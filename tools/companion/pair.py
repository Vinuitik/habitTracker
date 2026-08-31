#!/usr/bin/env python3
"""
HabitTracker companion — M5 device pairing.

Pairs THIS device with a user's HabitTracker account by redeeming a one-time pairing code
(generated from the "Pair a device" button in the web app's Connect Drive page), then getting
this device its OWN Google Drive access via the standard OAuth-for-installed-apps flow (the same
loopback-redirect pattern `gcloud`/`rclone` use). This device's refresh token is stored ONLY
locally and is completely independent of the server's own refresh token — the server never sees
it, and this script never sees the server's.

See README.md for setup (Google OAuth client credentials) and manual end-to-end verification
steps — this script cannot be exercised against real Google/Drive in an automated test
environment with no real OAuth credentials, so those steps are for a human to run once.

Usage:
    python pair.py pair --server https://habittrackerdima.me
    python pair.py write-test-kpi --kpi-name Steps --value 1234 --date 2026-08-31
    python pair.py show-config
"""
import argparse
import base64
import http.server
import json
import os
import secrets
import sys
import threading
import time
import urllib.parse
import uuid
import webbrowser
from pathlib import Path

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Module-level so tests can monkeypatch them to point at a local mock server instead of real
# Google / a real HabitTracker deployment — mirrors how DriveService's TOKEN_URL is a single
# named constant on the Java side.
GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

CONFIG_DIR = Path(os.environ.get("HABITTRACKER_COMPANION_HOME", Path.home() / ".habittracker_companion"))
CONFIG_FILE = CONFIG_DIR / "config.json"

IV_BYTES = 12  # matches VaultEncryptionService: wire format is [12B IV][ciphertext + 16B GCM tag]


# ── Config (local only — never sent anywhere) ────────────────────────────────────────────────

def load_config():
    if not CONFIG_FILE.exists():
        return {}
    return json.loads(CONFIG_FILE.read_text())


def save_config(config):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(config, indent=2))
    try:
        os.chmod(CONFIG_FILE, 0o600)  # best-effort; no-op on platforms that don't support it
    except OSError:
        pass


# ── Step 1: redeem the pairing code (POST /api/sync/pair — unauthenticated by server design) ──

def redeem_pairing_code(server, code):
    """Calls the server's unauthenticated pairing endpoint. Returns {mailboxFolderId,
    encryptionKey} or raises RuntimeError with the server's error message."""
    resp = requests.post(f"{server.rstrip('/')}/api/sync/pair", json={"code": code}, timeout=15)
    body = resp.json()
    if resp.status_code != 200:
        raise RuntimeError(body.get("error", f"pairing failed (HTTP {resp.status_code})"))
    return {"mailboxFolderId": body["mailboxFolderId"], "encryptionKey": body["encryptionKey"]}


# ── Step 2: this device's OWN Google OAuth-for-installed-apps flow ─────────────────────────────

class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    """One-shot handler: captures ?code=...&state=... from Google's redirect, then stops
    listening. Nothing else on this device ever needs to accept inbound connections."""

    def do_GET(self):
        params = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        self.server.oauth_result = {
            "code": params.get("code", [None])[0],
            "state": params.get("state", [None])[0],
            "error": params.get("error", [None])[0],
        }
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.end_headers()
        self.wfile.write(b"<html><body>Paired. You can close this tab.</body></html>")

    def log_message(self, *args):
        pass  # keep the CLI output clean


def run_oauth_flow(client_id, client_secret, open_browser=True, timeout_seconds=180, on_ready=None):
    """Standard installed-app / loopback OAuth flow (RFC 8252): a local one-shot HTTP server on
    an ephemeral port stands in for a registered redirect URI, the user consents in a real
    browser, Google redirects back to 127.0.0.1:<port>, and we exchange the code for tokens.
    Returns the TokenResponse dict (includes refresh_token) — this device's OWN token, the server
    never sees it and it is stored nowhere but CONFIG_FILE.

    on_ready(consent_url, redirect_uri, state), if given, is called once the loopback server is
    listening and before the browser opens — tests use this hook to simulate Google's redirect
    instead of driving a real browser through a real consent screen."""
    server = http.server.HTTPServer(("127.0.0.1", 0), _CallbackHandler)
    server.oauth_result = None
    port = server.server_address[1]
    redirect_uri = f"http://127.0.0.1:{port}"

    state = secrets.token_urlsafe(16)
    consent_url = GOOGLE_AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": DRIVE_SCOPE,
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    })

    thread = threading.Thread(target=server.handle_request, daemon=True)
    thread.start()

    if on_ready:
        on_ready(consent_url, redirect_uri, state)

    print(f"Opening browser for Google consent (listening on {redirect_uri}) ...")
    if open_browser:
        webbrowser.open(consent_url)
    else:
        print(f"Open this URL manually:\n{consent_url}")

    thread.join(timeout=timeout_seconds)
    still_alive = thread.is_alive()
    server.server_close()
    if still_alive:
        raise TimeoutError("Timed out waiting for Google OAuth redirect")

    result = server.oauth_result
    if result is None:
        raise RuntimeError("No redirect received from Google")
    if result.get("error"):
        raise RuntimeError(f"Google OAuth error: {result['error']}")
    if result.get("state") != state:
        raise RuntimeError("OAuth state mismatch — possible CSRF, aborting")
    if not result.get("code"):
        raise RuntimeError("Google redirect had no authorization code")

    return exchange_code(client_id, client_secret, result["code"], redirect_uri)


def exchange_code(client_id, client_secret, code, redirect_uri):
    resp = requests.post(GOOGLE_TOKEN_URL, data={
        "code": code,
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "grant_type": "authorization_code",
    }, timeout=15)
    body = resp.json()
    if resp.status_code != 200:
        raise RuntimeError(f"Token exchange failed: {body}")
    if "refresh_token" not in body:
        raise RuntimeError(
            "Google did not return a refresh_token — revoke prior access at "
            "https://myaccount.google.com/permissions and pair again"
        )
    return body


def refresh_access_token(client_id, client_secret, refresh_token):
    resp = requests.post(GOOGLE_TOKEN_URL, data={
        "client_id": client_id,
        "client_secret": client_secret,
        "refresh_token": refresh_token,
        "grant_type": "refresh_token",
    }, timeout=15)
    body = resp.json()
    if resp.status_code != 200:
        raise RuntimeError(f"Access token refresh failed: {body}")
    return body["access_token"]


# ── Step 3: encrypt + write one mailbox file (mirrors VaultEncryptionService + outbox.js) ─────

def encrypt_for_mailbox(encryption_key_b64, plaintext_bytes):
    """AES-256-GCM, wire format [12B IV][ciphertext + 16B GCM tag] — must match
    VaultEncryptionService.encrypt() exactly, since MailboxConsumeService.decrypt() expects it."""
    key = base64.b64decode(encryption_key_b64)
    iv = secrets.token_bytes(IV_BYTES)
    ciphertext = AESGCM(key).encrypt(iv, plaintext_bytes, None)
    return iv + ciphertext


def build_mailbox_batch(device_id, kind, payload):
    """Matches MailboxConsumeService.MailboxBatch / SyncRequest exactly."""
    request = {
        "requestId": str(uuid.uuid4()),
        "kind": kind,
        "ts": int(time.time() * 1000),
        "payload": payload,
    }
    return {"deviceId": device_id, "requests": [request]}


def upload_mailbox_file(access_token, mailbox_folder_id, wire_bytes, filename):
    """Two-step create: metadata POST then media PATCH — mirrors DriveService.uploadFile()."""
    headers_json = {"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"}
    meta = {"name": filename, "parents": [mailbox_folder_id]}
    created = requests.post(DRIVE_FILES_URL, headers=headers_json, json=meta, timeout=15).json()
    file_id = created["id"]

    headers_media = {"Authorization": f"Bearer {access_token}", "Content-Type": "application/octet-stream"}
    requests.patch(f"{DRIVE_UPLOAD_URL}/{file_id}?uploadType=media",
                    headers=headers_media, data=wire_bytes, timeout=15)
    return file_id


# ── CLI ──────────────────────────────────────────────────────────────────────────────────────

def cmd_pair(args):
    code = args.code or input("Enter the pairing code shown on the HabitTracker web app: ").strip()
    print("Redeeming pairing code ...")
    creds = redeem_pairing_code(args.server, code)
    print("Paired. Now connecting this device's own Google Drive access ...")

    client_id = args.client_id or os.environ.get("COMPANION_GOOGLE_CLIENT_ID")
    client_secret = args.client_secret or os.environ.get("COMPANION_GOOGLE_CLIENT_SECRET")
    if not client_id or not client_secret:
        print(
            "Missing Google OAuth client credentials. Pass --client-id/--client-secret or set "
            "COMPANION_GOOGLE_CLIENT_ID / COMPANION_GOOGLE_CLIENT_SECRET. See README.md.",
            file=sys.stderr,
        )
        sys.exit(1)

    tokens = run_oauth_flow(client_id, client_secret, open_browser=not args.no_browser)

    config = {
        "server": args.server,
        "mailboxFolderId": creds["mailboxFolderId"],
        "encryptionKey": creds["encryptionKey"],
        "clientId": client_id,
        "clientSecret": client_secret,
        "refreshToken": tokens["refresh_token"],
        "deviceId": str(uuid.uuid4()),
    }
    save_config(config)
    print(f"Done. This device's own Drive refresh token is stored at {CONFIG_FILE} — "
          f"independent of the server's, never uploaded anywhere.")


def cmd_write_test_kpi(args):
    config = load_config()
    if not config:
        print("Not paired yet — run `python pair.py pair` first.", file=sys.stderr)
        sys.exit(1)

    access_token = refresh_access_token(config["clientId"], config["clientSecret"], config["refreshToken"])
    payload = {"kpiName": args.kpi_name, "date": args.date, "value": args.value}
    batch = build_mailbox_batch(config["deviceId"], "kpi-value", payload)
    wire = encrypt_for_mailbox(config["encryptionKey"], json.dumps(batch).encode("utf-8"))

    filename = f"{config['deviceId']}-{int(time.time() * 1000)}-{uuid.uuid4()}.enc"
    file_id = upload_mailbox_file(access_token, config["mailboxFolderId"], wire, filename)
    print(f"Wrote {filename} (Drive file id {file_id}) — "
          f"MailboxConsumeService will pick it up on its next pass (every 15 min, or on boot).")


def cmd_show_config(args):
    config = load_config()
    if not config:
        print("Not paired yet.")
        return
    safe = {k: v for k, v in config.items() if k not in ("clientSecret", "refreshToken", "encryptionKey")}
    print(json.dumps(safe, indent=2))
    print("(clientSecret / refreshToken / encryptionKey withheld from this printout)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_pair = sub.add_parser("pair", help="Redeem a pairing code and connect this device's own Drive access")
    p_pair.add_argument("--server", required=True, help="e.g. https://habittrackerdima.me")
    p_pair.add_argument("--code", help="Pairing code (prompted for interactively if omitted)")
    p_pair.add_argument("--client-id")
    p_pair.add_argument("--client-secret")
    p_pair.add_argument("--no-browser", action="store_true", help="Print the consent URL instead of opening a browser")
    p_pair.set_defaults(func=cmd_pair)

    p_test = sub.add_parser("write-test-kpi", help="Write one hand-authored kind:kpi-value file into the mailbox")
    p_test.add_argument("--kpi-name", required=True)
    p_test.add_argument("--value", required=True, type=float)
    p_test.add_argument("--date", required=True, help="YYYY-MM-DD")
    p_test.set_defaults(func=cmd_write_test_kpi)

    p_show = sub.add_parser("show-config", help="Print the non-secret parts of the local config")
    p_show.set_defaults(func=cmd_show_config)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

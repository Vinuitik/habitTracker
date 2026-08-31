"""
Integration test for the M5 companion pairing handshake, run entirely against LOCAL mock
servers — no real Google credentials exist in this environment, so every Google call is
redirected to a throwaway local HTTP server standing in for oauth2.googleapis.com, and every
HabitTracker server call is redirected to a throwaway local HTTP server standing in for
POST /api/sync/pair.

Covers:
  1. redeem_pairing_code() against a mocked /api/sync/pair (valid code, invalid code)
  2. the full OAuth-for-installed-apps loopback flow (run_oauth_flow) against a mocked Google
     token endpoint, including the actual localhost redirect capture
  3. refresh_access_token() against the same mocked token endpoint
  4. encrypt_for_mailbox()/build_mailbox_batch() wire-format round trip (AES-256-GCM,
     [12B IV][ciphertext+tag] — must match VaultEncryptionService on the Java side)

Run with:  python3 -m unittest tools/companion/test_pair.py -v
"""
import base64
import http.server
import json
import os
import threading
import unittest
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import pair


def _start_server(handler_cls):
    server = http.server.HTTPServer(("127.0.0.1", 0), handler_cls)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


class _MockPairServer(http.server.BaseHTTPRequestHandler):
    """Stands in for the Java SyncController's POST /api/sync/pair."""

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        code = body.get("code")
        if code == "GOODCODE1":
            resp = {"mailboxFolderId": "mailbox-folder-xyz", "encryptionKey": "dGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISEh"}
            status = 200
        else:
            resp = {"error": "Invalid or expired code"}
            status = 400
        payload = json.dumps(resp).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class _MockGoogleTokenServer(http.server.BaseHTTPRequestHandler):
    """Stands in for https://oauth2.googleapis.com/token."""

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))
        grant_type = form.get("grant_type", [None])[0]

        if grant_type == "authorization_code":
            resp = {"access_token": "fake-access-token", "refresh_token": "fake-refresh-token", "expires_in": 3600}
        elif grant_type == "refresh_token":
            resp = {"access_token": "fake-refreshed-access-token", "expires_in": 3600}
        else:
            resp = {"error": "unsupported_grant_type"}

        payload = json.dumps(resp).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class PairingHandshakeTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.pair_server, cls.pair_thread = _start_server(_MockPairServer)
        cls.pair_server_url = f"http://127.0.0.1:{cls.pair_server.server_address[1]}"

        cls.google_server, cls.google_thread = _start_server(_MockGoogleTokenServer)
        cls._orig_token_url = pair.GOOGLE_TOKEN_URL
        pair.GOOGLE_TOKEN_URL = f"http://127.0.0.1:{cls.google_server.server_address[1]}/token"

    @classmethod
    def tearDownClass(cls):
        cls.pair_server.shutdown()
        cls.pair_server.server_close()
        cls.google_server.shutdown()
        cls.google_server.server_close()
        pair.GOOGLE_TOKEN_URL = cls._orig_token_url

    # ── Step 1: redeem_pairing_code against the mocked server ──────────────────────────────

    def test_redeem_pairing_code_valid(self):
        creds = pair.redeem_pairing_code(self.pair_server_url, "GOODCODE1")
        self.assertEqual(creds["mailboxFolderId"], "mailbox-folder-xyz")
        self.assertEqual(creds["encryptionKey"], "dGVzdC1rZXktMzItYnl0ZXMtbG9uZyEhISEh")

    def test_redeem_pairing_code_invalid_raises(self):
        with self.assertRaises(RuntimeError):
            pair.redeem_pairing_code(self.pair_server_url, "BADCODE")

    # ── Step 2: full OAuth-for-installed-apps loopback flow, against mocked Google ─────────

    def test_full_oauth_loopback_flow_against_mocked_google(self):
        result_holder = {}

        def simulate_google_redirect(consent_url, redirect_uri, state):
            # This is exactly what Google would do after the user consents: 302 the browser back
            # to our loopback redirect_uri with ?code=...&state=.... We drive it directly instead
            # of a real browser/consent screen (no real Google credentials exist here).
            def fire():
                urllib.request.urlopen(f"{redirect_uri}?code=fake-auth-code&state={state}", timeout=5).read()
            threading.Thread(target=fire, daemon=True).start()

        result_holder["tokens"] = pair.run_oauth_flow(
            "fake-client-id", "fake-client-secret",
            open_browser=False, timeout_seconds=10, on_ready=simulate_google_redirect,
        )

        tokens = result_holder.get("tokens")
        self.assertIsNotNone(tokens)
        self.assertEqual(tokens["refresh_token"], "fake-refresh-token")
        self.assertEqual(tokens["access_token"], "fake-access-token")

    def test_refresh_access_token_against_mocked_google(self):
        token = pair.refresh_access_token("fake-client-id", "fake-client-secret", "fake-refresh-token")
        self.assertEqual(token, "fake-refreshed-access-token")

    # ── Step 3: mailbox encryption wire format ──────────────────────────────────────────────

    def test_mailbox_encryption_round_trip_matches_vault_encryption_service_format(self):
        key_b64 = base64.b64encode(os.urandom(32)).decode("ascii")
        batch = pair.build_mailbox_batch("device-123", "kpi-value", {"kpiName": "Steps", "date": "2026-08-31", "value": 42.0})
        wire = pair.encrypt_for_mailbox(key_b64, json.dumps(batch).encode("utf-8"))

        # [12B IV][ciphertext + 16B GCM tag] — decrypt exactly the way VaultEncryptionService does.
        iv, ciphertext = wire[:12], wire[12:]
        plaintext = AESGCM(base64.b64decode(key_b64)).decrypt(iv, ciphertext, None)
        decoded = json.loads(plaintext)

        self.assertEqual(decoded["deviceId"], "device-123")
        self.assertEqual(len(decoded["requests"]), 1)
        req = decoded["requests"][0]
        self.assertEqual(req["kind"], "kpi-value")
        self.assertEqual(req["payload"], {"kpiName": "Steps", "date": "2026-08-31", "value": 42.0})
        self.assertIn("requestId", req)
        self.assertIn("ts", req)


if __name__ == "__main__":
    unittest.main()

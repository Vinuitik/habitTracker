"""Encrypted MongoDB → Google Drive backup, with restore.

Backup:  mongodump → zip → gzip → AES-256-GCM → Drive
Restore: Drive → AES-256-GCM → gunzip → unzip → mongorestore

Auth is OAuth (refresh token), NOT a service account: service accounts lost My Drive
storage quota, so SA uploads fail. Scope is drive.file — this app only ever sees files
it created itself, which is why the Drive folder is found-or-created rather than assumed.

Wire format is byte-identical to ObsidianOptimizer's VaultEncryptionService, so a blob
written here can be decrypted by the same scheme:  [12B IV][AES-GCM ciphertext + 16B tag]

CLI:
    python backup.py           # loop: backup every BACKUP_INTERVAL_HOURS
    python backup.py backup    # one backup, then exit
    python backup.py restore   # restore newest backup, then exit
    python backup.py list      # list backups on Drive, then exit
"""

import datetime
import gzip
import os
import shutil
import subprocess
import sys
import tempfile
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload, MediaIoBaseDownload

# --- Mongo ---
MONGO_USER = os.getenv("MONGO_USER")
MONGO_PASS = os.getenv("MONGO_PASS")
MONGO_DB = os.getenv("MONGO_DB")
MONGO_AUTH_DB = os.getenv("MONGO_AUTH_DB", "admin")
MONGO_HOST = os.getenv("MONGO_HOST", "localhost")

# --- Google Drive (OAuth) ---
CLIENT_ID = os.getenv("GOOGLE_OAUTH_CLIENT_ID", "")
CLIENT_SECRET = os.getenv("GOOGLE_OAUTH_CLIENT_SECRET", "")
REFRESH_TOKEN = os.getenv("GOOGLE_OAUTH_REFRESH_TOKEN", "")
TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPES = ["https://www.googleapis.com/auth/drive.file"]
FOLDER_MIME = "application/vnd.google-apps.folder"
TARGET_FOLDER_NAME = os.getenv("BACKUP_FOLDER_NAME", "HabitBackups v2")

# --- Policy ---
PASSPHRASE = os.getenv("BACKUP_PASSPHRASE", "")
KEEP = int(os.getenv("BACKUP_KEEP", "5"))
INTERVAL_HOURS = float(os.getenv("BACKUP_INTERVAL_HOURS", "12"))

# Matches ObsidianOptimizer's scheme; the salt is habitTracker-specific so the same
# passphrase never derives the same key across the two apps.
PBKDF2_SALT = b"HabitBackupSalt"
PBKDF2_ITERATIONS = 310_000


# ── Encryption ──────────────────────────────────────────────────────────────────

def _key() -> bytes:
    if not PASSPHRASE:
        raise RuntimeError("BACKUP_PASSPHRASE is not set — refusing to write an unencrypted backup")
    return PBKDF2HMAC(
        algorithm=SHA256(), length=32, salt=PBKDF2_SALT, iterations=PBKDF2_ITERATIONS
    ).derive(PASSPHRASE.encode())


def encrypt(plaintext: bytes) -> bytes:
    iv = os.urandom(12)
    return iv + AESGCM(_key()).encrypt(iv, gzip.compress(plaintext), None)


def decrypt(blob: bytes) -> bytes:
    # A wrong passphrase or a corrupted blob fails the GCM auth tag here, not silently.
    return gzip.decompress(AESGCM(_key()).decrypt(blob[:12], blob[12:], None))


# ── Drive ───────────────────────────────────────────────────────────────────────

def drive():
    missing = [n for n, v in [
        ("GOOGLE_OAUTH_CLIENT_ID", CLIENT_ID),
        ("GOOGLE_OAUTH_CLIENT_SECRET", CLIENT_SECRET),
        ("GOOGLE_OAUTH_REFRESH_TOKEN", REFRESH_TOKEN),
    ] if not v]
    if missing:
        raise RuntimeError(f"Google OAuth not configured — missing: {', '.join(missing)}")
    creds = Credentials(
        token=None,
        refresh_token=REFRESH_TOKEN,
        client_id=CLIENT_ID,
        client_secret=CLIENT_SECRET,
        token_uri=TOKEN_URI,
        scopes=SCOPES,
    )
    creds.refresh(Request())  # fail fast and loudly on a dead/revoked refresh token
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def folder_id(svc) -> str:
    """Find-or-create. Under drive.file we only see folders this client created, so a
    folder made by the old service account is invisible here and a new one is created."""
    q = f"name = '{TARGET_FOLDER_NAME}' and mimeType = '{FOLDER_MIME}' and trashed = false"
    found = svc.files().list(q=q, fields="files(id)", spaces="drive").execute().get("files", [])
    if found:
        return found[0]["id"]
    meta = {"name": TARGET_FOLDER_NAME, "mimeType": FOLDER_MIME}
    fid = svc.files().create(body=meta, fields="id").execute()["id"]
    print(f"[*] Created Drive folder '{TARGET_FOLDER_NAME}' ({fid})")
    return fid


def list_backups(svc) -> list:
    """Newest first."""
    fid = folder_id(svc)
    return svc.files().list(
        q=f"'{fid}' in parents and trashed = false",
        orderBy="createdTime desc",
        fields="files(id,name,size,createdTime)",
        spaces="drive",
    ).execute().get("files", [])


def prune(svc) -> None:
    for f in list_backups(svc)[KEEP:]:
        svc.files().delete(fileId=f["id"]).execute()
        print(f"[*] Pruned old backup {f['name']}")


# ── Backup ──────────────────────────────────────────────────────────────────────

def do_backup() -> None:
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    name = f"backup_{stamp}.zip.enc"
    work = tempfile.mkdtemp(prefix="backup-")
    try:
        print("[*] Dumping MongoDB...")
        subprocess.run([
            "mongodump",
            f"--host={MONGO_HOST}",
            f"--username={MONGO_USER}",
            f"--password={MONGO_PASS}",
            f"--authenticationDatabase={MONGO_AUTH_DB}",
            f"--db={MONGO_DB}",
            f"--out={work}/dump",
        ], check=True)

        print("[*] Compressing + encrypting...")
        archive = shutil.make_archive(f"{work}/archive", "zip", f"{work}/dump")
        with open(archive, "rb") as fh:
            blob = encrypt(fh.read())
        enc_path = f"{work}/{name}"
        with open(enc_path, "wb") as fh:
            fh.write(blob)

        print(f"[*] Uploading {name} ({len(blob)} bytes)...")
        svc = drive()
        svc.files().create(
            body={"name": name, "parents": [folder_id(svc)]},
            media_body=MediaFileUpload(enc_path, mimetype="application/octet-stream"),
            fields="id",
        ).execute()
        prune(svc)
        print(f"[✓] Backup uploaded: {name}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ── Restore ─────────────────────────────────────────────────────────────────────

def do_restore(file_name: str | None = None) -> None:
    """Restores the newest backup (or a named one). mongorestore --drop replaces the
    target DB's collections — this is destructive by design."""
    svc = drive()
    backups = list_backups(svc)
    if not backups:
        raise RuntimeError(f"No backups found in Drive folder '{TARGET_FOLDER_NAME}'")
    target = next((b for b in backups if b["name"] == file_name), None) if file_name else backups[0]
    if target is None:
        raise RuntimeError(f"Backup '{file_name}' not found")

    work = tempfile.mkdtemp(prefix="restore-")
    try:
        print(f"[*] Downloading {target['name']}...")
        enc_path = f"{work}/{target['name']}"
        with open(enc_path, "wb") as fh:
            dl = MediaIoBaseDownload(fh, svc.files().get_media(fileId=target["id"]))
            done = False
            while not done:
                _, done = dl.next_chunk()

        print("[*] Decrypting...")
        with open(enc_path, "rb") as fh:
            plaintext = decrypt(fh.read())
        zip_path = f"{work}/archive.zip"
        with open(zip_path, "wb") as fh:
            fh.write(plaintext)
        shutil.unpack_archive(zip_path, f"{work}/dump", "zip")

        print("[*] Restoring into MongoDB (--drop)...")
        subprocess.run([
            "mongorestore",
            f"--host={MONGO_HOST}",
            f"--username={MONGO_USER}",
            f"--password={MONGO_PASS}",
            f"--authenticationDatabase={MONGO_AUTH_DB}",
            "--drop",
            f"--nsInclude={MONGO_DB}.*",
            f"{work}/dump",
        ], check=True)
        print(f"[✓] Restored from {target['name']}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ── Entrypoint ──────────────────────────────────────────────────────────────────

def main() -> int:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "loop"
    if cmd == "backup":
        do_backup()
    elif cmd == "restore":
        do_restore(sys.argv[2] if len(sys.argv) > 2 else None)
    elif cmd == "list":
        for b in list_backups(drive()):
            print(f"{b['createdTime']}  {b.get('size', '?'):>10}  {b['name']}")
    elif cmd == "loop":
        while True:
            try:
                do_backup()
            except Exception as e:
                print(f"[!] Backup failed: {e}")
            print(f"[*] Sleeping for {INTERVAL_HOURS}h...")
            time.sleep(INTERVAL_HOURS * 3600)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

import subprocess
import datetime
import os
import shutil
import time  # <-- Add this import
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from google.oauth2 import service_account

# --- CONFIG ---
MONGO_USER = os.getenv("MONGO_USER")
MONGO_PASS = os.getenv("MONGO_PASS")
MONGO_DB = os.getenv("MONGO_DB")
MONGO_AUTH_DB = os.getenv("MONGO_AUTH_DB", "admin")
MONGO_HOST = os.getenv("MONGO_HOST", "localhost")
SERVICE_ACCOUNT_FILE = 'habitbackup.json'
SCOPES = ['https://www.googleapis.com/auth/drive']
TARGET_FOLDER_NAME = 'HabitBackups'

DUMP_DIR = 'dump'

def do_backup():
    ARCHIVE_NAME = f"backup_{datetime.datetime.now().strftime('%Y-%m-%d_%H-%M-%S')}.zip"

    # --- Dump MongoDB ---
    print("[*] Dumping MongoDB...")
    subprocess.run([
        'mongodump',
        f'--host={MONGO_HOST}',
        f'--username={MONGO_USER}',
        f'--password={MONGO_PASS}',
        f'--authenticationDatabase={MONGO_AUTH_DB}',
        f'--db={MONGO_DB}',
        f'--out={DUMP_DIR}'
    ], check=True)

    # --- Compress ---
    print("[*] Compressing backup...")
    shutil.make_archive(ARCHIVE_NAME.replace('.zip', ''), 'zip', DUMP_DIR)

    # --- Upload to Google Drive ---
    print("[*] Authenticating with Google Drive...")
    creds = service_account.Credentials.from_service_account_file(SERVICE_ACCOUNT_FILE, scopes=SCOPES)
    drive_service = build('drive', 'v3', credentials=creds)

    query = f"name = '{TARGET_FOLDER_NAME}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
    results = drive_service.files().list(q=query, fields="files(id)").execute()
    folder_id = results.get('files', [])[0]['id']

    print(f"[*] Uploading {ARCHIVE_NAME}...")
    file_metadata = {'name': ARCHIVE_NAME, 'parents': [folder_id]}
    media = MediaFileUpload(ARCHIVE_NAME, mimetype='application/zip')
    drive_service.files().create(body=file_metadata, media_body=media, fields='id').execute()

    print("[✓] Backup uploaded!")

    # --- Clean up ---
    shutil.rmtree(DUMP_DIR)
    os.remove(ARCHIVE_NAME)
    print("[*] Cleaning up...")
    print("[✓] Backup completed successfully!")

if __name__ == "__main__":
    while True:
        try:
            do_backup()
        except Exception as e:
            print(f"[!] Backup failed: {e}")
        print("[*] Sleeping for 12 hours...")
        time.sleep(43200)  # 12 hours in seconds
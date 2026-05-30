# Backup Service — Flow Index

| Subsystem | File |
|---|---|
| MongoDB → Google Drive backup | see below |
| Trello MCP server + cron | `FLOWS_mcp.md` |

---

# Backup Flow

Files: `backup.py`

## Trigger

Infinite loop in `backup.py › do_backup()` — runs, then `time.sleep(43200)` (12 hours), repeats.
No external scheduler; restarts only if container restarts.

---

## Steps

1. `mongodump` called via `subprocess.run()`
   - Dumps `habits` DB to local `dump/` directory
   - To change database or host: env vars `MONGO_DB`, `MONGO_HOST`, `MONGO_USER`, `MONGO_PASS`, `MONGO_AUTH_DB`
2. `shutil.make_archive()` → compress `dump/` to timestamped `.zip` (`backup_YYYY-MM-DD_HH-MM-SS.zip`)
3. Authenticate with Google Drive API
   - `service_account.Credentials.from_service_account_file('habitbackup.json')`
   - `habitbackup.json` = Google service account key file, mounted into container at runtime
   - To rotate credentials: replace `habitbackup.json` + rebuild/restart container
   - Scope: `https://www.googleapis.com/auth/drive` (full Drive access)
4. Locate target folder: Drive API files list query for `name = 'HabitBackups'` (folder must exist manually)
   - To change target folder: `TARGET_FOLDER_NAME` constant in `backup.py`
   - If folder not found: runtime `IndexError` — backup fails silently (caught by outer `try/except`)
5. Upload zip via `MediaFileUpload` → `drive_service.files().create()`
6. `shutil.rmtree(dump/)` + `os.remove(archive)` — clean up local files

Failures are caught by outer `try/except` in `__main__` — logged, then loop continues with next 12h sleep.

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Backup interval | `backup.py` → `time.sleep(43200)` | seconds; currently 12h |
| Target database | `MONGO_DB` env var | |
| MongoDB connection | `MONGO_HOST`, `MONGO_USER`, `MONGO_PASS`, `MONGO_AUTH_DB` env vars | |
| Google Drive target folder | `TARGET_FOLDER_NAME` constant in `backup.py` | folder must exist manually in Drive |
| Google Drive credentials | Replace `habitbackup.json` + restart container | service account key from Google Cloud Console |
| Missing folder handling | `backup.py › do_backup()` after `drive_service.files().list(...)` | currently `IndexError` → silent failure |

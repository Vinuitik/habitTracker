# Backup Service — Flow Index

| Subsystem | File |
|---|---|
| MongoDB → Google Drive backup + restore | see below |
| Trello MCP server + cron | `FLOWS_mcp.md` |

---

# Backup Flow

Files: `backup.py`, `Dockerfile`, `start.sh`

Encrypted `mongodump` → Google Drive, with a restore path back. Auth is **OAuth**
(refresh token), not a service account. Scope is `drive.file`.

## Trigger

`backup.py › main()` — `loop` (default, via `start.sh`) runs `do_backup()` then sleeps
`BACKUP_INTERVAL_HOURS` (default 12). No external scheduler; restarts with the container.

Manual (one-shot, exits after):
```sh
docker exec mongo-backup python backup.py backup    # back up now
docker exec mongo-backup python backup.py list      # list backups on Drive
docker exec mongo-backup python backup.py restore   # restore NEWEST — destructive
docker exec mongo-backup python backup.py restore backup_2026-07-16_21-10-25.zip.enc
```

---

## Backup Steps

```
mongodump ──► zip ──► gzip ──► AES-256-GCM ──► Drive
```

1. `do_backup()` → `mongodump` via `subprocess.run(check=True)` into a `tempfile.mkdtemp()`
   working dir (removed in `finally` — no state survives a crash).
   To change DB/host: `MONGO_DB`, `MONGO_HOST`, `MONGO_USER`, `MONGO_PASS`, `MONGO_AUTH_DB`.
2. `shutil.make_archive(...,'zip')` → `encrypt()` → `[12B IV][AES-GCM ct+tag]`.
3. `drive()` — builds OAuth creds from `GOOGLE_OAUTH_CLIENT_ID/SECRET/REFRESH_TOKEN`,
   then `creds.refresh()` **eagerly** so a dead token fails here, loudly, not mid-upload.
4. `folder_id()` — **find-or-create** `BACKUP_FOLDER_NAME`. Never assumes the folder exists.
5. `files().create()` upload as `backup_YYYY-MM-DD_HH-MM-SS.zip.enc`.
6. `prune()` — hard-deletes everything past `BACKUP_KEEP` (newest-first by `createdTime`).

## Restore Steps

```
Drive ──► AES-256-GCM ──► gunzip ──► unzip ──► mongorestore --drop
```

`do_restore(name=None)` → `list_backups()` (newest first) → `get_media` chunked download →
`decrypt()` → `unpack_archive` → `mongorestore --drop --nsInclude=<MONGO_DB>.*`.

**Destructive**: `--drop` replaces the target DB's collections. A wrong passphrase raises
`InvalidTag` during `decrypt()` — *before* `mongorestore` runs, so a bad key can never
half-write garbage into the DB.

---

## Encryption

`encrypt()` / `decrypt()` — wire format is byte-identical to ObsidianOptimizer's
`VaultEncryptionService`:

- Key: `PBKDF2HMAC(SHA256, passphrase, PBKDF2_SALT, 310_000 iter)` → 256-bit AES key
- `gzip` → 12B random IV → AES-256-GCM → `[IV][ciphertext + 16B auth tag]`
- Passphrase: `BACKUP_PASSPHRASE`. Missing → `_key()` raises, refusing to upload plaintext.
- Salt is `b"HabitBackupSalt"` — deliberately different from OO's `ObsidianSyncSalt`, so the
  same passphrase never derives the same key across the two apps.

---

## Technology Notes

- **`BACKUP_PASSPHRASE` is the only key, and it is not stored on Drive. Lose it and every
  backup is permanently unreadable.** There is no recovery path — the GCM tag guarantees a
  wrong key fails cleanly rather than yielding partial data. Keep a copy outside this repo.
- **The refresh token is SHARED with ObsidianOptimizer** (same Google OAuth client, same
  token, copied from OO's `app_settings.sync.refresh_token`). Clicking **Disconnect** in OO's
  Settings calls Google's revoke endpoint and **silently kills these backups too**. Symptom:
  `creds.refresh()` throws on the next run. Fix: reconnect in OO, then re-copy its
  `sync.refresh_token` into `GOOGLE_OAUTH_REFRESH_TOKEN` here. To decouple, mint a separate
  refresh token from the same client (needs a redirect URI registered + one-time consent).
- **Refresh tokens die on their own too**: if the OAuth client's publishing status is
  "Testing", Google expires tokens after ~7 days. It must be **"In production"**.
- **Scope is `drive.file`, NOT full Drive.** This client can only see files it created
  itself. Consequences: (1) the old service-account `HabitBackups` folder is **invisible**
  here — hence `HabitBackups v2`; (2) `folder_id()` MUST find-or-create, because a by-name
  lookup for a folder someone else made returns empty; (3) nothing in your wider Drive is
  readable by this service, by design.
- **Service accounts are a dead end for this.** The old model authenticated as a SA, which
  lost My Drive storage quota — SA uploads fail on quota regardless of code correctness.
  This is why the migration to OAuth was necessary, not just tidier.
- **Base image is pinned to `python:3.10-slim-bookworm` on purpose.** It was unpinned, floated
  to Debian 13 (trixie), and silently broke `mongodump` — the binary is copied out of
  `mongo:7.0-jammy` (Ubuntu) and links `libgssapi_krb5.so.2`, which trixie's slim image
  doesn't ship. `libgssapi-krb5-2` is installed explicitly for the same reason. **Any bump of
  either image can re-break this; re-test `mongodump --version` in the built image after one.**
- **`PYTHONUNBUFFERED=1` is load-bearing.** Without it Python block-buffers stdout when not a
  TTY, so `[!] Backup failed` never reaches `docker logs` — the original failure was loud in
  code and invisible in practice for days. Do not remove it.
- **Still no alerting.** A failure prints to `docker logs` and the loop sleeps 12h. Nobody is
  told. If backups matter, the next gap to close is a heartbeat/dead-man's-switch, not more
  retry logic. `[NOT IMPLEMENTED]`
- **No retry within a pass**: a transient Drive 429/5xx loses that cycle entirely and waits
  the full interval. OO's `DriveService.withRetry` is the pattern to copy if this bites.
- **Backups are FULL each cycle**, not incremental. Fine at this DB size (a seeded 4-doc test
  encrypted to 747 bytes); revisit if `habits` grows to hundreds of MB.
- **`mem_limit: 128m`** covers this container AND the MCP server (`start.sh` runs both).
  `encrypt()` holds the whole dump in memory as a single `bytes` — a large DB will OOM the
  container before it fails on anything else.
- **Compose fails fast** on missing OAuth/passphrase vars (`${VAR:?...}`) rather than starting
  a container that silently never backs up — the exact failure mode this service already had.

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Backup interval | `BACKUP_INTERVAL_HOURS` env | default 12 (hours) |
| Backups retained | `BACKUP_KEEP` env | default 5; older hard-deleted by `prune()` |
| Drive folder name | `BACKUP_FOLDER_NAME` env | default `HabitBackups v2`; find-or-created |
| Encryption passphrase | `BACKUP_PASSPHRASE` env | **the only key — no recovery if lost** |
| PBKDF2 iterations / salt | `backup.py` `PBKDF2_ITERATIONS` / `PBKDF2_SALT` | changing either invalidates all existing backups |
| Wire format | `encrypt()` / `decrypt()` | `[12B IV][AES-GCM ct+tag]`, matches OO |
| Google credentials | `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REFRESH_TOKEN` env | shared with ObsidianOptimizer |
| OAuth scope | `backup.py` `SCOPES` | `drive.file` — widening needs re-consent |
| Target database | `MONGO_DB` env | |
| MongoDB connection | `MONGO_HOST`, `MONGO_USER`, `MONGO_PASS`, `MONGO_AUTH_DB` env | |
| Restore target/behaviour | `do_restore()` | `--drop --nsInclude=<MONGO_DB>.*` |
| Manual backup / restore / list | `python backup.py backup|restore|list` | via `docker exec mongo-backup` |
| mongodump shared libs | `Dockerfile` `libgssapi-krb5-2` | required by the jammy binary |
| Base image pin | `Dockerfile` `python:3.10-slim-bookworm` | unpinning re-breaks `mongodump` |
| Log visibility | `Dockerfile` `ENV PYTHONUNBUFFERED=1` | removing it re-hides all failures |

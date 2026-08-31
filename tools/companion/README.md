# HabitTracker companion (M5 device pairing, M6 capability-deploy polling)

A minimal script that pairs a device with your HabitTracker account and can write one
hand-authored test file into your Drive mailbox, proving the pairing handshake and
`MailboxConsumeService` both work end to end (M5). M6 adds the reverse channel:
`poll-capabilities` checks a second, separate Drive folder the server can write
`{capabilityId, version, sourceCode}` files into, and updates a local per-capability version
cache — still no capability code actually runs as a result; that's a later milestone (M8/M9, see
`docs/designs/kpi-tracking-agent.md`). M6 only proves the delivery/version-tracking mechanics.

Windows-first (any platform with Python 3.9+ works the same way — no OS-specific code was used).

## Setup

```powershell
cd tools\companion
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

You also need a Google OAuth client (Desktop app type) for this device's own Drive access —
independent of the server's own `GOOGLE_OAUTH_CLIENT_ID/SECRET`. **This script defaults to
letting you reuse the server's existing OAuth client** (same one already configured for
`javaapp`/`mongo-backup`, since it's already added as a "Testing" app with test users — see
`src/main/java/habitTracker/sync/FLOWS.md`), since registering a second Google Cloud OAuth client
just for this script wasn't specified and adds an extra manual setup step for no real benefit at
this milestone. If you'd rather isolate them, register a second "Desktop app" OAuth client in the
same Google Cloud project and pass its id/secret instead — either way, set:

```powershell
$env:COMPANION_GOOGLE_CLIENT_ID = "..."
$env:COMPANION_GOOGLE_CLIENT_SECRET = "..."
```

(or pass `--client-id`/`--client-secret` on the command line).

## Usage

```powershell
python pair.py pair --server https://habittrackerdima.me
```

1. Prompts for the pairing code shown by the "Pair a device" button on the Connect Drive page
   (`/connect-drive`) in the web app — you must be logged in there to generate one.
2. Redeems it via `POST /api/sync/pair`, getting back this account's `mailboxFolderId` +
   `encryptionKey`.
3. Opens a browser to Google's consent screen for **this device's own** Drive access
   (`drive.file` scope) and listens on a random localhost port for the redirect — the standard
   OAuth-for-installed-apps / loopback flow (RFC 8252), the same pattern `gcloud`/`rclone` use.
4. Stores everything locally at `~/.habittracker_companion/config.json` (`%USERPROFILE%\.habittracker_companion\config.json`
   on Windows). This device's refresh token never leaves this file and is completely independent
   of the server's own refresh token — pairing never touches or exposes that.

```powershell
python pair.py write-test-kpi --kpi-name Steps --value 1234 --date 2026-08-31
```

Writes one encrypted `kind: "kpi-value"` file straight into the paired account's Drive mailbox
folder, using this device's own (locally refreshed) Drive access token — exactly the shape
`MailboxConsumeService` already knows how to consume.

```powershell
python pair.py show-config
```

Prints the non-secret parts of the local config (server URL, mailbox folder id, device id) for
sanity-checking — secrets are withheld from the printout.

```powershell
python pair.py poll-capabilities
```

M6. Checks this account's `_capability_deploy` Drive folder (a separate folder from the inbound
mailbox — server writes here, this device only ever reads) for capability files, decrypts each
with the same per-user key, and compares its `version` field against
`~/.habittracker_companion/capabilities.json`: strictly newer versions are downloaded and cached,
the current version is skipped, and older/malformed payloads are rejected and logged — never
raises on a single bad file. Prints one `ACCEPT`/`SKIP`/`REJECT` line per file found. If this
account has never had a capability deployed to it, `capabilityDeployFolderId` will be `null` (it's
created lazily server-side on first deploy) and this command has nothing to do — re-run
`python pair.py pair` after a first deploy to pick up the folder id.

## Automated tests

```powershell
python -m unittest test_pair -v
python -m unittest test_capability_deploy -v
```

`test_pair.py` runs entirely against local mock servers standing in for both the HabitTracker
server (`/api/sync/pair`) and Google's OAuth token endpoint — **no real Google credentials are
used or needed**, since none exist in the dev/CI environment. Covers: pairing-code redemption
(valid/invalid), the full loopback OAuth flow end to end (including the actual localhost redirect
capture, not just the token exchange), the refresh-token grant, and the AES-256-GCM mailbox wire
format round-tripping exactly the way `VaultEncryptionService` expects it.

`test_capability_deploy.py` covers the M6 reverse channel: `evaluate_capability_version()` as a
pure function (skip/accept/reject decisions, including malformed-payload rejection), and
`poll_capability_deploy()` end to end against a local mock standing in for the Drive v3 REST API's
list+download endpoints, seeded with payloads encrypted exactly the way
`CapabilityDeployService`/`VaultEncryptionService` produce them server-side.

## Manual verification (do this once against the real deployment — cannot be automated here)

This environment has no real Google OAuth credentials, so the actual pairing handshake against
real Google/Drive has never been exercised end to end. A human needs to run this once:

1. **Generate a pairing code.** Log into the real HabitTracker web app (must already have Drive
   connected via the existing "Connect Google Drive" button first — pairing requires it). Go to
   `/connect-drive`, click **Pair a device**, note the 8-character code and the fact it expires
   in 10 minutes.

2. **Pair the device.**
   ```powershell
   python pair.py pair --server https://habittrackerdima.me
   ```
   Enter the code when prompted. Confirm:
   - the script prints "Paired." before opening the browser,
   - the Google consent screen shows the correct app and `drive.file` scope,
   - after consenting, the script prints "Done." and `~/.habittracker_companion/config.json`
     exists with `mailboxFolderId`, `encryptionKey`, and `refreshToken` populated.
   - Re-run `python pair.py pair` with the **same** code a second time and confirm it fails with
     `Invalid or expired code` (single-use enforcement).

3. **Write one test KPI value.**
   ```powershell
   python pair.py write-test-kpi --kpi-name <an existing KPI's exact name> --value 42 --date 2026-08-31
   ```
   Confirm it prints a Drive file id.

4. **Confirm the server consumed it.** Within 15 minutes (or immediately after a server restart,
   since `MailboxConsumeService.consumeOnStartup()` runs on boot), check:
   - the KPI's data in the web app (`/kpis/dashboard` or the KPI's own data view) now shows a
     value of `42` on `2026-08-31`,
   - the Drive file written in step 3 has been deleted from the `_mailbox_requests` folder
     (delete-after-success — see `MailboxConsumeService` in
     `src/main/java/habitTracker/sync/FLOWS.md`),
   - server logs show no `[MailboxConsume]` error lines for that file.

If the KPI name doesn't exist for that account, `KPIService.addKPIDataForUser()` will fail and
the mailbox file will be left for retry forever (see FLOWS.md's "permanently-failing request"
note) — use a real existing KPI name for this check, or watch the logs to confirm the expected
failure mode instead.

## Design decisions made in this milestone that weren't fully specified upstream

- **Pairing code format**: 8 characters, uppercase, from an alphabet excluding visually-ambiguous
  characters (`0/O/1/I/L`) — optimized for a human typing it into this script by hand. Not
  specified in the design doc beyond "short one-time code."
- **Companion OAuth client credentials**: the design doc says the companion's refresh token must
  be independent of the server's, but doesn't say whether the companion needs its *own* Google
  Cloud OAuth client (separate client id/secret) or can reuse the server's existing one. This
  script reuses the server's by default (simplest — that client is already "Testing" status with
  test users configured) while allowing an override. Flagging this as a call worth revisiting if
  this project ever moves the OAuth client out of "Testing" status, since a shared client means
  every companion device shows up as the same "app" in the user's Google account permissions
  list.
- **Local config storage**: plain JSON at `~/.habittracker_companion/config.json`, `chmod 600`
  best-effort. No OS keychain integration — consistent with "minimal", matches the milestone's
  scope (prove pairing works, not harden secret storage).

### M6 additions

- **Capability version is a plain positive integer**, not semver — not specified upstream beyond
  "version field." Simplest possible "strictly newer" rule for both sides; revisit if capabilities
  ever need coordinated multi-part version numbers.
- **`_capability_deploy` folder is created lazily** (on the server's first
  `CapabilityDeployService.deployCapability()` call), unlike `_mailbox_requests` which is created
  eagerly at connect time. A freshly-paired device gets `capabilityDeployFolderId: null` until
  this account's first deploy and must re-run `pair` afterward to pick it up — not automatic. This
  keeps every already-connected user's Drive free of an empty folder for a feature that doesn't
  execute anything yet.
- **Local capabilities cache** (`~/.habittracker_companion/capabilities.json`): plain JSON,
  `{capabilityId: {version, sourceCode, updatedAt}}`, no chmod (not treated as a secrets file the
  way `config.json` is — `sourceCode` isn't a credential).
- **No delete/cleanup of `_capability_deploy` files** on either side — every poll re-lists and
  re-downloads every historical file, forever. Unlike the inbound mailbox (which self-cleans via
  delete-on-success), nothing here deletes anything. Fine at this milestone's scale; flagged in
  `FLOWS.md` as needing either a companion-side delete-after-cache-update step or a server-side
  retention policy if deploy volume grows.

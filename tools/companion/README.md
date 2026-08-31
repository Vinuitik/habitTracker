# HabitTracker companion (M5 — device pairing)

A minimal script that pairs a device with your HabitTracker account and can write one
hand-authored test file into your Drive mailbox, proving the pairing handshake and
`MailboxConsumeService` both work end to end. This is the foundation M6+ build on for actual
push-proxy capabilities (see `docs/designs/kpi-tracking-agent.md`) — this milestone does not run
any capability code, it only proves a device can pair and write.

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

## Automated tests

```powershell
python -m unittest test_pair -v
```

Runs entirely against local mock servers standing in for both the HabitTracker server
(`/api/sync/pair`) and Google's OAuth token endpoint — **no real Google credentials are used or
needed**, since none exist in the dev/CI environment. Covers: pairing-code redemption
(valid/invalid), the full loopback OAuth flow end to end (including the actual localhost redirect
capture, not just the token exchange), the refresh-token grant, and the AES-256-GCM mailbox wire
format round-tripping exactly the way `VaultEncryptionService` expects it.

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

## Coding-hours capability (M9 — reference push capability)

A real, working example push capability: `coding_hours_capability.py` tracks how many hours are
spent with a coding-related window focused (VS Code, JetBrains IDEs, terminals, ...) and writes
that as a `kind: "kpi-value"` payload into your mailbox, reusing `pair.py`'s existing
encryption/upload machinery through a narrow wrapper — it never touches your Drive refresh token
directly. This is a HAND-WRITTEN reference capability (the generation pipeline that would produce
these automatically, M7/M8, isn't built yet) — see `docs/designs/kpi-tracking-agent.md`.

Windows-only (it calls `GetForegroundWindow`/`GetWindowTextW` via `ctypes`; no special OS
permissions are required to read the focused window's title on Windows).

### Files

- `mailbox_writer.py` — the scoped write seam. Exposes exactly one function capabilities may
  call, `write_kpi_value(kpi_name, date, value, timeout_seconds=20)`, which internally uses
  `pair.py`'s config loading, token refresh, encryption, and upload. A capability script that
  only imports this module never sees `pair.py`'s config file or refresh token.
- `coding_hours_capability.py` — polls the focused window on a timer, accumulates coding vs.
  non-coding time (`TimeAccumulator`), and calls `mailbox_writer.write_kpi_value()` at the end of
  the run (and optionally periodically, via `--flush-interval-seconds`).
- `coding_hours_config.json` — small user-editable JSON: the list of window-title substrings
  (case-insensitive) that count as "coding". Edit this list directly, no restart of anything else
  needed.

### Setup

You must already have paired this device (`python pair.py pair ...` above) before running the
capability — it reuses that same local config/token.

```powershell
cd tools\companion
venv\Scripts\activate   # if not already active
```

Edit `coding_hours_config.json` to match what you actually run (add/remove IDE or terminal names).

### Running once, manually

```powershell
python coding_hours_capability.py --kpi-name "Coding Hours" --run-seconds 28800
```

Tracks for 8 hours (28800s), then writes the accumulated coding hours once and exits. Omit
`--run-seconds` to run until killed — useful when you want a Task Scheduler job to both start it
in the morning and stop it at a fixed end-of-day time (Task Scheduler's own "stop the task if it
runs longer than..." option, or a second scheduled task that just kills the process). Add
`--flush-interval-seconds 7200` to also write a running total every 2 hours, so a crash mid-day
doesn't lose the whole day's tracking.

### Running via Windows Task Scheduler (recommended)

This was **not** registered in this environment (no real Windows machine here) — set it up by
hand once:

1. Open Task Scheduler → **Create Task** (not "Basic Task", so you get the Conditions/Settings
   tabs).
2. **Triggers**: "At log on" (or a fixed daily time, e.g. 8:00 AM).
3. **Actions**: Start a program —
   - Program: the `python.exe` inside `tools\companion\venv\Scripts\`
   - Arguments: `coding_hours_capability.py --kpi-name "Coding Hours" --run-seconds 43200 --flush-interval-seconds 7200`
   - Start in: the `tools\companion` directory (so the default `coding_hours_config.json` path
     resolves).
4. **Settings** tab: uncheck "Stop the task if it runs longer than..." only if you intend
   `--run-seconds` to be the sole stop condition; otherwise set it to match how long you want
   tracking to run for that day (e.g. 12 hours) as a belt-and-suspenders cap.
5. Test with **Run** from Task Scheduler once, then check `coding_hours_config.json`'s directory
   for no errors and confirm a `kind: "kpi-value"` file briefly appears in your Drive mailbox
   folder before `MailboxConsumeService` consumes it.

The KPI named in `--kpi-name` must already exist in the web app before this runs (same caveat as
`write-test-kpi` above) — an unknown KPI name means the write will be left for retry forever, per
`MailboxConsumeService`'s permanently-failing-request behavior.

### Automated tests

```powershell
python -m unittest test_mailbox_writer test_coding_hours_capability -v
```

Everything here runs on any platform, including this Linux dev box — no real Windows APIs, no
real Drive credentials involved:
- `test_mailbox_writer.py`: the write wrapper's timeout enforcement (a hung write raises
  `TimeoutError`), the not-paired error path, and a **structural** check that
  `coding_hours_capability.py` never imports `pair.py` or references its config/token internals
  — only `mailbox_writer.write_kpi_value`.
- `test_coding_hours_capability.py`: window-title substring matching, `TimeAccumulator`'s
  seconds/hours math including idle-gap handling, and `run_tracking_loop`'s poll/flush/stop
  control flow — all driven through injected fake clocks and fake window-title streams. The one
  test that calls the real `GetForegroundWindow` API is skipped automatically on non-Windows via
  `unittest.skipUnless(sys.platform == "win32", ...)`.

**Not covered here, and cannot be without a real Windows machine**: whether
`GetForegroundWindow`/`GetWindowTextW` actually behave as expected against real window titles,
and a real multi-day run's values "looking right" (per the design doc's own M9 test list, this
last one is explicitly manual-only).

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
- **Scoped-secret-injection boundary (M9)**: the design doc's Windows section describes a wrapper
  that "injects only the declared secret(s) into the subprocess env", which reads as a
  subprocess-based boundary. The M9 task description instead asks for "a small wrapper module
  [that exposes] a narrow 'write one mailbox value' function that the capability calls" — a
  same-process function-call boundary, which is what `mailbox_writer.py` implements (and what its
  own docstring's "Risk-posture note" is explicit about the limits of: it's an import-level
  discipline, not real process isolation — nothing stops a capability from `import pair` directly
  if it chose to). Went with the function-call form since it's what M9 specifies; flagging the gap
  from the design doc's subprocess-env-injection description in case a stronger boundary
  (capability runs as a genuinely separate OS process with only a short-lived scoped token passed
  via env var, wrapper code never in the same address space) is wanted before this pattern is
  reused for generated (M8) capabilities.
- **Periodic mid-day flush (`--flush-interval-seconds`)**: not specified by the design doc or the
  M9 task text, which only describe writing "at a configured interval (or end of day)". Added
  because a single write-at-process-exit design loses the whole day's tracked time if the process
  is killed or the machine reboots before then. Repeated writes for the same KPI/date currently
  rely on whatever `KPIService.addKPIDataForUser()`'s upsert-vs-append semantics are for an
  already-present `(kpi, date)` value — not verified against the Java service in this milestone
  (Python-only scope), so confirm that a second write for the same day overwrites rather than
  duplicates before relying on `--flush-interval-seconds` in production.

# Offline-sync client Flows

Files: `db.js`, `crypto.js`, `connectivity.js`, `driveClient.js`, `outbox.js` — plus `../registerSW.js` and `../../sw.js` (installable shell). Server side: `habitTracker/sync/FLOWS.md`.

## The seam: three ways a write can land

`Outbox.submitHabitComplete(habitId, completed, date)` / `Outbox.submitKpiValue(kpiName, date, value)`
are the only entry points a page should call for the two offline-capable writes:

```
submit(intent):
  Connectivity.isServerReachable() (real fetch to GET /api/ping, short timeout — navigator.onLine
  is only a hint, never trusted alone)
    → true  → sendDirect(intent) — the SAME endpoints the app always used
              (/habits/update/{id}, /api/kpis/{name}/data) — cut the intermediary entirely
    → false → DriveClient.refreshBridge() (best-effort) → DriveClient.isAvailable()?
              → true  → DriveClient.pushBatch([intent]) — encrypted straight into the
                         user's own Drive "_mailbox_requests/" folder
              → false → OfflineDB.enqueue(intent) — pure local IndexedDB queue, no network tried
```

Every intent carries a client-generated `requestId` (uuid) — the server-side idempotency ledger
(`consumed_sync_requests`) uses this to make a replay a no-op.

## flush() — draining the local queue

Called on `window.load`, `online`, tab refocus (`visibilitychange`), and a 5-minute interval
(`outbox.js` bottom). Same branch as `submit()`, but batches everything still queued into a
**single** Drive push (`DriveClient.pushBatch(queued)`) rather than one file per intent — cheaper
and matches the server's per-file batch format (`MailboxConsumeService.MailboxBatch`).

## The Drive bridge — why the browser never holds a refresh token

`DriveClient.refreshBridge()` calls `GET /api/sync/status` and caches whatever comes back
(`driveAccessToken`, `driveAccessTokenExpiresAt`, `mailboxFolderId`, `encryptionKey`) into
IndexedDB `meta`. This is a short-lived, auto-expiring credential the server mints on every
successful contact — never the durable refresh token or the OAuth client secret (those stay
server-side, see `sync/FLOWS.md`). `DriveClient.isAvailable()` just checks the cached expiry
with a 60s safety margin.

Practical consequence: direct-to-Drive pushes only work for roughly an hour after the last time
the app successfully reached the server. Beyond that with no server contact, writes fall to the
local-only queue — correct and safe, just delayed until the next successful contact (server OR
a fresh bridge).

## Crypto

`crypto.js` (`OfflineCrypto.encryptJson`) is the WebCrypto mirror of
`habitTracker.sync.VaultEncryptionService`: AES-256-GCM, `[12B IV][ciphertext+tag]`, no KDF (the
key is already a real random 256-bit key, base64, handed down via the bridge). Any change to the
Java side's wire format must be mirrored here exactly, or decryption breaks silently.

## Install shell (`../registerSW.js`, `../../sw.js`)

`registerSW.js` registers `/sw.js` and requests persistent storage — only runs in a secure
context (`window.isSecureContext`), so it silently no-ops over the self-signed dev cert; first
install must happen over the real Cloudflare-tunnel domain.

`sw.js`: precaches the shell (env.js/topbar.js/css/the handful of top-level pages —
`SHELL_URLS`); navigations are network-first with a 4s timeout, falling back to the cached shell
page on failure/timeout/non-ok response; `GET`s to `/api/today`, `/api/habits*`, `/api/kpis*`
are stale-while-revalidate, which is what makes "what's due today" / KPI values visible offline
as last-known-state — no separate IndexedDB read-cache needed for this.

## IndexedDB schema (`db.js`)

```
habittracker-offline (v1)
  outbox  — keyPath 'requestId'   — queued intents not yet sent anywhere
  meta    — keyPath 'key'         — { deviceId, driveBridge } (see driveClient.js)
```

## Technology Notes

- **`navigator.onLine` is a hint, not truth** — `Connectivity.isServerReachable()` never trusts
  it alone; it always does a real timed fetch against the cheap, unauthenticated `/api/ping`.
- **Cache Storage / IndexedDB are sandboxed + evictable** — same caveat as any PWA; under
  storage pressure the browser can evict cached shell/API responses. `registerSW.js` requests
  `navigator.storage.persist()` but the browser may still refuse.
- **Hand-written service worker, not Workbox** — this app has no build step/bundler, so
  `vite-plugin-pwa`-style `injectManifest` isn't available; `SHELL_URLS` is a manually
  maintained list. Bump `sw.js`'s `VERSION` whenever that list or the routing logic changes.
- **Auto-update, no reinstall ever needed**: `sw.js` calls `skipWaiting()`+`clients.claim()`
  unconditionally, so a version bump takes over as soon as the browser notices the file changed.
  `registerSW.js` forces that check on every page load and tab-refocus (`registration.update()`)
  rather than waiting on the browser's own throttled (~24h) background check, and shows a
  "Reload" banner once the new worker has actually installed over an existing controller. The
  banner click is the only user action ever required — there is no "uninstall/reinstall the PWA"
  step for a code update, that's only for install-shell changes (icon, name, manifest fields).
- **Write failure semantics changed**: pages that used to revert a checkbox/UI state on a
  non-200 response no longer do, since `Outbox.submit()` always "succeeds" from the caller's
  perspective (worst case: locally queued) — only a *thrown exception* (an actual bug, not a
  network condition) triggers a UI revert now. See `index.html` `toggleCard()`/`shameRemove()`,
  `habit-table.html` `updateHabitStatus()`.
- **No offline auth verification**: the Spring session cookie either works or a direct write
  gets a 401 (falls through to Drive/queue same as any other failure) — there's no persisted
  "was I logged in" flag the way a fuller offline-auth design would have; a session that expired
  while offline just means direct sends keep failing until the user is back online and re-logs in.

## Change Index

| What to change | Where |
|---|---|
| Which endpoints an intent replays to | `outbox.js` `sendDirect()` |
| Reachability probe / timeout | `connectivity.js` `isServerReachable()` (2.5s) |
| Flush triggers / cadence | `outbox.js` bottom (`load`/`online`/`visibilitychange`/5-min interval) |
| Bridge token expiry safety margin | `driveClient.js` `EXPIRY_SAFETY_MARGIN_MS` (60s) |
| Drive mailbox filename convention | `driveClient.js` `pushBatch()` |
| Crypto params (must match Java) | `crypto.js` ⇄ `habitTracker.sync.VaultEncryptionService` |
| IndexedDB schema | `db.js` `open()` |
| Service worker shell precache list | `../../sw.js` `SHELL_URLS` (+ bump `VERSION`) |
| API stale-while-revalidate routes | `../../sw.js` `API_PREFIXES` |
| SW update detection | `../registerSW.js` |

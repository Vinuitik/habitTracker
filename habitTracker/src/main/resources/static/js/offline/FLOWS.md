# Offline-sync client Flows

Files: `store.js`, `db.js`, `crypto.js`, `connectivity.js`, `driveClient.js`, `outbox.js` — plus `../registerSW.js` and `../../sw.js` (installable shell). Server side: `habitTracker/sync/FLOWS.md`.

## The seam: how a write lands

`Outbox.submitHabitComplete(habitId, completed, date)` / `Outbox.submitKpiValue(kpiName, date, value)`
are the only entry points a page should call for the two offline-capable writes. They differ:

- **Habit completion is local-first** (next section): enqueue to IndexedDB → return → `flush()` syncs.
- **KPI values still use the old inline path** `submit(intent)`:

```
submit(intent):
  Connectivity.isServerReachable() (real fetch to GET /api/ping, 2.5s timeout)
    → true  → sendDirect(intent) — /api/kpis/{name}/data
    → false → DriveClient.refreshBridge() → DriveClient.isAvailable()?
              → true  → DriveClient.pushBatch([intent]) — encrypted into the user's Drive "_mailbox_requests/"
              → false → OfflineDB.enqueue(intent) — local queue only
```

Every intent carries a client-generated `requestId` (uuid) — the server-side idempotency ledger
(`consumed_sync_requests`) makes a replay a no-op.

## Local-first Today (v9) — IndexedDB is the single source of truth for habit completion

```
render:  Store.getToday() = meta 'todaySnapshot' (last /api/today) + unsent habit-complete intents overlaid
tap:     index.html toggleCard() → UI updates instantly → Outbox.submitHabitComplete()
           → OfflineDB.enqueue(intent) → return → flush() in background (never on the tap path)
flush:   Outbox.flushOnce(): sendDirect() each queued intent (5s timeout, no ping)
           → on failure: Drive pushBatch, intents kept with driveSentAt (overlay only)
           → all sent: Store.refresh() pulls /api/today → snapshot → Store.onChange → re-render if DOM differs
```
- `/api/today` is **no longer** in `sw.js` `API_PREFIXES` — the Store replaces that cache.
- Drive-pushed intents stay as overlay for `Store.DRIVE_OVERLAY_TTL_MS` (20 min; server drains Drive every 15 min), else a pull would revert them. After TTL they are dropped by `flushOnce()`.
- Snapshot is whatever date the device last pulled; offline past midnight shows yesterday's list until a pull succeeds.
- Streaks are cached in meta `streaksSnapshot` (rendered before the streak POST returns).
- KPI values still use the old `submit()` path (ping → server → Drive → queue) [NOT MIGRATED].
- `habit-table.html` calls `submitHabitComplete()` (so its write is local-first) but still renders from its own fetch, not `Store` [NOT MIGRATED].

## flush() — the sync loop

`Outbox.flush()` is single-flight (a call during a running flush sets `again` and re-runs after).
Triggers: `window.load`, `online`, tab refocus (`visibilitychange`), a 5-minute interval
(`outbox.js` bottom), and right after every habit tap. `flushOnce()`:
drop expired Drive-overlay intents → `sendDirect()` each unsent intent in order (5s timeout, no
ping; first failure stops the loop) → leftovers go to Drive as ONE batch (`pushBatch`) and are
kept with `driveSentAt` → if everything reached the server, `Store.refresh()` pulls `/api/today`.

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

`sw.js`: precaches the shell (env.js/topbar.js/css/offline scripts — `SHELL_URLS`) and the navigable
pages (`PAGE_ROUTES`); navigations are network-first with a 4s timeout, falling back to the cached
shell page on failure/timeout/non-ok response; `GET`s to `/api/habits*` and `/api/kpis*` are
stale-while-revalidate (last-known state offline). **`/api/today` is NOT in that list any more** —
the Today page reads it through `Store` (IndexedDB), not the SW cache.

## "Server down" is an HTTP 530, not a dropped connection (the seam that used to break)

Behind the Cloudflare tunnel, "origin down" means the edge ANSWERS with **HTTP 530** — the
`fetch()` RESOLVES with a non-ok Response, it does NOT throw, and `navigator.onLine` stays `true`
(Wi-Fi is fine, only the origin is dead). Both signals apps naïvely trust to detect "offline"
lie here. Three sites treat a non-ok/5xx/530 exactly like a thrown fetch = "unreachable":

- **`topbar.js checkAuth()`** — redirects to `/login` **only** on a real `401/403`. Any other
  non-ok status (530/5xx) keeps the session and renders from cache. The old `if (res.ok) … else
  redirect` sent every page to `/login` the instant the origin was down — and the SW never serves
  `/login` from cache (auth-handshake path), so it landed on a raw blank error page. **This was
  the "server down breaks the app" bug.**
- **`sw.js handleApiGet()`** — only an OK network response is returned/cached; a non-ok (530 with
  a Cloudflare HTML body) becomes `null` so the cached copy — or the friendly `{offline:true}`
  JSON — wins, instead of leaking a 530 whose HTML body breaks the caller's `.json()`.
- **`sw.js handleNavigate()`** — already network-first with a 4s timeout, falling back to the
  cached shell on throw, timeout, OR non-ok Response.
- **Page reads** (`index.html init()`) paint from `Store.getToday()` first; the "You're offline"
  state shows only when there is no local snapshot AND the pull failed.

## IndexedDB schema (`db.js`)

```
habittracker-offline (v1)
  outbox  — keyPath 'requestId'   — queued intents not yet sent anywhere
  meta    — keyPath 'key'         — { deviceId, driveBridge (driveClient.js),
                                      todaySnapshot, streaksSnapshot (store.js / index.html) }
```

## Technology Notes

- **`navigator.onLine` is a hint, not truth** — `Connectivity.isServerReachable()` never trusts
  it alone; it always does a real timed fetch against the cheap, unauthenticated `/api/ping`.
- **Cache Storage / IndexedDB are sandboxed + evictable** — same caveat as any PWA; under
  storage pressure the browser can evict cached shell/API responses. `registerSW.js` requests
  `navigator.storage.persist()` but the browser may still refuse.
- **Hand-written service worker, not Workbox** — this app has no build step/bundler, so
  `vite-plugin-pwa`-style `injectManifest` isn't available; `SHELL_URLS` is a manually
  maintained list. `VERSION` is stamped per deploy by `scripts/deploy.sh`, so the new list ships with the next push.
  **This list is the actual offline failure mode in practice**: it's easy to add a new page or
  a new shared script (a new offline/*.js module, a new nav page) and forget to add it here —
  the asset then 530s uncached with no fallback the next time the origin is down, instead of
  failing loudly at dev time. Confirmed in production (2026-08-15): `SHELL_URLS` had every page
  built after `v3` but was missing all six offline-pipeline scripts (`db.js`, `crypto.js`,
  `connectivity.js`, `driveClient.js`, `outbox.js`, `registerSW.js`) plus `rule-setting.html` and
  the manifest icons — so on an origin-down test, `Outbox` never loaded, and any write attempt
  threw instead of queuing. Fixed in `v5`. When adding a `<script src>`/`<link href>` that's
  loaded on any shell page, add it to `SHELL_URLS` in the same commit.
  **Second bug found alongside it (same date, `v6`)**: `SHELL_URLS` cached top-level pages under
  their static filename (`/habit-table.html`) but `PageController` only ever forwards to that
  filename server-side — the browser's navigation request is always the route
  (`/habits/table`). `handleNavigate()`'s `cache.match(request)` therefore never matched any page
  but the one aliased at the final fallback, so switching pages while offline always silently
  landed back on Today regardless of which page you tapped. Fixed by adding `PAGE_ROUTES` — the
  same pages, precached under their actual `@GetMapping` path instead of the filename. When adding
  a new `@GetMapping` in `PageController.java` with no path variable, add its route to
  `PAGE_ROUTES` in `sw.js` in the same commit (routes with `{id}` still fall back to Today, same
  as before — precaching per-id content isn't worth it here).
- **Update flow (click-gated, not automatic)**: `sw.js` does NOT call `skipWaiting()` on install —
  a new worker installs, then WAITS. Only `window.applyUpdate()` (`registerSW.js`, from the banner
  Reload button or the topbar `.topbar__update` button) posts `SKIP_WAITING`, then reloads on
  `controllerchange`. Reason: auto-activating once left open tabs dead when a deploy was still
  propagating behind the tunnel (Cloudflare 530). Detection is automatic: `registration.update()`
  runs on every page load and tab refocus, and `notifyIfWaiting()` (gated on an existing controller,
  so a first install isn't an "update") shows the banner and lights `TopbarUpdate.markAvailable()`.
  `window.TopbarUpdate` is set explicitly because a top-level `const` in a classic script doesn't
  attach to `window`. A PWA that is never opened/refocused stays on its old version.
  Idle auto-reload [NOT IMPLEMENTED].
- **`VERSION` is stamped by the deploy script**: `scripts/deploy.sh` rewrites `const VERSION` in
  `sw.js` to the git short SHA for the build only (file restored afterwards), so every deploy makes
  `sw.js` byte-different. The value committed in git (`'v9'`) is a placeholder; cache names
  (`habittracker-shell-<VERSION>`) derive from it. Manual bumps are only needed if you deploy
  without the script. See `FLOWS_infra.md` "Deploy on push".
- **Write failure semantics**: `index.html toggleCard()` updates the UI immediately and calls
  `Outbox.submitHabitComplete()` without waiting; the intent is persisted locally first, so it can
  only throw on an IndexedDB failure — only then does the checkbox revert. A rejected/unreachable
  POST is retried by `flush()`. `shameRemove()` records the id in a session `dismissed` set so a
  refresh doesn't resurrect the card. A permanently-rejecting intent (e.g. habit deleted) is
  retried every flush forever, like the server-side mailbox note in `sync/FLOWS.md`.
- **No offline auth verification**: the Spring session cookie either works or a direct write
  gets a 401 (falls through to Drive/queue same as any other failure) — there's no persisted
  "was I logged in" flag the way a fuller offline-auth design would have; a session that expired
  while offline just means direct sends keep failing until the user is back online and re-logs in.

## Change Index

| What to change | Where |
|---|---|
| Snapshot + overlay merge / pull timeout | `store.js` `getToday()` / `refresh()` |
| Drive overlay TTL | `store.js` `DRIVE_OVERLAY_TTL_MS` |
| Which endpoints an intent replays to | `outbox.js` `sendDirect()` |
| Reachability probe / timeout (KPI `submit()` only) | `connectivity.js` `isServerReachable()` (2.5s) |
| Flush triggers / cadence | `outbox.js` bottom (`load`/`online`/`visibilitychange`/5-min interval) |
| Bridge token expiry safety margin | `driveClient.js` `EXPIRY_SAFETY_MARGIN_MS` (60s) |
| Drive mailbox filename convention | `driveClient.js` `pushBatch()` |
| Crypto params (must match Java) | `crypto.js` ⇄ `habitTracker.sync.VaultEncryptionService` |
| IndexedDB schema | `db.js` `open()` |
| Service worker shell precache list (assets/scripts) | `../../sw.js` `SHELL_URLS` (VERSION auto-stamped by `scripts/deploy.sh`) |
| Service worker page precache list (navigable routes) | `../../sw.js` `PAGE_ROUTES` — must mirror `PageController.java` `@GetMapping`s |
| API stale-while-revalidate routes | `../../sw.js` `API_PREFIXES` |
| Auth 530-vs-401 gate (redirect only on 401/403) | `js/topbar.js` `checkAuth()` |
| API GET non-ok→cache/offline-JSON fallback | `../../sw.js` `handleApiGet()` |
| Offline-state render on a page | `index.html` `init()` (`data.offline`/`!data.date` branch) |
| SW update detection / click-to-activate | `../registerSW.js` `notifyIfWaiting()` / `applyUpdate()`; `../../sw.js` `message` listener |
| Today pull timeout / snapshot keys | `store.js` `PULL_TIMEOUT_MS` / `SNAPSHOT_KEY` |
| Direct-send timeout | `outbox.js` `SEND_TIMEOUT_MS` |
| Persistent update-available topbar button | `js/topbar.js` `TopbarUpdate`/`initTopbar()`, styled in `styles/organisms/topbar.css` `.topbar__update` |

# Offline Drive-Sync Flows

Files: `UserSyncSettings.java`, `UserSyncSettingsRepository.java`, `ConsumedSyncRequest.java`, `ConsumedSyncRequestRepository.java`, `VaultEncryptionService.java`, `DriveService.java`, `DriveOAuthService.java`, `MailboxConsumeService.java`, `SyncController.java`

## Why this exists

The app runs on a personal PC that isn't always on. A user's phone (installed as a PWA — see
`static/js/offline/FLOWS.md`) needs to keep working — mark a habit done, log a KPI value — even
when this server is asleep. Each user connects their **own** Google Drive; their offline writes
queue through their own Drive as small encrypted JSON files, and this package drains that queue
whenever the server is next up.

## Connect flow (per-user, incremental OAuth)

```
static/connect-drive.html "Connect Google Drive"
  → GET /api/sync/oauth/url?origin=<window.origin>          (SyncController.oauthUrl)
      DriveOAuthService.buildConsentUrl(): scope=drive.file, access_type=offline&prompt=consent
      (forces a refresh token every time) + a single-use state nonce (10-min TTL, in-memory)
  → browser → Google consent (the user's OWN account, not the app owner's)
  → GET /api/sync/oauth/callback?code&state                 (SyncController.oauthCallback)
      DriveOAuthService.handleCallback():
        state check → DriveService.exchangeCode() → refresh token
        findOrCreateFolder("HabitTrackerSync") → findOrCreateFolder("_mailbox_requests", root)
        first connect → VaultEncryptionService.generateKey() (random 256-bit AES key, no KDF)
        → UserSyncSettingsRepository.save() keyed by userId (SecurityUtils.getCurrentUserId())
  → 302 → /connect-drive.html?drive=connected
Disconnect: POST /api/sync/disconnect → deletes this user's UserSyncSettings row.
```

- **OAuth client is shared with the mongo-backup service** (`GOOGLE_OAUTH_CLIENT_ID/SECRET`,
  `google.oauth.client-id/client-secret` properties) — same Google Cloud client, but each
  habitTracker user's consent produces their OWN refresh token, stored per-user, never mixed
  with the backup service's own admin-owned refresh token.
- To change scope/redirect handling: `DriveOAuthService` constants.
- To change the Drive root/mailbox folder names: `ROOT_FOLDER_NAME` / `MAILBOX_FOLDER_NAME` in
  `DriveOAuthService`.

## The bridge token — how the browser talks to Drive without the server

`GET /api/sync/status` (polled by `driveClient.js` on every page load/reconnect while online)
does more than report connected/not-connected: when connected, it also **mints a fresh
short-lived Drive access token** (`DriveService.getAccessTokenWithExpiry`) and returns it,
along with `mailboxFolderId` and `encryptionKey`. This is the **only** Drive credential the
browser ever holds — never the durable refresh token or the OAuth client secret, both of which
stay in `UserSyncSettings` server-side.

Consequence: the browser can push directly to the user's Drive mailbox for as long as this
cached token stays valid (~1h from mint time) — a bridge over the exact window where "server
just went down, but I still have internet" happens. If the bridge has expired and the server
truly hasn't been reachable in a while, the write falls back to a local-only queue instead
(`static/js/offline/outbox.js`) — safe, just delayed, not lost. This sidesteps entirely the
open question of whether Google's raw `refresh_token` grant works cross-origin from a browser
— there is no token-endpoint call from the browser, ever.

To change the bridge lifetime: `expiresInSeconds` from Google is trusted as-is (`SyncController.status()`
falls back to a 1h assumption if Google omits it). To change what's hand down: `SyncController.status()`.

## Mailbox consume (server drains each user's own Drive)

```
MailboxConsumeService.consumeAll() — @PostConstruct (boot) + @Scheduled (every 15 min):
  for each UserSyncSettings row (i.e. every user who has connected Drive):
    DriveService.getAccessToken(refreshToken) → this user's own access token
    DriveService.listFiles(mailboxFolderId) → this user's own "_mailbox_requests/*"
    for each file:
      downloadFile → VaultEncryptionService.decrypt(their own key) → MailboxBatch{deviceId, requests[]}
      per request (SyncRequest{requestId, kind, ts, payload}):
        ConsumedSyncRequestRepository.existsById(requestId) → skip if already applied
        dispatch by kind:
          "habit-complete" → StructureService.updateHabitCompletionForUser(userId, habitId, completed, date)
          "kpi-value"      → KPIService.addKPIDataForUser(userId, kpiName, date, value)
        on success → ConsumedSyncRequestRepository.save() (idempotency ledger)
      ALL requests in the file committed → DriveService.deleteFile()   — else left for next pass
```

**Delete-after-success is the correctness hinge**: a mailbox file is removed only once every
request inside it committed. `requestId` is client-generated (uuid), so a re-consumed file
(partial failure, retried pass) is a safe no-op for anything already applied.

**Why `...ForUser` methods exist**: this runs on a background scheduled thread with no HTTP
`SecurityContext`. `StructureService.updateHabitCompletion()` / `KPIService.addKPIData()` both
call `SecurityUtils.getCurrentUserId()` internally for ownership checks and to stamp `userId` on
new records — on a background thread that returns `null`, which would silently corrupt data
(every replayed habit-completion would fail its ownership check, or KPI upserts would 404). The
`...ForUser(userId, ...)` overloads take `userId` explicitly instead, mirroring the same pattern
`KPIService.fillDefaultIfMissing()` already used for its own cron entry point. See
`StructureService.updateHabitCompletion()` / `HabitService.getHabitByIdForUser()` /
`KPIService.addKPIData()` for the public/explicit-userId pairs.

**Why these two write kinds specifically**: both are pure upserts keyed by `(id, date)` —
replaying the same request twice, or in any order, converges to the same state. Streak is
derived later by the daily cron from stored `HabitStructure` records, never mutated here. See
`Structure/FLOWS.md` (if present) / `StructureService.updateHabitCompletionForUser()` for the
one date-gated side effect (`restoreNegativeStreak`, only fires for `date == today`).

To add a new offline-capable write kind: extend `MailboxConsumeService.applyRequest()`'s switch
+ add the matching case in `static/js/offline/outbox.js`'s `sendDirect()`.

## Encryption

`VaultEncryptionService`: AES-256-GCM, wire format `[12B IV][ciphertext + 16B GCM tag]`. Key is
a **real random 256-bit key** generated once per user at connect time (`generateKey()`) — no
PBKDF2/passphrase, since there's no human-memorized secret to derive from (unlike the
sister-project reference this design was benchmarked against, which uses a human passphrase).
To rotate a user's key: clearing `UserSyncSettings.encryptionKey` and forcing a reconnect would
orphan any already-queued Drive files encrypted under the old key — not implemented; would need
a migration path if this becomes necessary.

## Endpoints (`SyncController`, `/api/sync/**`, session-authed)

| Method | Path | Description |
|---|---|---|
| `GET` | `/oauth/url?origin=` | Google consent URL for this user |
| `GET` | `/oauth/callback?code&state` | Code exchange, persists this user's `UserSyncSettings`, 302 redirect |
| `POST` | `/disconnect` | Deletes this user's `UserSyncSettings` row |
| `GET` | `/status` | `{connected, driveAccessToken, driveAccessTokenExpiresAt, mailboxFolderId, encryptionKey}` — mints a fresh bridge token on every call |

`GET /api/ping` (`habitTracker.PingController`, top-level, unauthenticated) is the cheap
reachability probe `static/js/offline/connectivity.js` uses to distinguish "server down" from
"session expired."

## Technology Notes

- **Hand-rolled Drive v3 REST client (`DriveService`), not `google-api-client`**: `javaapp` is
  memory-capped (`mem_limit: 384m`, `-Xmx256m` — see `docker-compose.yml`); the official Google
  API client library's dependency footprint wasn't worth it for ~6 REST calls. Upload uses a
  two-step metadata-POST + media-PATCH instead of hand-rolling `multipart/related`, which is
  fiddly to get right with a bare `RestTemplate`.
- **In-memory OAuth state nonce** (`DriveOAuthService.pendingStates`): single-instance,
  cleared on restart. A backend restart mid-consent just means the user clicks Connect again —
  acceptable for a single-`javaapp`-container deployment (no clustering).
- **`GOOGLE_OAUTH_CLIENT_ID/SECRET` in "Testing" publishing status** (Google Cloud Console) means
  only Google accounts added as test users can complete this consent flow at all — every
  habitTracker user who wants to connect their own Drive needs to be added as a test user (or
  the OAuth client needs to be published). See `.env.example` for the full note.
- **No Drive-side cleanup of orphaned mailbox files**: unlike a vault-sync design with a janitor
  sweep, this mailbox is small and self-cleaning (delete-on-success) — no janitor exists or is
  needed at this scale.
- **A permanently-failing request loops silently**: if `applyRequest()` throws for the same
  `requestId` every pass (e.g. the habit was deleted between queueing and replay), that mailbox
  file is never deleted and is retried forever, every 15 minutes, indefinitely. Fine at
  personal-project scale; would need a retry-cap/dead-letter if it ever becomes noisy.
- **Refresh tokens can die**: same risk as the backup service's — Google expires "Testing"-status
  refresh tokens after ~7 days. `SyncController.status()` treats a failed refresh as
  `connected: false` with `error: "reconnect_required"` rather than throwing, so the UI can
  prompt a reconnect instead of every mailbox consume pass failing silently in the logs.

## Change Index

| What to change | Where |
|---|---|
| Drive root / mailbox folder names | `DriveOAuthService.ROOT_FOLDER_NAME` / `MAILBOX_FOLDER_NAME` |
| OAuth scope / consent params | `DriveOAuthService.buildConsentUrl()` |
| OAuth state nonce TTL | `DriveOAuthService.STATE_TTL_MS` |
| Consume schedule | `MailboxConsumeService` `@Scheduled(cron=...)` (currently every 15 min, hardcoded — matches `updater.UpdateScheduler`'s convention) |
| Add a new offline-capable write kind | `MailboxConsumeService.applyRequest()` switch + `static/js/offline/outbox.js` `sendDirect()` |
| Idempotency ledger | `ConsumedSyncRequestRepository` (`consumed_sync_requests`, `requestId` as `@Id`) |
| Encryption key generation / format | `VaultEncryptionService.generateKey()` / `encrypt()` / `decrypt()` |
| Bridge token lifetime + payload | `SyncController.status()` |
| Drive REST calls | `DriveService` (list/upload/download/delete/find-or-create-folder) |
| Credential source | `application.properties` `google.oauth.client-id/client-secret` ← `GOOGLE_OAUTH_CLIENT_ID/SECRET` env |

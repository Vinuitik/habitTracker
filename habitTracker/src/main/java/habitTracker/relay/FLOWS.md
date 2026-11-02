# Relay (web) — second factor + runner hub

Files: ClaimService.java, ClaimController.java, Device.java, DeviceRepository.java, DeviceService.java, DeviceController.java, UplinkConfig.java, UplinkHandler.java, UplinkRegistry.java, RelayProxyController.java, RunnerClient.java, RunnerJobService.java, RunnerUnavailableException.java, RunnerCallException.java, TotpService.java, TwoFactorService.java, TwoFactorController.java, TwoFactorInterceptor.java, TrustedDevice.java, TrustedDeviceService.java, RelayController.java, ../WebConfig.java, ../auth/User.java, templates/twofa-setup.html, templates/twofa-verify.html, static/js/twofa.js, static/css/auth.css, templates/relay.html, static/js/relay/relay.js, static/styles/pages/relay.css, static/js/topbar.js (nav), static/js/env.js (ROUTES.RELAY)

## Gate
GET /relay/** → Spring Security (logged in? else /login) → TwoFactorInterceptor.preHandle() → TwoFactorService.isVerified(session)
- verified (session `relay2fa.user` == current user id AND `relay2fa.until` > now) → handler
- else valid `relay_trust` cookie for the current, still-enrolled user → TrustedDeviceService.accepts() → TwoFactorService.markVerified() → req.changeSessionId() → handler (see Trusted devices). Bad cookie → cleared, fall through
- not enrolled → 302 /2fa/setup?next=<uri>; enrolled → 302 /2fa/verify?next=<uri>
- /relay/api/** unverified → 401 `{"error":"two-factor verification required"}`; not logged in → 401 (SecurityConfig entry point for /relay/api/**; everything else still → /login)
- NOT gated: /relay/pair, /relay/uplink (WebConfig excludePathPatterns; SecurityConfig permitAll) — runner credentials, no browser session

To change which paths are gated: WebConfig.addInterceptors(). To change how long one check lasts: `RELAY_2FA_TTL_HOURS` (default 12).

## Enrol
GET /2fa/setup → TwoFactorService.pendingSecret() (20 random bytes, stored in the session only) → TotpService.otpauthUri() → TotpService.qrSvg() → TwoFactorController.renderSetup() → twofa-setup.html
- page = 2 numbered steps: (1) add to an authenticator app, (2) type the code
- phone/touch (default, mobile-first CSS): "Add to authenticator app" = `<a href="otpauth://…">` → opens the installed app pre-filled; QR hidden
- desktop (`@media (pointer: fine) and (min-width: 481px)`): QR shown ("scan with the authenticator app, not the camera"), app link hidden
- both: setup key grouped in 4s + manual-entry hint (name, key, time-based); "Copy setup key" button (static/js/twofa.js, hidden without JS; copies the key WITHOUT spaces)
POST /2fa/setup code [trust] → TwoFactorService.confirmSetup() → check() → User.totpSecret = base64(VaultEncryptionService.encrypt(serverKey, secret)), totpEnabled=true → TrustedDeviceService.forgetAll(user) → markVerified() → [trust ticked → TrustedDeviceService.trust()] → req.changeSessionId() → 302 safeNext(next)
- wrong code → same pending secret re-rendered (already-scanned QR stays valid)
- already enrolled → NOT_SET_UP → redirect (setup never overwrites an existing secret)
- re-enrolment (after a manual reset) deletes every trusted device of the user

To change phone/desktop split: auth.css `@media (pointer: fine) and (min-width: 481px)`. Wording: twofa-setup.html.

## Verify
POST /2fa/verify code [trust] → TwoFactorService.verify() → decrypt User.totpSecret → check() → save totpLastStep → markVerified() → [trust ticked → TrustedDeviceService.trust()] → changeSessionId() → 302 safeNext(next)

check(): locked? → LOCKED · TotpService.matchingStep() (±1 step, constant-time) · step ≤ User.totpLastStep → replay → failure · 5 failures → locked 15 min

To change limits: TwoFactorService.MAX_FAILURES / LOCK_SECONDS; drift: TotpService.WINDOW.

## Trusted devices ("Trust this device for N days")
Checkbox on twofa-setup.html + twofa-verify.html (hidden when `RELAY_2FA_TRUST_DAYS`=0) → param `trust=true`
TrustedDeviceService.trust() → 32 random bytes (base64url token) → insert `relay_trusted_devices` {userId, tokenHash = sha256 hex, createdAt, expiresAt = now + N days, userAgent (≤200 chars)} → Set-Cookie `relay_trust=<rowId>.<token>`; HttpOnly; Secure; SameSite=Lax; Path=/relay; Max-Age N days
Gate: TwoFactorInterceptor → TrustedDeviceService.accepts(userId) → findById(rowId) → MessageDigest.isEqual(sha256(token), tokenHash) + same userId + expiresAt > now → AND User.totpEnabled → markVerified() (same 12h session flag as a typed code)
- unknown id / tampered token / other user / expired / feature off → cookie cleared (Max-Age=0), normal 2FA redirect
Forget: relay.js showTrusted() (devices page footer, only when count > 0) → GET /relay/api/trusted-devices `{days, count}`; "Forget trusted devices" → DELETE /relay/api/trusted-devices → TrustedDeviceService.forgetAll(user) `{removed}` + clears this browser's cookie. Both behind the gate (a verified session).
Indexes: TrustedDeviceService.ensureIndexes() at startup → tokenHash (unique), userId, expiresAt (TTL, expireAfterSeconds 0)

To change duration / turn off: `RELAY_2FA_TRUST_DAYS` (default 30, 0 = off). Cookie name/path: TrustedDeviceService.COOKIE, COOKIE_PATH.

## Hub (contract: Relay repo shared/HUB.md)
### QR pairing (recommended, phone first)
runner `POST /relay/pair/start {name}` (permitAll, CSRF-ignored, not 2FA) → ClaimController.start() → ClaimService.start() (sweeps expired; ≥ MAX_PENDING (100) → 429) → claimId (20 chars, 100 bits) + pollSecret (32 bytes; only sha256 kept) → `{claimId, pollSecret, expiresInSeconds:600}`
runner shows QR of `/relay/claim/<claimId>` → phone opens it → GET /relay/claim/{id} (gated: login → 2FA → back here) → RelayController.relay() → relay.html → relay.js route() (pathname) → showClaim()
→ GET /relay/api/claims/{id} → `{name}` → "Add <name>?" → Confirm → POST …/approve → ClaimService.approve() → DeviceService.create(currentUser, name) → `{deviceId}` · "Not mine" → POST …/deny → 204
runner `POST /relay/pair/poll {claimId, pollSecret}` → ClaimService.poll() (constant-time secret check) → 202 pending · 200 `{deviceId, token}` once (claim removed) · 404 declined/expired/unknown/wrong secret
To change TTL / pending cap / id length: ClaimService.TTL_SECONDS, MAX_PENDING, ID_LENGTH.

### Code pairing (desktop alternative)
POST /relay/api/devices/pairing-code (gated) → DeviceController.pairingCode() → DeviceService.newPairingCode() → own `new PairingCodeService()` instance (8 chars, single use, 10 min, bound to user) → `{code, expiresInSeconds:600}`
POST /relay/pair `{code, name?}` (permitAll, CSRF-ignored, not 2FA) → DeviceService.pair() → redeem (remove() = single use) → DeviceService.create() → 32 SecureRandom bytes, base64url → save Device{userId, name, tokenHash=sha256 hex, createdAt} → `200 {deviceId, token}`; missing code 400; bad/used/expired 404
To change code TTL/format: sync/PairingCodeService (shared class, separate instance) + DeviceService.CODE_TTL_SECONDS.

### Uplink
runnerd `GET /relay/uplink` + `Authorization: Bearer <token>` → UplinkHandler.beforeHandshake() → DeviceService.findByToken(sha256) → unknown: 401, no upgrade
→ afterConnectionEstablished → UplinkRegistry.register() (new socket replaces old; old closed 4000) → DeviceService.touch() (lastSeenAt)
UplinkRegistry.pingAll() every 30s → WS ping; pong → UplinkHandler.handlePongMessage() → heard() + touch(); nothing heard for 95s → close (half-open peer)
afterConnectionClosed → unregister() (only if still the current socket) → its pending requests fail → 503

### Proxy
ANY /relay/api/devices/{id}/v1/** → RelayProxyController.proxy() → DeviceService.owned(user, id) else 404 → body > 4 MiB → 413 → RunnerClient.exchange() → UplinkRegistry.request() (`req` frame; CompletableFuture in `pending` by uuid) → no socket: 503 `{"error":"device offline"}` → wait `relay.proxy.timeout-ms` (30000) → 504 → `res` frame (UplinkRegistry.onFrame) → relay status, contentType, body (body > 4 MiB → 502)

### Runner client (server-initiated calls)
Hub feature (e.g. Project/ProjectRunnerService) → RunnerJobService.startJob/pollJob/cancelJob/send(userId, deviceId, …) → RunnerClient.call(userId, deviceId, method, path, body) → DeviceService.owned() else RunnerUnavailableException(DEVICE_NOT_FOUND) → RunnerClient.exchange() → UplinkRegistry.request() → no socket / socket closed → RunnerUnavailableException(OFFLINE) → wait `relay.proxy.timeout-ms` → RunnerUnavailableException(TIMEOUT) (+ UplinkRegistry.cancel) → `res` frame → UplinkRegistry.Response
- RunnerJobService: non-2xx → RunnerCallException(status, raw body); 2xx → parsed JSON (blank → `{}`).
- Job endpoints (runner contract): `POST /v1/jobs {kind: map|plan|kpi, projectId?, prompt, model?, maxTurns?}` → `{jobId}` · `GET /v1/jobs/{id}?after=<seq>` → `{jobId, kind, state, events[], nextSeq, result?, error?, usage?}` · `DELETE /v1/jobs/{id}`. Job id must match `[A-Za-z0-9_-]{1,128}` (RunnerJobService.checkId) so it can't address another runner path.
- UI mapping: RunnerUnavailableException.code() = `runner_not_found` / `runner_offline` / `runner_timeout`; callers answer with `message: "Connect a runner"` (see Project/ProjectRunnerController.unavailable()).
To change: timeout `relay.proxy.timeout-ms`; error codes RunnerUnavailableException.code().

### Devices
GET /relay/api/devices → `[{id, name, online, lastSeenAt}]` (current user only; online = live socket in UplinkRegistry)
DELETE /relay/api/devices/{id} → owned else 404 → delete document → UplinkRegistry.disconnect() (close 4001) → 204

## UI (browser)
GET /relay (gated, see Gate) → RelayController.relay() → templates/relay.html (empty shell) → js/relay/relay.js → initTopbar(ENV.ROUTES.RELAY) → route() on hashchange
Every call: relay.js call() → Api.raw() (sends cookie `XSRF-TOKEN` as `X-XSRF-TOKEN`) → status mapping: 401 → `/2fa/verify?next=<path+hash>` (safeNext keeps the hash, so you land back on the same chat); 503 → "Device offline (machine asleep?)"; 504 → "Device didn't answer"; fetch threw → "Network error"
To change messages / redirect: relay.js call().

### Devices `#/`
showDevices() → GET /relay/api/devices → rows (dot = `online`, "last seen" = Dom.timeAgo(lastSeenAt)) → row → `#/d/<id>`; empty list → "Install your first runner" CTA → `#/install`
"Add device" → addDevice() → two cards: **Recommended · with your phone** = pairCmd() for this browser's OS (OS from navigator.userAgent: `Windows` → `& "$env:LOCALAPPDATA\Relay\bin\relay-runner.exe" pair`, else `/opt/relay/bin/relay-runner pair`; + `--hub <origin>` unless origin == DEFAULT_HUB), what to do with the QR, link "New machine? Install guide" → `#/install`; **Desktop alternative** → "Get a code" → getCode() → POST /relay/api/devices/pairing-code → code + same command with `--code <CODE>` (OS from `#code-box[data-os]`)
Copy buttons: any `.relay-cmd` → copyCommand() copies its `<code>` (navigator.clipboard; no clipboard API (plain http) → selects the text)

### Install guide `#/install`
Entry: empty device list CTA · Add-device card link · static/install.html "Install a runner" (`/relay#/install`)
showInstall() → OS tabs (guideOs, preselected = OS; `data-act="os"` re-renders) → GUIDE[os] text → steps: open shell → INSTALL_CMD[os] (copy) → scan the QR the script prints (phone → `/relay/claim/<id>` → QR pairing above; Ctrl-C skips) → online under Devices
→ "Already installed" card: Linux `sudo -u <your user> /opt/relay/bin/relay-runner pair` / Windows PAIR_CMD.windows (+ `--hub`), code fallback (codeBox(guideOs) → getCode(), Linux adds the `sudo -u` prefix inside the guide)
→ Troubleshooting `<details>`: QR expired / Ctrl-C → rerun pair · offline → Linux `systemctl status relay-runner@<user>` + `journalctl -u relay-runner@<user> -f`, Windows Task Scheduler "Relay keeper" · pair run as the wrong user · machine asleep
Origin ≠ DEFAULT_HUB → extra hint: Ctrl-C the installer's QR, use the pair command (it carries `--hub`).
Commands are a contract with the Relay repo's release scripts (install.sh / install.ps1, Linux service `relay-runner@<user>`, Windows task "Relay keeper"); the page only displays them.
To change commands: relay.js INSTALL_CMD, PAIR_CMD, LINUX_AS_USER. Guide text per OS: relay.js GUIDE. OS guess: relay.js OS.
"Revoke" → confirm() → DELETE /relay/api/devices/{id} → re-render. New device shows after Refresh (no auto-poll on this view).

### Sessions `#/d/<id>`
showDevice() → in parallel GET `…/v1/projects` (fills the New-session select) + GET `…/v1/sessions` (all projects; runner sorts waiting → busy → rest) → rows: title, project name, timeAgo(lastActiveAt|createdAt), preview, state badge → `#/d/<id>/s/<sid>`
"New session" → POST `…/v1/projects/{projectId}/sessions {provider:"claude"}` → 201 Session → open its chat
To change the provider: relay.js PROVIDER.

### Chat `#/d/<id>/s/<sid>`
showChat() → refresh() → GET `…/v1/sessions/{sid}` → render(): title, state badge, messages (user/agent bubbles via md(); tool = one mono line with toolKind + status; agent `kind` quota/auth → warning bubble) → schedule()
schedule(): busy|waiting → 2s, idle|error → 10s, finished → stop; tab hidden → stop, visible again → refresh() at once
Messages re-render only when JSON of `messages` changed; auto-scroll only if you were already at the bottom.
Composer: Enter → send(), Shift+Enter newline → POST `…/message {text}` → 202 → poll at 2s; 409 → "Still working…" notice (clears when state leaves busy)
state waiting → pendingPermission.options as buttons (allow_* = primary) → POST `…/permission {optionId}` → 200 Session → render
Cancel (busy/waiting) → POST `…/cancel` (ends the turn, keeps session) · Stop → confirm() → POST `…/stop` (kills the agent, session finished; composer disabled)
To change poll rates: relay.js POLL_ACTIVE_MS / POLL_IDLE_MS. Markdown subset: relay.js md()/inline() (``` fences, paragraphs, `code`, **bold**; everything escaped first via Dom.esc).
Not in the UI (API.md has them, proxy would carry them): mode/config switch, agent-chats (open a VS Code chat), subagents, files, git, containers, schedule, tokens/usage, auth sign-in relay, VS Code permissions, handoff [NOT IMPLEMENTED].

## Not yet
- Browser UI beyond devices / sessions / chat (see list above) [NOT IMPLEMENTED]
- Rename device [NOT IMPLEMENTED]
- Reset / disable 2FA (lost phone) [NOT IMPLEMENTED] — today: unset `totpEnabled`/`totpSecret`/`totpLastStep` on the user in Mongo. Trusted-device cookies stop working immediately (gate requires totpEnabled); the rows are deleted on re-enrolment.
- Passkeys / WebAuthn [NOT IMPLEMENTED]
- List / revoke a single trusted browser (only "forget all") [NOT IMPLEMENTED]

## Technology Notes
- **Key:** `KPI_SECRETS_KEY` (base64, 32 bytes), shared with KPI capability secrets via VaultEncryptionService.serverKey(). Unset (true in prod today) → derived from `JWT_SECRET`, so rotating JWT_SECRET makes every stored TOTP secret undecryptable (users must re-enrol). Setting KPI_SECRETS_KEY later has the same effect on existing rows.
- **Why encrypted, not hashed:** the server must recompute codes from the secret. Mongo dumps go to Drive; ciphertext there is useless without the key, which lives only in `.env`.
- **Lockout state is in-memory** (ConcurrentHashMap in TwoFactorService): a restart clears it; single JVM only. `synchronized` verify/confirm also assumes one JVM.
- **Verified flag is a session attribute:** sessions are the default in-memory Tomcat store, so a restart forces password + code again. Bound to the user id, so a reused session for another user doesn't inherit it.
- **Enrolment is trust-on-first-use:** whoever first holds a logged-in session for an account without 2FA can enrol their own authenticator. Enrol right after creating the account.
- **TOTP:** HMAC-SHA1, 6 digits, 30s (authenticator-app defaults). Server clock must be NTP-synced; more than ~30s skew starts rejecting codes.
- **QR:** zxing core renders to inline SVG server-side; no JS, no image endpoint.
- **otpauth:// link (phone):** works only if an installed app registers the scheme (Google Authenticator, Microsoft Authenticator, Aegis do on Android; iOS opens whichever app claimed it last). No app installed → the tap does nothing / shows an OS error; the page's hint + copyable key cover that. Phone vs desktop is a CSS guess (`pointer: fine` + width ≥ 481px): a touch laptop gets the phone layout, a narrow desktop window too — the key + copy are shown in both, so nobody is stuck.
- **Trust cookie theft = up to N (30) days of Relay access without a code.** It does NOT replace the HabitTracker login (Spring Security still needs a session/JWT for the same user), so it only removes the second factor for whoever already has both the cookie and a login. HttpOnly (no JS read), Secure (https only), SameSite=Lax, Path=/relay (not sent to the rest of the app).
- **Secure flag:** on plain http (local dev without https) browsers drop the cookie, so "trust" silently does nothing there. localhost counts as secure in Chrome/Firefox.
- **Token storage:** only sha256 hex (fast hash is fine for 256-bit random tokens); Mongo dumps on Drive can't be turned into cookies. Compare is MessageDigest.isEqual.
- **Expiry & cleanup:** validity is checked on every read (expiresAt > now), so expiry is exact. The TTL index only garbage-collects: Mongo's TTL monitor runs every ~60s, so expired rows linger up to about a minute (longer under load). Index is created by ensureIndexes() because auto-index-creation is off; changing a TTL on an existing index needs collMod or a drop.
- **Revocation:** "Forget trusted devices" deletes all rows of the user → every trusted browser asks for a code on its next unverified request. A browser that already has a verified session keeps it until RELAY_2FA_TTL_HOURS / session end (session flag ≠ cookie). Re-enrolment also deletes all rows; resetting `totpEnabled` disables them at once.
- **Changing RELAY_2FA_TRUST_DAYS** affects new cookies only; existing rows keep their stored expiresAt (except 0, which ignores all of them immediately).
- **Shared browser, two accounts:** a cookie for another user is cleared when that user hits /relay, so switching accounts on one browser drops the other account's trust.
- **Uplink registry is in-memory, single node** (UplinkRegistry maps). A restart drops every uplink: devices show offline until each runner reconnects (backoff up to 60s). Two app instances would each see only their own sockets.
- **Pending proxy requests are in-memory**: lost on restart (the browser sees a dropped connection); a socket closing fails its pending calls with 503.
- **Pings exist for Cloudflare:** the tunnel kills a WebSocket after ~100s idle; 30s pings keep it alive, and the 95s silence check catches half-open runners.
- **Pairing codes are in-memory** (separate PairingCodeService instance from vault sync, so a sync code can never pair a runner). Restart → generate a new code.
- **QR claims are in-memory** (ClaimService map): a restart drops pending claims → runner's poll gets 404 → run pair again. Single JVM only. `/relay/pair/start` is unauthenticated, hence the 100-pending cap (429) instead of unbounded growth.
- **claimId is a bearer link:** whoever opens it while logged in can approve it, binding the device to THEIR account — that's why it's 100 bits and lives 10 min; the runner's name is shown so you can spot a QR you didn't start. The token itself only ever goes to the poller holding pollSecret.
- **Device token:** only sha256 hex stored (a fast hash is fine for a 256-bit random token). Lost hub.json on the runner = re-pair. Mongo dumps contain only hashes.
- **Frame size:** container text-frame limit is 8 MiB + 64 KiB (UplinkConfig.setServletContext), body limit 4 MiB (UplinkRegistry.MAX_BODY_BYTES): headroom for JSON escaping of a 4 MiB body. A bigger frame makes Tomcat close the socket (1009).
- **Indexes:** spring.data.mongodb.auto-index-creation is off, so DeviceService.ensureIndexes() creates tokenHash (unique) + userId indexes at startup.
- **Origin:** /relay/uplink keeps Spring's default same-origin check; requests with no Origin header (runnerd) pass.
- **Blocking proxy:** each in-flight call holds a Tomcat worker thread up to 30s (default pool 200). Fine for one user; switch to DeferredResult if that changes.
- **RunnerClient is the same blocking request/response:** no streaming, no retries. Long work must be a runner job (start → poll), never one call. Job state lives only on the runner: a runner restart loses running jobs (poll then returns the runner's 404 → surfaced as a 404 `runner_error`). A hub restart loses nothing job-wise (the hub stores only the jobId).
- **Ownership is per call:** RunnerClient.call() re-reads the device by (id, userId) every time, so a deleted/revoked device fails immediately with `runner_not_found`, even for a project still linked to it.

- **UI is polling, no live push:** an open chat costs one proxied GET every 2s while busy/waiting (each a full transcript, up to the 4 MiB body limit, through Cloudflare → hub → WebSocket → runner) and every 10s idle; nothing while the tab is hidden or the session finished. Replies appear with up to 2s lag; a long transcript polled at 2s is the main bandwidth cost. Each poll also holds a Tomcat thread for its duration (Blocking proxy, above).
- **UI state is in memory only:** the hash URL (`#/d/<id>/s/<sid>`) is the only thing that survives a refresh. An unsent draft is lost on refresh and on the 401 → /2fa/verify redirect; scroll position is lost too. Device names are cached per page load only.
- **No JS tests:** HabitTracker has no JS test framework; relay.js keeps logic small (router, call(), md(), render()). The MockMvc test only proves the shell renders for a verified user.
- **Service worker:** /relay is not in sw.js PAGE_ROUTES (precaching would follow the 2FA redirect), but a good online visit caches the shell like any navigation; /relay/api is never cached (not in API_PREFIXES). Offline you get the shell plus "Network error".
- **Install commands are hardcoded** (relay.js INSTALL_CMD/PAIR_CMD): GitHub repo `Vinuitik/relay`, `releases/latest`, `/opt/relay/bin`, `%LOCALAPPDATA%\Relay\bin`. Renaming the repo, moving the install dir or dropping a release asset breaks the guide silently; `latest` means the guide always installs the newest runner, no pinning.
- **OS guess is userAgent-only:** anything not matching `Windows` (macOS, ChromeOS, phones) defaults to the Linux tab; macOS has no runner build [NOT IMPLEMENTED].
- **Clipboard:** navigator.clipboard needs a secure context (https / localhost); on plain http the Copy button only selects the command.

## Change Index
| Thing | Where |
|---|---|
| Gated paths / exclusions | WebConfig.addInterceptors() |
| Unauthenticated /relay/api → 401 | SecurityConfig.webFilterChain() exceptionHandling |
| permitAll + CSRF-ignore for runner endpoints | SecurityConfig.webFilterChain() (`/relay/pair`, `/relay/uplink`) |
| Device fields / collection | Device (`relay_devices`) |
| Token size / hash | DeviceService.TOKEN_BYTES, hash() |
| Pairing code TTL reported | DeviceService.CODE_TTL_SECONDS (actual TTL: PairingCodeService) |
| Device name limit | DeviceService.MAX_NAME |
| Uplink auth | UplinkHandler.beforeHandshake() |
| Ping interval / dead timeout | UplinkRegistry.PING_MS, DEAD_AFTER_MS |
| Body / frame limits | UplinkRegistry.MAX_BODY_BYTES, MAX_FRAME_BYTES; UplinkConfig.setServletContext() |
| Proxy timeout | `relay.proxy.timeout-ms` (default 30000) |
| Proxy path mapping / error bodies | RelayProxyController.proxy(), error() |
| Server-initiated runner call (ownership + offline/timeout) | RunnerClient.call(), exchange() |
| Runner job start/poll/cancel, job id check | RunnerJobService.startJob(), pollJob(), cancelJob(), checkId() |
| "Connect a runner" error codes | RunnerUnavailableException.code() |
| Close codes | UplinkRegistry.REPLACED (4000), REVOKED (4001) |
| Verification lifetime | `RELAY_2FA_TTL_HOURS` / `relay.2fa.ttl-hours` |
| Failure limit / lock time | TwoFactorService.MAX_FAILURES, LOCK_SECONDS |
| Clock-drift window | TotpService.WINDOW |
| Encryption key | `KPI_SECRETS_KEY` → VaultEncryptionService.serverKey() |
| Stored fields | User.totpSecret, totpEnabled, totpLastStep |
| Redirect safety | TwoFactorController.safeNext() |
| Error messages | TwoFactorController.message() |
| Page look | templates/twofa-*.html, static/css/auth.css (.auth-hint, .auth-qr, .auth-secret, .twofa-*, .auth-copy, .auth-check) |
| Phone vs desktop on setup page | auth.css `@media (pointer: fine) and (min-width: 481px)` (.twofa-add, .twofa-qr, .twofa-on-touch/-desktop) |
| Authenticator link / key in model | TwoFactorController.renderSetup() (otpauthUri, secret, secretRaw, account) |
| Copy setup key | static/js/twofa.js |
| Trust duration / off switch | `RELAY_2FA_TRUST_DAYS` / `relay.2fa.trust-days` (default 30, 0 = off) |
| Trust cookie name / path / flags | TrustedDeviceService.COOKIE, COOKIE_PATH, setCookie() |
| Trust token size / UA truncation | TrustedDeviceService.TOKEN_BYTES, MAX_USER_AGENT |
| Trusted-device rows / indexes | TrustedDevice (`relay_trusted_devices`), TrustedDeviceService.ensureIndexes() |
| Cookie → verified session | TwoFactorInterceptor.preHandle(), TrustedDeviceService.accepts() |
| Forget trusted devices (API / UI) | TwoFactorController.trustedDevices(), forgetTrustedDevices(); relay.js showTrusted(), forgetTrusted(); relay.css `.relay-foot` |
| Re-enrol forgets trust | TwoFactorService.confirmSetup() |
| Relay page shell | templates/relay.html, RelayController.relay() |
| Relay UI routes (hash) | relay.js route() |
| Hub status → message / 2FA redirect | relay.js call() |
| Pair command text | relay.js addDevice(), getCode(), PAIR_CMD (per OS), pairCmd(), DEFAULT_HUB |
| Installer commands (contract with Relay release scripts) | relay.js INSTALL_CMD |
| Install guide text / troubleshooting per OS | relay.js GUIDE, showInstall() |
| OS preselect (userAgent) | relay.js OS |
| Install guide entry points | relay.js showDevices() (empty CTA), addDevice() (link); static/install.html (card) |
| Install guide look | relay.css `.relay-steps`, `.relay-step`, `.relay-tabs`, `.relay-faq`, `.relay-empty__cta`, `.relay-link` |
| QR claim TTL / cap / id length | ClaimService.TTL_SECONDS, MAX_PENDING, ID_LENGTH |
| QR confirm page | relay.js showClaim(), claimAction(); route RelayController `/relay/claim/{id}` |
| QR runner endpoints (permitAll, CSRF-ignored, 2FA-excluded) | SecurityConfig, WebConfig (`/relay/pair/start`, `/relay/pair/poll`) |
| New-session provider | relay.js PROVIDER |
| Poll rates | relay.js POLL_ACTIVE_MS, POLL_IDLE_MS, schedule() |
| State labels / badge colours | relay.js STATES |
| Markdown subset | relay.js md(), inline() |
| Permission buttons | relay.js render() (pendingPermission) |
| Relay look | static/styles/pages/relay.css |
| Nav entry | static/js/topbar.js NAV_ITEMS (Relay), env.js ROUTES.RELAY |

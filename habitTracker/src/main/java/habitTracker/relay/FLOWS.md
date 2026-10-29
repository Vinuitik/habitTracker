# Relay (web) — second factor + runner hub

Files: Device.java, DeviceRepository.java, DeviceService.java, DeviceController.java, UplinkConfig.java, UplinkHandler.java, UplinkRegistry.java, RelayProxyController.java, TotpService.java, TwoFactorService.java, TwoFactorController.java, TwoFactorInterceptor.java, RelayController.java, ../WebConfig.java, ../auth/User.java, templates/twofa-setup.html, templates/twofa-verify.html, templates/relay.html

## Gate
GET /relay/** → Spring Security (logged in? else /login) → TwoFactorInterceptor.preHandle() → TwoFactorService.isVerified(session)
- verified (session `relay2fa.user` == current user id AND `relay2fa.until` > now) → handler
- not enrolled → 302 /2fa/setup?next=<uri>; enrolled → 302 /2fa/verify?next=<uri>
- /relay/api/** unverified → 401 `{"error":"two-factor verification required"}`; not logged in → 401 (SecurityConfig entry point for /relay/api/**; everything else still → /login)
- NOT gated: /relay/pair, /relay/uplink (WebConfig excludePathPatterns; SecurityConfig permitAll) — runner credentials, no browser session

To change which paths are gated: WebConfig.addInterceptors(). To change how long one check lasts: `RELAY_2FA_TTL_HOURS` (default 12).

## Enrol
GET /2fa/setup → TwoFactorService.pendingSecret() (20 random bytes, stored in the session only) → TotpService.otpauthUri() → TotpService.qrSvg() → twofa-setup.html (QR + base32 key)
POST /2fa/setup code → TwoFactorService.confirmSetup() → check() → User.totpSecret = base64(VaultEncryptionService.encrypt(serverKey, secret)), totpEnabled=true → markVerified() → req.changeSessionId() → 302 safeNext(next)
- wrong code → same pending secret re-rendered (already-scanned QR stays valid)
- already enrolled → NOT_SET_UP → redirect (setup never overwrites an existing secret)

## Verify
POST /2fa/verify code → TwoFactorService.verify() → decrypt User.totpSecret → check() → save totpLastStep → markVerified() → changeSessionId() → 302 safeNext(next)

check(): locked? → LOCKED · TotpService.matchingStep() (±1 step, constant-time) · step ≤ User.totpLastStep → replay → failure · 5 failures → locked 15 min

To change limits: TwoFactorService.MAX_FAILURES / LOCK_SECONDS; drift: TotpService.WINDOW.

## Hub (contract: Relay repo shared/HUB.md)
### Pairing
POST /relay/api/devices/pairing-code (gated) → DeviceController.pairingCode() → DeviceService.newPairingCode() → own `new PairingCodeService()` instance (8 chars, single use, 10 min, bound to user) → `{code, expiresInSeconds:600}`
POST /relay/pair `{code, name?}` (permitAll, CSRF-ignored, not 2FA) → DeviceService.pair() → redeem (remove() = single use) → 32 SecureRandom bytes, base64url → save Device{userId, name, tokenHash=sha256 hex, createdAt} → `200 {deviceId, token}`; missing code 400; bad/used/expired 404
To change code TTL/format: sync/PairingCodeService (shared class, separate instance) + DeviceService.CODE_TTL_SECONDS.

### Uplink
runnerd `GET /relay/uplink` + `Authorization: Bearer <token>` → UplinkHandler.beforeHandshake() → DeviceService.findByToken(sha256) → unknown: 401, no upgrade
→ afterConnectionEstablished → UplinkRegistry.register() (new socket replaces old; old closed 4000) → DeviceService.touch() (lastSeenAt)
UplinkRegistry.pingAll() every 30s → WS ping; pong → UplinkHandler.handlePongMessage() → heard() + touch(); nothing heard for 95s → close (half-open peer)
afterConnectionClosed → unregister() (only if still the current socket) → its pending requests fail → 503

### Proxy
ANY /relay/api/devices/{id}/v1/** → RelayProxyController.proxy() → DeviceService.owned(user, id) else 404 → body > 4 MiB → 413 → UplinkRegistry.request() (`req` frame; CompletableFuture in `pending` by uuid) → no socket: 503 `{"error":"device offline"}` → wait `relay.proxy.timeout-ms` (30000) → 504 → `res` frame (UplinkRegistry.onFrame) → relay status, contentType, body (body > 4 MiB → 502)

### Devices
GET /relay/api/devices → `[{id, name, online, lastSeenAt}]` (current user only; online = live socket in UplinkRegistry)
DELETE /relay/api/devices/{id} → owned else 404 → delete document → UplinkRegistry.disconnect() (close 4001) → 204

## Not yet
- Browser UI for devices / chat (relay.html is still a placeholder) [NOT IMPLEMENTED]
- Rename device [NOT IMPLEMENTED]
- Reset / disable 2FA (lost phone) [NOT IMPLEMENTED] — today: unset `totpEnabled`/`totpSecret`/`totpLastStep` on the user in Mongo.

## Technology Notes
- **Key:** `KPI_SECRETS_KEY` (base64, 32 bytes), shared with KPI capability secrets via VaultEncryptionService.serverKey(). Unset (true in prod today) → derived from `JWT_SECRET`, so rotating JWT_SECRET makes every stored TOTP secret undecryptable (users must re-enrol). Setting KPI_SECRETS_KEY later has the same effect on existing rows.
- **Why encrypted, not hashed:** the server must recompute codes from the secret. Mongo dumps go to Drive; ciphertext there is useless without the key, which lives only in `.env`.
- **Lockout state is in-memory** (ConcurrentHashMap in TwoFactorService): a restart clears it; single JVM only. `synchronized` verify/confirm also assumes one JVM.
- **Verified flag is a session attribute:** sessions are the default in-memory Tomcat store, so a restart forces password + code again. Bound to the user id, so a reused session for another user doesn't inherit it.
- **Enrolment is trust-on-first-use:** whoever first holds a logged-in session for an account without 2FA can enrol their own authenticator. Enrol right after creating the account.
- **TOTP:** HMAC-SHA1, 6 digits, 30s (authenticator-app defaults). Server clock must be NTP-synced; more than ~30s skew starts rejecting codes.
- **QR:** zxing core renders to inline SVG server-side; no JS, no image endpoint.
- **Uplink registry is in-memory, single node** (UplinkRegistry maps). A restart drops every uplink: devices show offline until each runner reconnects (backoff up to 60s). Two app instances would each see only their own sockets.
- **Pending proxy requests are in-memory**: lost on restart (the browser sees a dropped connection); a socket closing fails its pending calls with 503.
- **Pings exist for Cloudflare:** the tunnel kills a WebSocket after ~100s idle; 30s pings keep it alive, and the 95s silence check catches half-open runners.
- **Pairing codes are in-memory** (separate PairingCodeService instance from vault sync, so a sync code can never pair a runner). Restart → generate a new code.
- **Device token:** only sha256 hex stored (a fast hash is fine for a 256-bit random token). Lost hub.json on the runner = re-pair. Mongo dumps contain only hashes.
- **Frame size:** container text-frame limit is 8 MiB + 64 KiB (UplinkConfig.setServletContext), body limit 4 MiB (UplinkRegistry.MAX_BODY_BYTES): headroom for JSON escaping of a 4 MiB body. A bigger frame makes Tomcat close the socket (1009).
- **Indexes:** spring.data.mongodb.auto-index-creation is off, so DeviceService.ensureIndexes() creates tokenHash (unique) + userId indexes at startup.
- **Origin:** /relay/uplink keeps Spring's default same-origin check; requests with no Origin header (runnerd) pass.
- **Blocking proxy:** each in-flight call holds a Tomcat worker thread up to 30s (default pool 200). Fine for one user; switch to DeferredResult if that changes.

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
| Close codes | UplinkRegistry.REPLACED (4000), REVOKED (4001) |
| Verification lifetime | `RELAY_2FA_TTL_HOURS` / `relay.2fa.ttl-hours` |
| Failure limit / lock time | TwoFactorService.MAX_FAILURES, LOCK_SECONDS |
| Clock-drift window | TotpService.WINDOW |
| Encryption key | `KPI_SECRETS_KEY` → VaultEncryptionService.serverKey() |
| Stored fields | User.totpSecret, totpEnabled, totpLastStep |
| Redirect safety | TwoFactorController.safeNext() |
| Error messages | TwoFactorController.message() |
| Page look | templates/twofa-*.html, static/css/auth.css (.auth-hint, .auth-qr, .auth-secret) |

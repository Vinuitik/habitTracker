# Relay (web) — second factor

Files: TotpService.java, TwoFactorService.java, TwoFactorController.java, TwoFactorInterceptor.java, RelayController.java, ../WebConfig.java, ../auth/User.java, templates/twofa-setup.html, templates/twofa-verify.html, templates/relay.html

## Gate
GET /relay/** → Spring Security (logged in? else /login) → TwoFactorInterceptor.preHandle() → TwoFactorService.isVerified(session)
- verified (session `relay2fa.user` == current user id AND `relay2fa.until` > now) → handler
- not enrolled → 302 /2fa/setup?next=<uri>; enrolled → 302 /2fa/verify?next=<uri>

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

## Not yet
- Device list, runner pairing, chat proxy [NOT IMPLEMENTED]
- Reset / disable 2FA (lost phone) [NOT IMPLEMENTED] — today: unset `totpEnabled`/`totpSecret`/`totpLastStep` on the user in Mongo.

## Technology Notes
- **Key:** `KPI_SECRETS_KEY` (base64, 32 bytes), shared with KPI capability secrets via VaultEncryptionService.serverKey(). Unset (true in prod today) → derived from `JWT_SECRET`, so rotating JWT_SECRET makes every stored TOTP secret undecryptable (users must re-enrol). Setting KPI_SECRETS_KEY later has the same effect on existing rows.
- **Why encrypted, not hashed:** the server must recompute codes from the secret. Mongo dumps go to Drive; ciphertext there is useless without the key, which lives only in `.env`.
- **Lockout state is in-memory** (ConcurrentHashMap in TwoFactorService): a restart clears it; single JVM only. `synchronized` verify/confirm also assumes one JVM.
- **Verified flag is a session attribute:** sessions are the default in-memory Tomcat store, so a restart forces password + code again. Bound to the user id, so a reused session for another user doesn't inherit it.
- **Enrolment is trust-on-first-use:** whoever first holds a logged-in session for an account without 2FA can enrol their own authenticator. Enrol right after creating the account.
- **TOTP:** HMAC-SHA1, 6 digits, 30s (authenticator-app defaults). Server clock must be NTP-synced; more than ~30s skew starts rejecting codes.
- **QR:** zxing core renders to inline SVG server-side; no JS, no image endpoint.

## Change Index
| Thing | Where |
|---|---|
| Gated paths | WebConfig.addInterceptors() |
| Verification lifetime | `RELAY_2FA_TTL_HOURS` / `relay.2fa.ttl-hours` |
| Failure limit / lock time | TwoFactorService.MAX_FAILURES, LOCK_SECONDS |
| Clock-drift window | TotpService.WINDOW |
| Encryption key | `KPI_SECRETS_KEY` → VaultEncryptionService.serverKey() |
| Stored fields | User.totpSecret, totpEnabled, totpLastStep |
| Redirect safety | TwoFactorController.safeNext() |
| Error messages | TwoFactorController.message() |
| Page look | templates/twofa-*.html, static/css/auth.css (.auth-hint, .auth-qr, .auth-secret) |

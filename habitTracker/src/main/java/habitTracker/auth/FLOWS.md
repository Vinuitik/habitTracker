# Auth Flows

Files: `SecurityConfig.java`, `RegisterController.java`, `UserService.java`, `UserPrincipal.java`, `JwtUtil.java`, `JwtAuthFilter.java`, `ApiAuthController.java`, `SecurityUtils.java`

## Google OAuth (browser sessions)

1. User visits `/login` → clicks Sign in with Google
   - `RegisterController.loginPage()` → `login.html`
2. Spring redirects to Google OIDC authorization endpoint
   - To change: `SecurityConfig.webFilterChain()` → `oauth2Login(...)` block
   - External: Google Cloud Console → OAuth2 credentials → `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` in env
3. Google authenticates → redirects back with OIDC token
4. `SecurityConfig.loadOidcUser()` → `OidcUserService.loadUser()` → `UserService.findOrCreateOAuthUser()`
   - Match by `googleId` → update email if changed → return existing user
   - Match by email only (local account) → link `googleId` → return user
   - No match → create new `User` in `users` collection
5. Spring session created → redirect to `/`

---

## Local Registration

1. User GET `/register` → `RegisterController.registerPage()` → `register.html`
2. User submits form → POST `/register` → `RegisterController.register()`
3. `UserService.register(email, password, name)`
   - Validates email shape (`EMAIL_PATTERN`) + password length (`MIN_PASSWORD_LENGTH`=8) → `IllegalArgumentException` → register page with error
   - Duplicate email → `IllegalArgumentException` → back to register page with error
   - `BCryptPasswordEncoder.encode(password)` → save `User` to MongoDB
4. Auto-login: session written to `SecurityContextHolder` → `redirect:/today`
   - Was `redirect:/` which forwards to `landing.html` (signed-out page) — looked like "sign-up didn't log me in". To change: `RegisterController.register()`

---

## Local Form Login (browser sessions)

1. User submits `/login` (email + password)
2. Spring built-in `UsernamePasswordAuthenticationFilter` handles it (no custom class)
   - To change login page or redirect: `SecurityConfig.webFilterChain()` → `formLogin(...)` block
   - To add/remove public routes: `requestMatchers(...)` in same method
3. `UserService.loadUserByUsername(email)` → MongoDB lookup → wrapped as `UserPrincipal`
4. BCrypt check against stored `passwordHash`
5. Session created → redirect to `/`

---

## API / JWT (stateless)

1. Client POST `/api/auth/login` `{email, password}`
   - Permitted without token: `SecurityConfig.apiFilterChain()` → `requestMatchers("/api/auth/**").permitAll()`
   - To protect/expose additional API routes: edit `authorizeHttpRequests(...)` in same method
2. `ApiAuthController.login()` → `AuthenticationManager.authenticate()`
3. Success → `JwtUtil.generate(principal)` → response `{token}`
   - Payload: `sub=email`, claim `userId`, signed HMAC-SHA
   - To change expiry: `jwt.expiration-ms` in `application.properties`
   - To change signing key: `jwt.secret` in `application.properties` (min 32 chars)
   - **Fail-fast**: `JwtUtil` constructor throws `IllegalStateException` at startup if `jwt.secret` is blank, `<32` bytes, or equals `INSECURE_DEFAULT_SECRET`. Compose requires `JWT_SECRET` (`${JWT_SECRET:?...}`); tests supply one via `src/test/resources/application.properties`.
4. Client sends `Authorization: Bearer <token>` on all `/api/**` requests
5. Per-request: `JwtAuthFilter.doFilterInternal()` → `JwtUtil.validate()` → `JwtUtil.extractEmail()` → `UserService.loadUserByUsername()` → set `SecurityContextHolder`

---

## User Scoping (userId on all data)

Every data query calls `SecurityUtils.getCurrentUserId()` which reads the current principal from `SecurityContextHolder`.
- Web sessions: principal = `UserPrincipal` (set by form login or OAuth callback)
- API requests: principal = `UserPrincipal` set by `JwtAuthFilter`
- If `userId` is null (unauthenticated / legacy data): queries fall back to unscoped (returns all records)
  - To change this fallback: `StructureService.getStructureForDate()` and `StructureService.fetchHabitStructures()`

### By-id ownership guard (IDOR)
List queries scope via `findByUserId`, but **by-id** access (`/api/habits/{id}`, `/habits/edit/{id}`, `/habits/delete/{id}`, `/habits/update/{id}`, `/habits/streaks`, `/habits/addRule`) loads by Integer id and must verify ownership, else any user reads/edits another's habit by guessing ids.
- `HabitService.ownedByCurrentUser(habit)` is the guard. Applied in `getHabitById`, `getStreaks`, `updateHabit`, `deleteHabit`, `updateRule`, `getHabitDTOById`.
- `StructureService.updateHabitCompletion()` guards via `habitService.getHabitById()` (already ownership-scoped → null if not owned).
- Not owned → read paths return null/empty; write paths throw `IllegalArgumentException` → 400 (or 404 for delete).

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Add/remove public web routes | `SecurityConfig.webFilterChain()` → `requestMatchers(...)` | |
| Add/remove public API routes | `SecurityConfig.apiFilterChain()` → `requestMatchers(...)` | |
| JWT expiry | `jwt.expiration-ms` in `application.properties` | |
| JWT signing key | `jwt.secret` in `application.properties` | min 32 chars; fail-fast in `JwtUtil` ctor |
| Register email/password rules | `UserService.EMAIL_PATTERN`, `MIN_PASSWORD_LENGTH` | |
| Post-register redirect | `RegisterController.register()` → `redirect:/today` | |
| Habit input validation | `Habit` jakarta constraints + `@Valid`/`@Validated` in `HabitWriteController` | groups: `OnCreate` (create) vs Default (update) |
| Validation error → 400 JSON | `GlobalExceptionHandler` | handles `MethodArgumentNotValid`, `ConstraintViolation`, `IllegalArgument` |
| By-id ownership guard (IDOR) | `HabitService.ownedByCurrentUser()` | used by all by-id read/write methods |
| Google OAuth credentials | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` env vars | set in Google Cloud Console |
| OAuth redirect / login page | `SecurityConfig.webFilterChain()` → `oauth2Login(...)` | |
| User creation on OAuth | `UserService.findOrCreateOAuthUser()` | 3 cases: new / email match / googleId match |
| User creation on registration | `UserService.register()` | BCrypt encoded |
| userId scoping fallback | `StructureService.getStructureForDate()`, `StructureService.fetchHabitStructures()` | |

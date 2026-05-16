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
   - Duplicate email → `IllegalArgumentException` → back to register page with error
   - `BCryptPasswordEncoder.encode(password)` → save `User` to MongoDB
4. Auto-login: session written to `SecurityContextHolder` → redirect to `/`

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
4. Client sends `Authorization: Bearer <token>` on all `/api/**` requests
5. Per-request: `JwtAuthFilter.doFilterInternal()` → `JwtUtil.validate()` → `JwtUtil.extractEmail()` → `UserService.loadUserByUsername()` → set `SecurityContextHolder`

---

## User Scoping (userId on all data)

Every data query calls `SecurityUtils.getCurrentUserId()` which reads the current principal from `SecurityContextHolder`.
- Web sessions: principal = `UserPrincipal` (set by form login or OAuth callback)
- API requests: principal = `UserPrincipal` set by `JwtAuthFilter`
- If `userId` is null (unauthenticated / legacy data): queries fall back to unscoped (returns all records)
  - To change this fallback: `StructureService.getStructureForDate()` and `StructureService.fetchHabitStructures()`

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Add/remove public web routes | `SecurityConfig.webFilterChain()` → `requestMatchers(...)` | |
| Add/remove public API routes | `SecurityConfig.apiFilterChain()` → `requestMatchers(...)` | |
| JWT expiry | `jwt.expiration-ms` in `application.properties` | |
| JWT signing key | `jwt.secret` in `application.properties` | min 32 chars |
| Google OAuth credentials | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` env vars | set in Google Cloud Console |
| OAuth redirect / login page | `SecurityConfig.webFilterChain()` → `oauth2Login(...)` | |
| User creation on OAuth | `UserService.findOrCreateOAuthUser()` | 3 cases: new / email match / googleId match |
| User creation on registration | `UserService.register()` | BCrypt encoded |
| userId scoping fallback | `StructureService.getStructureForDate()`, `StructureService.fetchHabitStructures()` | |

# Auth Implementation — TODOS

Generated from plan-eng-review on 2026-04-27. Steps are ordered; do not skip ahead.

## Implementation Steps

- [ ] **Step 1** — Add `userId` fields: `@Indexed String userId` on `Habit`, `KPI`, `KPIHabitMapping`, `Rule`, `HabitStructure`. Replace `@Indexed(unique=true)` on `KPI.name` with `@CompoundIndex(def="{name:1,userId:1}", unique=true)`.
- [ ] **Step 2** — Create `UserRepository extends MongoRepository<User, String>`. `findByEmail(String email)`, `findByGoogleId(String googleId)`.
- [ ] **Step 3** — Create `User` document (`@Document("users")`): fields `id` (String), `email`, `passwordHash`, `googleId`, `name`. Add `UserService implements UserDetailsService`: `register()`, `findOrCreateOAuthUser()`, `loadUserByUsername()`.
- [ ] **Step 4** — Create `UserPrincipal implements UserDetails, OAuth2User, Serializable`. Static factories: `fromUser(User)` and `fromOAuth2(User, OidcUser)`. `getAttributes()` never returns null.
- [ ] **Step 5** — `SecurityConfig`: dual `SecurityFilterChain` with `@Order`. Chain 1 (`/api/**`, stateless, JWT): `NegatedRequestMatcher` NOT needed — match `/api/**` directly. Chain 2 (everything else, session, formLogin, OAuth2). Add `CookieCsrfTokenRepository.withHttpOnlyFalse()` to Chain 2. Wire `UserDetailsService` + `PasswordEncoder` bean.
- [ ] **Step 6** — `JwtUtil`: `generate(UserPrincipal)`, `validate(String token)`, `extractEmail(String token)`, `extractUserId(String token)`. Use JJWT 0.12.x. Read secret from `${jwt.secret}`.
- [ ] **Step 7** — `JwtAuthFilter extends OncePerRequestFilter`: read `Authorization: Bearer <token>`, validate, set `SecurityContextHolder`. On expired/invalid: continue filter chain without setting context (no exception).
- [ ] **Step 8** — `ApiAuthController`: `POST /api/auth/login` → returns JWT or 401. `DataMigrationRunner implements ApplicationRunner`: backfill `userId` on all existing docs using seed user; write `{ _id: "auth-user-scoping-v1" }` to `_migration` collection as last step (idempotency flag).
- [ ] **Step 9** — Update all service queries to filter by `userId`: `HabitService`, `KPIService`, `RulesService`, `StructureService`. Pass `userId` from `SecurityUtils.getCurrentUserId()` (static helper reading `SecurityContextHolder`). Fix `HabitStructureManager.createHabitStructure()` to accept `String userId` param.
- [ ] **Step 10** — Static HTML CSRF fix: update `new-habit.js` to read `XSRF-TOKEN` cookie and send `X-XSRF-TOKEN` header on all POST requests. Verify `CookieCsrfTokenRepository.withHttpOnlyFalse()` is set.
- [ ] **Step 11** — Fix `HabitCompletionSystemTest` regression: add `spring-security-test` dependency, use `@WithMockUser`, add CSRF token to POST requests.
- [ ] **Step 12** — Add `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `JWT_SECRET` to `docker-compose.yml` env section + create `.env.example` template. Add `server.forward-headers-strategy=native` to `application.properties`. Smoke test: register → login → POST habit → GET habits.

## Properties to add (`application.properties`)

```properties
server.forward-headers-strategy=native
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
spring.security.oauth2.client.registration.google.scope=email,profile
jwt.secret=${JWT_SECRET}
jwt.expiration-ms=86400000
```

## Test Plan (separate file)
See `~/.gstack/projects/Vinuitik-habitTracker/ACER-master-eng-review-test-plan-20260427-163054.md`

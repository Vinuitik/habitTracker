# App UI Flows

Files: `HabitReadController.java`, `HabitWriteController.java`, `RegisterController.java`, `StructureService.java`, `HabitService.java`, templates in `src/main/resources/templates/`

---

## Page Graph

```
/login ──────────────────────────────────────────────────────────────────┐
  ├── POST /login (form)           → / (on success) | /login?error        │
  ├── GET  /oauth2/authorization/google → Google → / (on success)         │
  └── GET  /register               → register.html                        │
                                                                           │
/register                                                                  │
  └── POST /register               → / (auto-login on success)            │
                                                                           ↓
/ (Today) ←── all nav bars link here as "Today"                      ←────┘
  ├── checkbox toggle              → POST /habits/update/{id} (AJAX)
  └── nav → My Habits | Overview | Rules | KPIs | KPI Dashboard | Sign out

/habits/list (My Habits)
  ├── dropdown: Edit               → GET /habits/edit/{id}
  ├── dropdown: Info               → GET /habits/info/{id}
  ├── dropdown: Delete             → DELETE /habits/delete/{id} (AJAX, marks active=false)
  ├── "+" button                   → GET /habits/add → redirect /addHabitView/new-habit.html (static)
  └── inactive habits panel        → GET /habits/inactive (AJAX, JSON)

/addHabitView/new-habit.html (static — no Thymeleaf)
  └── POST /new-habit              → redirect /habit

/habits/edit/{id}
  └── POST /habits/edit/{id}       → "Habit updated successfully" (AJAX, stays on page)

/habits/info/{id}
  └── POST /habits/info/save       → 200 OK (AJAX)

/habits/table (Overview)
  ├── date range picker            → GET /habits/tableAsync (AJAX, replaces table)
  └── streak fetch on load         → POST /habits/streaks (AJAX, JSON list of IDs → streaks)

/habits/rules (Rules)
  └── save rule                   → POST /habits/addRule (AJAX)

/kpis (KPI List)
  ├── delete KPI                  → DELETE /kpis/{id} (AJAX)
  └── "Create KPI"                → GET /kpis/create

/kpis/create
  └── POST /kpis                  → redirect /kpis

/kpis/dashboard
  └── data fetch on load          → GET /kpis/data (AJAX, JSON)

POST /logout (all pages via nav "Sign out" button)
  └── Spring Security: invalidate session + clear JSESSIONID cookie → redirect /login?logout
```

---

## Auth Flows

### Google OAuth
`/login` → click "Sign in with Google" → `/oauth2/authorization/google` → Google OIDC → callback → `SecurityConfig.loadOidcUser()` → `UserService.findOrCreateOAuthUser()` → new or existing `User` saved → `UserPrincipal` set in session → redirect `/`

To change redirect on success: `SecurityConfig.webFilterChain()` → `oauth2Login().defaultSuccessUrl()`
To change user creation logic: `UserService.findOrCreateOAuthUser()` (3 cases: googleId match / email match / new)

### Local Login
`/login` form → `POST /login` → Spring `UsernamePasswordAuthenticationFilter` → `UserService.loadUserByUsername()` → BCrypt check → session → redirect `/`

### Registration
`GET /register` → `RegisterController.registerPage()` → `register.html`
`POST /register` → `RegisterController.register()` → `UserService.register()` → BCrypt encode → save `User` → auto-login → redirect `/`
Duplicate email → back to `register.html` with error

### Sign Out
Any page → click "Sign out" → `POST /logout` (CSRF token injected by Thymeleaf `th:action`) → Spring Security: `SecurityContextHolder` cleared + session invalidated + `JSESSIONID` cookie cleared → redirect `/login?logout`

---

## Key Data Flows Per Page

### / (Today)
`HabitReadController.getMethodName()` → `StructureService.getTodayStructure()` → query `habit_structures` by `(date, userId)` → filter active habits → `filterFailedNegativeHabits()` → render `index.html`
On load: JS → `POST /habits/streaks` with all habit IDs → renders streak badges async
On checkbox: JS → `POST /habits/update/{id}?completed=true/false` → `StructureService.updateHabitCompletion()`

To change: what appears on today → `StructureService.getStructureForDate()` + `filterFailedNegativeHabits()`
To change: streak display → JS in `inputView/` static assets

### /habits/list (My Habits)
`HabitReadController.listHabits()` → `HabitService.getAllActiveHabitsAsDTOs()` → `findByUserId(userId)` → `habits-list.html`
Inactive panel: JS → `GET /habits/inactive` → `HabitService.getAllInactiveHabitsAsDTOs()` → `findByUserId(userId)`

### /habits/table (Overview)
`HabitReadController.getHabitTable()` → `HabitService.getAllUniqueHabitNamesIds()` + `StructureService.getStructuresForDateRange()` → `habit-table.html`
Date change: JS → `GET /habits/tableAsync` → same services → JSON → replaces table DOM

### /habits/rules
`HabitReadController.showRuleSetting()` → `HabitService.getAllHabits()` (userId-scoped) → active habits only → `rule-setting.html`
Save: JS → `POST /habits/addRule` → `RuleService.addRule()` + `HabitService.updateRule()`

---

## User Scoping (all pages)

Every server-side data query goes through `SecurityUtils.getCurrentUserId()`.
- Authenticated: `UserPrincipal.getId()` → `findByUserId(id)` → user's data only
- Null (should not reach here — `anyRequest().authenticated()` blocks it): `List.of()` — empty, never all-users data

To change scoping logic: `HabitService.getAllHabits()`, `StructureService.getStructureForDate()`, `StructureService.fetchHabitStructures()`

---

## Public vs Protected Routes

| Route | Auth required | Note |
|---|---|---|
| `/login` | No | Spring `permitAll` |
| `/register` | No | Spring `permitAll` |
| `/css/**`, `/js/**`, `/addHabitView/**` | No | Static assets |
| `/error` | No | Spring `permitAll` |
| Everything else | Yes | Redirects to `/login` |
| `/api/**` | JWT token | Stateless chain, separate from session |

To add/remove public routes: `SecurityConfig.webFilterChain()` → `requestMatchers(...).permitAll()`

---

## Technology Notes

- **Sessions**: Spring Boot default in-memory `HttpSession`. All sessions lost on container restart — users must re-login after every `docker-compose` restart.
- **CSRF**: `CookieCsrfTokenRepository` — token stored in `XSRF-TOKEN` cookie, readable by JS. Thymeleaf `th:action` injects hidden `_csrf` field automatically. AJAX calls must read the cookie and send the token.
- **Static add-habit page**: `/addHabitView/new-habit.html` is a plain static file, not a Thymeleaf template. It does NOT get CSRF or auth context from Thymeleaf — it reads the CSRF cookie via JS.
- **Nav is copy-pasted**: There is no shared Thymeleaf fragment file. Each of the 9 templates defines its own `<nav>`. Changes to nav must be applied to all 9 files.

---

## Change Index

| What to change | Where |
|---|---|
| Add a new nav link | All 9 templates (no shared fragment) |
| Login page layout | `templates/login.html` |
| Register page layout | `templates/register.html` |
| Redirect after login/OAuth | `SecurityConfig.webFilterChain()` → `defaultSuccessUrl()` |
| Redirect after logout | `SecurityConfig.webFilterChain()` → `logout().logoutSuccessUrl()` |
| Add habit form fields | `addHabitView/new-habit.html` (static) + `Habit.java` + `POST /new-habit` in `HabitWriteController` |
| Today page habit display logic | `StructureService.getTodayStructure()`, `filterFailedNegativeHabits()` |
| Mark habit complete endpoint | `HabitWriteController.updateHabit()` → `POST /habits/update/{id}` |
| Edit habit fields | `templates/edit-habit.html` + `HabitWriteController.updateHabit()` → `POST /habits/edit/{id}` |
| Habit info fields | `templates/info.html` + `HabitWriteController.saveHabit()` → `POST /habits/info/save` |
| Overview date range | `HabitReadController.getHabitTable()` / `getHabitTableData()` → `StructureService.getStructuresForDateRange()` |
| Public/protected route list | `SecurityConfig.webFilterChain()` → `authorizeHttpRequests(...)` |

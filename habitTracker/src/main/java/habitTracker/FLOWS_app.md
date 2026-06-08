# App UI Flows

Files: `PageController.java`, `HabitReadController.java`, `HabitWriteController.java`, `KPIController.java`, `RegisterController.java`, `StructureService.java`, `HabitService.java`, static HTML in `src/main/resources/static/`

---

## Architecture Overview

Static HTML shells (served by Spring) + vanilla JS that fetches data via JSON APIs. No Thymeleaf in page rendering — Thymeleaf is only used for `/login` and `/register`.

```
Browser → Spring (PageController) → forwards to static/[page].html
                                  ↓
                        JS fetch → JSON API endpoints (session cookie)
```

Auth enforcement:
- `PageController` routes (`/habits/list`, etc.) require session auth → Spring Security redirects to `/login` if unauthenticated
- JSON API endpoints also require session auth
- `/auth/me` is public and returns 401 manually if no session — JS guard on each page uses this for UX

---

## Page Routing (PageController)

`PageController.java` maps old URL paths → forward to static HTML files:

| URL | Static file served |
|---|---|
| `/`, `/habit` | `static/index.html` |
| `/habits/list` | `static/habits-list.html` |
| `/habits/table` | `static/habit-table.html` |
| `/habits/rules` | `static/rule-setting.html` |
| `/habits/add` | `static/habit-add.html` |
| `/habits/edit/{id}` | `static/habit-edit.html` |
| `/habits/info/{id}` | `static/habit-info.html` |
| `/kpis` | `static/kpi-list.html` |
| `/kpis/create` | `static/kpi-create.html` |
| `/kpis/dashboard` | `static/kpi-dashboard.html` |

Edit/info pages parse the habit ID from `window.location.pathname.split('/').pop()`.

---

## Page Graph

```
/login (Thymeleaf) ──────────────────────────────────────────────────┐
  ├── POST /login (form)       → / (on success) | /login?error        │
  ├── GET  /oauth2/authorization/google → Google → / (on success)     │
  └── GET  /register (Thymeleaf)                                      │
                                                                       ↓
/ (Today) ←── all nav bars link here                             ←────┘
  ├── JS fetches /api/today → renders habit list
  ├── checkbox toggle → POST /habits/update/{id}
  └── nav → My Habits | Overview | Rules | KPIs | KPI Dashboard | Sign out

/habits/list (My Habits)
  ├── JS fetches /api/habits → renders list
  ├── dropdown Edit → /habits/edit/{id}
  ├── dropdown Info → /habits/info/{id}
  ├── dropdown Delete → DELETE /habits/delete/{id}
  ├── Inactive toggle → GET /habits/inactive (JSON)
  └── "+" → /habits/add

/habits/add
  └── POST /new-habit (JSON body) → redirect /

/habits/edit/{id}
  ├── JS fetches /api/habits/{id} → pre-fills form
  └── POST /habits/edit/{id} (JSON body) → redirect /habits/list

/habits/info/{id}
  ├── JS fetches /api/habits/{id} → pre-fills form
  └── POST /habits/info/save (JSON body) → redirect /habits/list

/habits/table (Overview)
  ├── JS fetches /api/habits/table → renders headers + initial rows
  └── date range update → GET /habits/tableAsync (JSON, reuses existing JS)

/habits/rules (Rules)
  ├── JS fetches /api/habits/rules → renders habit lists
  └── save rule → POST /habits/addRule (JSON)

/kpis (KPI List)
  ├── JS fetches /api/kpis (JSON) → renders KPI cards
  └── delete → DELETE /api/kpis/{name}

/kpis/create
  ├── JS fetches /api/kpis/available-habits → renders habit checkboxes
  └── POST /api/kpis/create (JSON body) → redirect /kpis

/kpis/dashboard
  ├── JS fetches /api/kpis/dashboard (JSON) → renders chart cards
  ├── chart data → GET /api/kpis/{name}/data?period=weekly|monthly|alltime|custom
  ├── period tabs: Weekly / Monthly / All Time / Custom
  └── Custom tab reveals date-range pickers → Apply → GET ...?period=custom&startDate=&endDate=

POST /logout (all pages via nav "Sign out" button)
  └── CSRF token from XSRF-TOKEN cookie → Spring Security invalidates session → redirect /login?logout
```

---

## Auth Flows

### Google OAuth (unchanged)
`/login` → "Sign in with Google" → `/oauth2/authorization/google` → Google OIDC → `SecurityConfig.loadOidcUser()` → `UserService.findOrCreateOAuthUser()` → session → redirect `/`

### Local Login (unchanged)
`/login` form → `POST /login` → Spring `UsernamePasswordAuthenticationFilter` → `UserService.loadUserByUsername()` → BCrypt → session → redirect `/`

### Registration (unchanged)
`GET /register` → `RegisterController.registerPage()` → `register.html`
`POST /register` → `RegisterController.register()` → BCrypt → auto-login → redirect `/`

### Sign Out
Any page → click "Sign out" → `POST /logout` (CSRF from XSRF-TOKEN cookie) → Spring Security: session invalidated → redirect `/login?logout`

### `/auth/me` (JS auth guard)
`GET /auth/me` → public endpoint → `SecurityUtils.getCurrentUserId()` → 200+userId if session valid, 401 if not
JS on each page calls this on load; 401 → `window.location.href = '/login'`

---

## JSON API Endpoints (session-auth via web chain)

| Endpoint | Returns |
|---|---|
| `GET /api/today` | `{date, habits:[{id,name,completed,defaultMade}]}` |
| `GET /api/habits` | `List<HabitDTO>` (active, sorted by name) |
| `GET /api/habits/table?startDate&endDate` | `{startDate,endDate,habitNames,tableData}` |
| `GET /api/habits/rules` | `List<RuleDTO>` (active habits for rules page) |
| `GET /api/habits/{id}` | `Habit` (single habit by ID) |
| `GET /api/habits/inactive` | `List<HabitDTO>` (inactive) |
| `GET /habits/inactive` | same (legacy path kept for habits-list.js) |
| `GET /habits/tableAsync?startDate&endDate` | `List<StructureDTO>` (kept for habit-table.js) |
| `POST /habits/streaks` | `List<Pair<Integer,Integer>>` body: `[habitId,...]` |
| `GET /api/kpis` | `List<KPIDTO>` |
| `GET /api/kpis/available-habits` | `List<Habit>` (active habits for KPI create) |
| `GET /api/kpis/dashboard` | `List<KPIDTO>` |
| `GET /api/kpis/{name}/data?period=weekly\|monthly\|alltime\|custom[&startDate=&endDate=]` | `List<KPIDataDTO>` |
| `GET /auth/me` | `{userId}` or 401 |

### Write endpoints (unchanged)
| Endpoint | Body | Notes |
|---|---|---|
| `POST /new-habit` | JSON `Habit` | sets curDate, active, streak before save |
| `POST /habits/edit/{id}` | JSON `Habit` | |
| `POST /habits/update/{id}?completed=&date=` | form params | checkbox toggle |
| `POST /habits/info/save` | JSON `Habit` | partial update |
| `POST /habits/addRule` | JSON `UpdateDTO` | |
| `DELETE /habits/delete/{id}` | — | marks active=false |
| `POST /api/kpis/create` | JSON `{name,description,higherIsBetter,habitIds}` | |
| `POST /api/kpis/{name}/data?date=&value=` | form params | |
| `DELETE /api/kpis/{name}` | — | |

---

## CSRF

`CookieCsrfTokenRepository.withHttpOnlyFalse()` sets `XSRF-TOKEN` cookie. All JS reads it via:
```js
document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN=')).split('=')[1]
```
and sends as `X-XSRF-TOKEN` header. Logout form reads same cookie into a hidden `_csrf` input.

---

## User Scoping (all pages)

All server-side data queries go through `SecurityUtils.getCurrentUserId()` → `findByUserId(id)`. If userId is null (should not reach here — `anyRequest().authenticated()` blocks it): `List.of()`.

KPI-specific: `KPIService.getAllActiveKPIs()` returns `List.of()` when userId is null (defensive guard added 2026-06-08 — previously fell back to an unscoped query returning all users' data).
To change this fallback: `KPIService.getAllActiveKPIs()` — the `if (userId == null) return List.of()` guard.

---

## Public vs Protected Routes

| Route | Auth required | Note |
|---|---|---|
| `/login`, `/register` | No | Spring `permitAll` |
| `/auth/me` | No (manual 401) | public endpoint, checks session internally |
| `/css/**`, `/js/**`, `/*.html`, static assets | No | static resources |
| All page routes (`/`, `/habits/list`, etc.) | Yes (session) | `PageController` + Spring Security |
| All API routes (`/api/**`, `/kpis/**`, etc.) | Yes (session) | web chain `anyRequest().authenticated()` |
| `/api/auth/**` | JWT | separate stateless chain |
| `/error` | No | Spring `permitAll` |

---

## Technology Notes

- **Sessions**: Spring Boot default in-memory `HttpSession`. All sessions lost on container restart — users must re-login after every `docker-compose` restart.
- **CSRF**: `CookieCsrfTokenRepository` — token in `XSRF-TOKEN` cookie, readable by JS. No Thymeleaf `th:action` needed on static pages — JS reads cookie directly.
- **Static pages**: Thymeleaf is NOT used for page rendering. Pages are plain HTML files in `static/`. `PageController` does a servlet `forward:` to each file. Spring Security auth runs on the page route, not the forwarded path.
- **Edit/Info page ID**: Habit ID extracted from URL path via `window.location.pathname.split('/').pop()` — no query string needed.
- **Nav is copy-pasted**: All 10 static HTML files have their own `<nav>`. Changes must be applied to all 10 files.
- **Legacy JS reuse**: `input.js`, `habits-list.js`, `habit-table.js`, `edit-habit.js`, `info.js`, `rule-setting.js`, `kpi-list.js`, `kpi-dashboard.js` are all kept. Their `DOMContentLoaded` handlers fire before async `init()` populates the DOM, so each page's `init()` re-runs any DOM-dependent setup after data loads.
- **Integration tests**: All tests use Testcontainers `@ServiceConnection` (a fresh `mongo:7` container per test class). Do NOT use `@WithMockUser` — it injects a `String` principal which causes `SecurityUtils.getCurrentUserId()` to return null, breaking all userId-scoped assertions. Use `AuthTestHelper` (in `src/test/java/habitTracker/auth/`) which creates real `User` docs and `UserPrincipal` objects. JWT tokens for `/api/**` tests: `auth.bearer(principal)` → `Authorization: Bearer <token>` header.

---

## Change Index

| What to change | Where |
|---|---|
| Add a new nav link | All 10 static HTML files (no shared fragment) |
| Add a new page route | `PageController.java` + new static HTML file + SecurityConfig if new path pattern |
| Login page layout | `templates/login.html` |
| Register page layout | `templates/register.html` |
| Redirect after login/OAuth | `SecurityConfig.webFilterChain()` → `defaultSuccessUrl()` |
| Redirect after logout | `SecurityConfig.webFilterChain()` → `logout().logoutSuccessUrl()` |
| Today page habit data | `HabitReadController.getToday()` → `GET /api/today` |
| Mark habit complete | `HabitWriteController.updateCompletion()` → `POST /habits/update/{id}` |
| Habit list data | `HabitReadController.listHabits()` → `GET /api/habits` |
| Table data | `HabitReadController.getHabitTable()` → `GET /api/habits/table` |
| Rules data | `HabitReadController.getRulesHabits()` → `GET /api/habits/rules` |
| Single habit fetch | `HabitReadController.getHabit()` → `GET /api/habits/{id}` |
| KPI list data | `KPIController.listKPIs()` → `GET /api/kpis` |
| KPI create | `KPIController.createKPI()` → `POST /api/kpis/create` |
| KPI chart data (period) | `KPIController.getKPIData()` → `GET /api/kpis/{name}/data` — add period case in switch + service method |
| KPI all-time data | `KPIService.getAllTimeKPIData()` → `DynamicKPIDataRepository.findAllOrderByDateAsc()` |
| KPI custom range | `KPIService.getKPIDataForDateRange()` → `DynamicKPIDataRepository.findByDateBetweenOrderByDateAsc()` |
| Auth guard (JS) | Each HTML file's inline `init()` script |
| Public/protected routes | `SecurityConfig.webFilterChain()` → `authorizeHttpRequests(...)` |

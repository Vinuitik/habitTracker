# Known Bugs

## Pre-existing test infrastructure failures (not production bugs)

### DynamicKPIDataRepositoryTest — all 9 tests error
`@DataMongoTest` + `activeProfiles={"test"}` with no `application-test.properties`.
`MONGO_URI` env var is unset → invalid connection string → ApplicationContext fails.
**Fix:** add `src/test/resources/application-test.properties` with a Testcontainers URI
or migrate the test to `@SpringBootTest` + `@ServiceConnection` like the others.

### HabitTrackerApplicationTests.contextLoads — errors
Plain `@SpringBootTest` with no Testcontainers Mongo → same missing URI cause.
**Fix:** add `@ServiceConnection` Testcontainers container or a test properties file.

---

## Previously reported — investigated and closed

### KPI list / dashboard not scoped to current user
`KPIService.getAllActiveKPIs()` had a `null`-userId fallback to `kpiRepository.findByActive(true)`.
- With real `UserPrincipal` (production auth path) the bug never triggered — scoping worked correctly.
- The fallback was reachable only when `@WithMockUser` was used in tests (String principal → `SecurityUtils` returns null).
**Fixed:** fallback changed to `List.of()` (2026-06-08). Covered by `KPIIntegrationTest`:
`listKPIs_doesNotReturnOtherUsersKPIs`, `listKPIs_returnsOnlyOwnKPIs`, `dashboardKPIs_doesNotReturnOtherUsersKPIs`.

### Creating a KPI with no habits linked → whitelabel error
Cannot reproduce via API. `KPIIntegrationTest.createKPI_withNoHabitsLinked_returns200_notWhitelabel`
and `createKPI_withNullHabitIds_returns200_notWhitelabel` both return 200.
Frontend investigated (2026-06-08): `kpi-create.html` handles empty habits array gracefully.
Likely historical — bug no longer present in current code.

### "Add Data" in KPI list page silently fails (FIXED 2026-06-08)
`kpi-list.js::initializeModal()` crashed on `document.querySelector('.close')` returning null
(close button class is `modal-box__close`, not `.close`). No null guard → crash →
`form.addEventListener('submit', handleFormSubmit)` never reached → form did a native browser
GET submit (page reload) instead of the JSON POST. Fixed by adding null checks on `closeBtn` and `form`.

### Add-habit form auth (userId not set on saved Habit)
Cannot reproduce. `KPIIntegrationTest.addHabit_persistsUserId_onSavedHabit` passes —
`Habit.userId` is correctly set to the authenticated user's id via `SecurityUtils.getCurrentUserId()`
when a real `UserPrincipal` is in the security context.

---

## Test hygiene fixed (2026-06-08)

`HabitCompletionSystemTest` was using `@WithMockUser` which injects a `String` principal,
causing `SecurityUtils.getCurrentUserId()` to always return `null`. Replaced with
`AuthTestHelper` which creates real `User` documents and `UserPrincipal` objects.
Tests now exercise the actual auth stack end-to-end.

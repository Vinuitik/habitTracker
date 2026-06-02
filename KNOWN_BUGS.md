# Known Bugs

## KPI list not scoped to current user
`KPIService.getAllActiveKPIs()` has the same null-userId fallback as habits did — returns all users' KPIs when userId is null. `/kpis` page shows every KPI in the database.
**Fix:** same pattern as habits — change null branch to `List.of()`, remove the unscoped fallback.

## KPI dashboard shows all users' graphs
Root cause same as above. Dashboard fetches KPI data for all KPIs returned by `getAllActiveKPIs()`, so graphs from other users appear.

## Creating a KPI with no habits linked → whitelabel error
Reproduced when no habits exist for the user and a KPI creation is attempted. Likely a null/empty list not handled in `KPIController` or `KPIService.createKPI()`. Stack trace not yet captured.

## Add-habit form auth (partially fixed)
Static `/addHabitView/new-habit.html` was outside the auth layer — no CSRF token, no session context. Converted to Thymeleaf template in this session. Needs rebuild + manual verification to confirm habits now save with correct userId.

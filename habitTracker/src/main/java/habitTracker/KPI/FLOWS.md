# KPI Flows

Files: `KPI.java`, `KPIData.java`, `KPIDTO.java`, `KPIDataDTO.java`, `KPIController.java`, `KPIService.java`, `KPIRepository.java`, `KPIHabitMappingRepository.java`, `KPICollectionNameUtil.java`, `DynamicKPIDataRepository.java`

## Storage model

- `KPI` docs live in the `kpis` collection, one per user-defined metric. Unique per `{name, userId}` — two users (or the same user, historically) can have same-named KPIs.
- Each KPI's data points live in their **own** Mongo collection, named `kpi_data_<kpiId>` (`KPICollectionNameUtil.toCollectionName()`). Keyed by the KPI's own `_id`, not its name — a same-named KPI from a different user gets a different collection since ids never collide. `DynamicKPIDataRepository` wraps `MongoTemplate`'s dynamic-collection-name overloads (`find(query, KPIData.class, collectionName)`) to read/write it.
- `KPIHabitMapping` (collection `kpi_habit_mappings`) links a KPI to zero or more `Habit`s, scoped by `userId`.

To change collection naming: `KPICollectionNameUtil.toCollectionName()`.

## User isolation

Every public `KPIService` method scoped to a single user resolves `userId` via `SecurityUtils.getCurrentUserId()` and threads it through `KPIRepository.findByNameAndUserId` / `findByActiveAndUserId` — never `findByName` alone. Combined with id-keyed collections, this means a same-named KPI from another user is neither readable nor writable through the normal API surface.

The daily cron (`KPIDefaultFillService`, in `habitTracker.updater`) is the one caller that legitimately has no `userId` — see its own section in `updater/FLOWS.md`. It never uses `SecurityUtils`; it takes an already-resolved `KPI` object (which carries its own `userId`/`id`) and only ever touches that KPI's own collection.

## Request flow: log a data point

`POST /api/kpis/{name}/data?date=&value=` → `KPIController.addKPIData()` → `KPIService.addKPIData()`:
1. Resolve KPI by `(name, userId)` — 404-equivalent `IllegalArgumentException` if not found/not owned.
2. `saveKPIDataPoint(kpi, date, value, autoFilled=false)` — upsert by date (same `_id` preserved on update), recompute EMA, save into the KPI's collection.

A manual log always sets `autoFilled = false`, even overwriting a day the cron auto-filled — the human value wins and the flag clears.

## Default-fill (opt-in per KPI)

`KPI.autoFillEnabled` (bool) + `KPI.defaultValue` (nullable) — off by default. When enabled, the nightly cron (`KPIDefaultFillService`, see `updater/FLOWS.md`) fills in `defaultValue` for any day with no logged value, marking `KPIData.autoFilled = true`. Never overwrites an existing point (manual or previously auto-filled).

Set via:
- `POST /api/kpis/create` — `autoFillEnabled`/`defaultValue` in the JSON body.
- `PUT /api/kpis/{name}/default-fill` — post-creation update, same two fields.

Both paths validate: `defaultValue` is required whenever `autoFillEnabled = true` (`KPIService.createKPI()` / `updateDefaultFillSettings()`).

## EMA calculation

`KPIService.calculateEMA()` — 14-day EMA (smoothing factor `2/15`) over the last 30 points (`DynamicKPIDataRepository.findTopNOrderByDateDesc(30, ...)`). First point in a collection gets `EMA = value`. Drives `KPIDataDTO.trendDirection`/`colorIntensity` (see `convertToDataDTO`) and the dashboard chart's point coloring (`static/js/kpi-dashboard.js` `getTrendColor()`).

## Frontend

Two copies exist per page (`templates/kpi-*.html` and `static/kpi-*.html`); `PageController` forwards `/kpis*` routes to the `.html` paths under `static/` via Spring's static resource handler, so **`static/` is what's actually served** — `templates/kpi-*.html` appears to be a stale leftover.

- `static/kpi-create.html` — create form. Auto-fill checkbox reveals a default-value number input; client-side validates `defaultValue` is present when checked before POSTing.
- `static/kpi-list.html` + `static/js/kpi-list.js` — card grid with Add Data / View Charts / Auto-Fill / Delete per KPI. Cards opted into auto-fill show an "Auto-fill: N" badge. The Auto-Fill button opens a settings modal (`PUT .../default-fill`).
- `static/kpi-dashboard.html` + `static/js/kpi-dashboard.js` — Chart.js line charts (value + EMA trend). Auto-filled points render as a diamond (`rectRot` point style) instead of a circle, and the tooltip appends "— auto-filled".

## Change Index

| What to change | Where | Note |
|---|---|---|
| Collection naming scheme | `KPICollectionNameUtil.toCollectionName()` | currently `kpi_data_<kpiId>` |
| EMA window / smoothing | `KPIService.calculateEMA()` | 30-point lookback, 14-day smoothing factor |
| Default-fill opt-in validation | `KPIService.createKPI()` / `updateDefaultFillSettings()` | `defaultValue` required when `autoFillEnabled=true` |
| Default-fill write path | `KPIService.fillDefaultIfMissing()` / `saveKPIDataPoint()` | never overwrites an existing data point |
| Default-fill cron trigger | `habitTracker.updater.KPIDefaultFillService` | see `updater/FLOWS.md` |
| Auto-filled chart marker | `static/js/kpi-dashboard.js` `renderChart()` `pointStyle` | diamond vs circle |
| Auto-fill UI (create) | `static/kpi-create.html` | checkbox + conditional number input |
| Auto-fill UI (edit) | `static/kpi-list.html` `openAutoFillModal()` | modal → `PUT /api/kpis/{name}/default-fill` |

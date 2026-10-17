# KPI Flows

Files: `KPI.java`, `KPIData.java`, `KPIDTO.java`, `KPIDataDTO.java`, `KPIController.java`, `KPIService.java`, `KPIRepository.java`, `KPIHabitMappingRepository.java`, `KPICollectionNameUtil.java`, `DynamicKPIDataRepository.java`, `KPIProxyCapability.java`, `KPIProxyCapabilityRepository.java`, `SandboxCapabilityProvider.java`, `SandboxClient.java`, `HttpSandboxClient.java`, `CapabilityStatus.java`, `CapabilityPlatform.java`

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

## Habit links (post-creation)

`habitIds` can also be set at creation (`POST /api/kpis/create`) and re-set any time after via
`PUT /api/kpis/{name}/habits` → `KPIService.updateKPIHabitMappings()` — full replace (deletes this
user's existing `KPIHabitMapping` rows for the KPI, then inserts the given `habitIds`, `[]`/`null`
clears all links). 404-equivalent `IllegalArgumentException` if the KPI name isn't owned by the
caller. UI: `static/kpi-list.html` "Manage Habits" button → `openManageHabitsModal()`, reusing the
same `/api/kpis/available-habits` list as the create form.

## EMA calculation

`KPIService.calculateEMA()` — 14-day EMA (smoothing factor `2/15`) over the last 30 points (`DynamicKPIDataRepository.findTopNOrderByDateDesc(30, ...)`). First point in a collection gets `EMA = value`. Drives `KPIDataDTO.trendDirection`/`colorIntensity` (see `convertToDataDTO`) and the dashboard chart's point coloring (`static/js/kpi-dashboard.js` `getTrendColor()`).

## Frontend

Two copies exist per page (`templates/kpi-*.html` and `static/kpi-*.html`); `PageController` forwards `/kpis*` routes to the `.html` paths under `static/` via Spring's static resource handler, so **`static/` is what's actually served** — `templates/kpi-*.html` appears to be a stale leftover.

- `static/kpi-create.html` — create form. Auto-fill checkbox reveals a default-value number input; client-side validates `defaultValue` is present when checked before POSTing.
- `static/kpi-list.html` + `static/js/kpi-list.js` — card grid with Add Data / View Charts / Auto-Fill / Delete per KPI. Cards opted into auto-fill show an "Auto-fill: N" badge. The Auto-Fill button opens a settings modal (`PUT .../default-fill`).
- `static/kpi-dashboard.html` + `static/js/kpi-dashboard.js` — Chart.js line charts (value + EMA trend). Auto-filled points render as a diamond (`rectRot` point style) instead of a circle, and the tooltip appends "— auto-filled".

## Sandboxed capability proxy (M7)

Nightly: `KPIProxyFillService.fillFromProxies()` → KPI with `proxyType=SANDBOX_CAPABILITY` → `ProxyHealthService.needsRepair()` (skip if breaker open) → `SandboxCapabilityProvider.fetchValue()`:
1. `KPIProxyCapabilityRepository.findFirstByKpiIdAndUserIdOrderByVersionDesc()` (collection `kpi_proxy_capabilities`, read fresh every run — editing needs no redeploy). Missing / not `ACTIVE` / not `SERVER` platform → `Optional.empty()` (counts as failure).
2. Prepends `TARGET_DATE = "<yesterday>"` to `sourceCode` → `SandboxClient.run()` (`HttpSandboxClient`: `POST http://sandbox:8090/run`, see `/sandbox/FLOWS.md`).
3. `OK` → last non-blank stdout line parsed as double (`parseLastLine`). Anything else (`NONZERO_EXIT`, `TIMEOUT`, `SIDECAR_ERROR`, unparseable stdout) → `Optional.empty()`.
4. Back in fill service: value → `isAnomalous()` → `KPIService.addKPIDataForUser(..., PROXY_CAPABILITY)` → `recordSuccess()`/`flagAnomaly()`; empty → `ProxyHealthService.recordFailure()` → N consecutive (`kpi.proxy.failure-threshold`, default 3) → `NEEDS_REPAIR`.
5. The provider also writes `lastRunAt` / `lastResult` / `consecutiveFailures` on the capability doc (diagnostics only; the breaker lives on the KPI).

Calls are sequential: a hanging script blocks the nightly loop for at most its timeout, then the next KPI/user runs normally.

To change script timeout: `sandbox.script-timeout-seconds` (`SANDBOX_SCRIPT_TIMEOUT_SECONDS`). To change sidecar URL: `sandbox.url` (`SANDBOX_URL`). To change HTTP read timeout: `sandbox.read-timeout-seconds`. To change the stdout contract: `SandboxCapabilityProvider.parseLastLine()`.

[NOT IMPLEMENTED] Anything that creates capabilities (M8 generation); today a capability row is inserted by hand into Mongo. Secrets injection and network allowlists (`declaredCapabilities` is stored but not enforced; sandbox is always `--network none`, no env secrets).

Minimal hand-inserted capability (`status` must be `ACTIVE`; `_class` is added by Spring Data when saved via the repository):
`{userId, kpiId, platform:"SERVER", language:"python", sourceCode:"print(42)", status:"ACTIVE", version:1}` plus a KPI with `proxyType:"SANDBOX_CAPABILITY"`.

## Technology Notes

- **Docker socket = host root.** Only the `sandbox` container mounts `/var/run/docker.sock`; `javaapp` never does. Anyone who can make the sidecar run arbitrary docker args owns the host, so the sidecar hardcodes the `docker run` flags (`build_docker_cmd`) and accepts only `{code, timeout}`. It has NO authentication: it relies on being reachable only on the compose network (no published port). Anything else on that network (any container, a compromised `javaapp`) can run code in a capped sandbox, but cannot choose docker flags.
- **Sidecar down / restarting:** `HttpSandboxClient` turns connection errors into `SIDECAR_ERROR` → counts as a failure per KPI. A multi-night outage therefore trips every sandbox KPI's breaker to `NEEDS_REPAIR` after 3 nights, needing a manual reset (`PUT /api/kpis/{name}/proxy/reset`). On sidecar start it force-removes leftover `kpi-sandbox=1` containers.
- **Concurrency:** sidecar semaphore (`SANDBOX_MAX_CONCURRENT`, default 4); waiters give up after 60s with `SIDECAR_ERROR`. `javaapp` calls sequentially anyway. HTTP read timeout (150s) must stay above script timeout + queue wait + pull, or `javaapp` gives up while the container still runs (it is still killed by the sidecar).
- **Timeouts:** enforced in the sidecar (clamped to `SANDBOX_MAX_TIMEOUT_SECONDS`=60) with an explicit `docker rm -f`, since `--rm` does not fire when the client is killed. An OOM kill shows as exit 137 → `NONZERO_EXIT`.
- **Image pull:** runner image `python:3.12-slim` is pulled lazily on first run (before the script timer starts, up to 180s), not at sidecar start. First night after deploy is slower; no internet at that moment = `SIDECAR_ERROR`. The image is never auto-updated afterwards.
- **Isolation limits:** per-run container: 128m, 0.25 cpu, `--network none`, pids 64, all caps dropped, read-only rootfs + 16m tmpfs, uid 65534. Containers share the host kernel (no gVisor); a kernel escape is out of scope per the staged-safety posture. Those container memory caps are on top of the sidecar's own 64m `mem_limit`.
- **Script contract:** python only, source <= 64KB, output truncated at 64KB, value = last non-blank stdout line. Scripts get `TARGET_DATE` as a module constant, nothing else.
- **Not persisted:** run output beyond 300 chars of `lastResult`; no audit log of executed code besides the capability doc.

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
| Habit links, post-creation | `KPIService.updateKPIHabitMappings()` | `PUT /api/kpis/{name}/habits`, full replace |
| Habit links UI (edit) | `static/kpi-list.html` `openManageHabitsModal()` | modal → `PUT /api/kpis/{name}/habits` |
| Sandbox proxy provider | `SandboxCapabilityProvider.fetchValue()` | `ProxyType.SANDBOX_CAPABILITY` |
| Capability storage | `KPIProxyCapability` / `KPIProxyCapabilityRepository` | collection `kpi_proxy_capabilities` |
| Script timeout | `sandbox.script-timeout-seconds` / `SANDBOX_SCRIPT_TIMEOUT_SECONDS` | sidecar clamps to its own max |
| Sidecar URL / HTTP timeout | `sandbox.url`, `sandbox.read-timeout-seconds` / `HttpSandboxClient` | default `http://sandbox:8090` |
| Stdout -> value parsing | `SandboxCapabilityProvider.parseLastLine()` | last non-blank line, finite double |
| Container limits / flags | `sandbox/app.py` `build_docker_cmd()`, `SANDBOX_MEM`, `SANDBOX_CPUS` | see `/sandbox/FLOWS.md` |

# Daily Cron Flows

Files: `UpdateScheduler.java`, `LastRunDateService.java`, `HabitUpdateService.java`, `HabitDateCalculator.java`, `HabitStructureManager.java`, `KPIDefaultFillService.java`

## Triggers

| Event | Method | Schedule |
|---|---|---|
| App startup | `UpdateScheduler.runOnStartup()` | `@PostConstruct` — fires every container restart |
| Nightly | `UpdateScheduler.scheduledUpdate()` | `@Scheduled` cron `0 5 0 * * ?` (00:05 server time) |

Both call `UpdateScheduler.performDailyUpdate()`, which runs `HabitUpdateService.updateAllHabits()`
then `KPIDefaultFillService.fillMissingDefaults()` — one daily job, two sub-tasks, gated by the
same idempotency guard below.
To change schedule: edit cron expression in `UpdateScheduler.scheduledUpdate()`.

---

## Idempotency Guard

`LastRunDateService.hasRunToday()`
- Reads `last_run_date` collection (max `_id` = most recent inserted date)
- Already ran today → log + exit
- Not ran → `LastRunDateService.markRunToday()` inserts today's date → proceed

One document per run is inserted; never updated. The collection grows unbounded (no cleanup implemented).

---

## Unified Daily Engine (rolling grace window)

`HabitUpdateService.updateAllHabits()` → `processHabit(habit, today)` is now the **single** pass:
it advances the schedule, seeds structures, AND credits/docks the streak. (The former
`StreakCalculationService` day-by-day walk was folded in here and deleted — the two passes had to
agree on exactly when a window closes, so unifying them removed that coupling.)

### Model
Each habit's current occurrence spans a **window** `[curDate, curDate + frequency)`. `curDate` is
the window anchor and only advances when the occurrence resolves. The habit stays visible/catchable
for the whole window. At `frequency=1` the window is a single day → identical to the old model
(a miss is docked on the next run).

### `processHabit` roll-forward
Skip if `active=false` or `curDate == null`. Then loop:
- Stop if `endDate` passed (`anchor > endDate`) or window not open yet (`today < anchor`).
- Seed the anchor's `HabitStructure` if missing (`completed = defaultMade`) — keeps it on the Today page + table.
- Resolve the occurrence:
  - **normal**: SUCCESS if completed anywhere in `[anchor, anchor+freq)` (resolves even mid-window); else LAPSE once `today >= anchor+freq`; else OPEN → stop.
  - **defaultMade**: never resolved early — once `today >= anchor+freq`, SUCCESS if no relapse (`completed=false`) in the window, else LAPSE; while open → stop.
- Apply the streak transition, then `anchor += frequency` and continue.

Persist `streak`, `longestStreak`, `curDate`, and `lastNegativeStreak` (set/unset) via `MongoTemplate.updateFirst()`.

### Streak transitions (per resolved occurrence)
| Outcome | Prior streak | Result |
|---|---|---|
| SUCCESS | > 0 | `streak++` |
| SUCCESS | ≤ 0 | save `lastNegativeStreak`, `streak = 1` |
| LAPSE | > 0 | `streak = 0`, clear `lastNegativeStreak` |
| LAPSE | ≤ 0 | `streak--` |

Negative magnitude = number of consecutive fully-lapsed windows. Multi-day downtime is caught up
naturally by the loop (one dock per elapsed window).

`HabitStructureManager.createHabitStructure()`: initial `completed` = `defaultMade`.
`HabitDateCalculator.shouldTrackHabitOnDate()` is still used by `StructureService` (Today window +
completion table); `calculateNextOccurrence()` is used on habit creation/edit.

---

## KPI Default-Fill (opt-in, per-KPI)

`KPIDefaultFillService.fillMissingDefaults()` — scans **all users'** KPIs directly via
`KPIRepository.findByActiveAndAutoFillEnabled(true, true)` (no `SecurityUtils`/request context on
a cron thread, same reasoning as `HabitUpdateService.updateAllHabits()` using `MongoTemplate.findAll`
instead of a userId-scoped service call). For each candidate KPI, calls
`KPIService.fillDefaultIfMissing(kpi, yesterday)`.

`KPIService.fillDefaultIfMissing()`:
- No-op if `KPI.autoFillEnabled != true` or `KPI.defaultValue == null`.
- No-op if a data point already exists for that date (manual entry or a prior auto-fill — never overwrites).
- Otherwise writes `KPIData{value = defaultValue, autoFilled = true}` into that KPI's own
  id-keyed collection (`KPICollectionNameUtil.toCollectionName(kpi.getId())`) — isolation is
  structural (one Mongo collection per KPI id), not an extra userId check.

Target date is always **yesterday** (`LocalDate.now().minusDays(1)`) — the day that just closed,
mirroring when the habit engine finalizes occurrences. A manual `KPIService.addKPIData()` call
always sets `autoFilled = false`, so backfilling a day by hand clears the flag even if the cron
already filled it.

To change the default value or opt in/out per KPI: `POST /api/kpis/create` (creation) or
`PUT /api/kpis/{name}/default-fill` (post-creation) — both validate `defaultValue` is required
whenever `autoFillEnabled = true`.

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Cron schedule | `UpdateScheduler.scheduledUpdate()` cron expression | `0 5 0 * * ?` = 00:05 server time |
| Startup run behavior | `UpdateScheduler.runOnStartup()` | fires on every container restart |
| Idempotency collection | `LastRunDateService` — collection `last_run_date` | grows unbounded, no cleanup implemented |
| Grace-window / streak logic | `HabitUpdateService.processHabit()` | window model + streak transitions live here |
| Occurrence completion semantics | `HabitUpdateService.processHabit()` + `StructureService.isOccurrenceComplete()` | must stay in sync (normal = any completed; defaultMade = no relapse) |
| Initial `completed` value on structure creation | `HabitStructureManager.createHabitStructure()` | currently = `defaultMade` |
| Negative-streak color (period-aware) | `static/index.html` `applyStreakColor()` | `severity = |streak|*frequency/30` |
| KPI default-fill target date | `KPIDefaultFillService.fillMissingDefaults()` | currently `LocalDate.now().minusDays(1)` |
| KPI default-fill opt-in/value | `KPI.autoFillEnabled` / `KPI.defaultValue`, set via `KPIController` create/`default-fill` endpoints | off by default, per-KPI, requires `defaultValue` when enabled |

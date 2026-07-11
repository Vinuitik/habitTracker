# Daily Cron Flows

Files: `UpdateScheduler.java`, `LastRunDateService.java`, `HabitUpdateService.java`, `HabitDateCalculator.java`, `HabitStructureManager.java`

## Triggers

| Event | Method | Schedule |
|---|---|---|
| App startup | `UpdateScheduler.runOnStartup()` | `@PostConstruct` — fires every container restart |
| Nightly | `UpdateScheduler.scheduledUpdate()` | `@Scheduled` cron `0 5 0 * * ?` (00:05 server time) |

Both call `UpdateScheduler.performDailyUpdate()`.
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

# Daily Cron Flows

Files: `UpdateScheduler.java`, `LastRunDateService.java`, `HabitUpdateService.java`, `HabitDateCalculator.java`, `HabitStructureManager.java`, `StreakCalculationService.java`

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

## Habit Update

`HabitUpdateService.updateAllHabits()`

1. Load all habits from `habits` collection
2. Load today's existing `HabitStructure` docs → build set of already-processed habit IDs (skip duplicates)
3. For each remaining habit: `HabitUpdateService.processHabit(habit, today)`
   - `active=false` or `endDate` passed → skip
   - `curDate == today` → `HabitStructureManager.createHabitStructure(id, today, defaultMade, userId)`
   - `curDate < today` → `HabitDateCalculator.calculateNextOccurrence()` → update `curDate` in DB; if new date == today → create structure
   - `curDate > today` → skip (habit not due yet)

`HabitDateCalculator.calculateNextOccurrence()`:
- Next scheduled day = today if `(today - startDate) % frequency == 0`, else next aligned future day
- To change scheduling math: this method only

`HabitStructureManager.createHabitStructure()`:
- Initial `completed` value = `defaultMade` (true = assumed done, false = assumed not done)

---

## Streak Calculation

`StreakCalculationService.updateAllStreaks(previousRunDate)`

Window processed: `[previousRunDate, today)` — today excluded (user may still complete habits today).

1. Fetch all active habits
2. Fetch `HabitStructure` docs in window for all habit IDs: `StreakCalculationService.fetchHabitStructures()`
3. For each habit: `StreakCalculationService.updateHabitStreak()`

**Fast path** (no structures found in window — habit had no activity):
- Count scheduled days via `StreakCalculationService.countScheduledDays()`
- `defaultMade=true` → `streak += delta`
- `defaultMade=false`, prior streak positive → `streak = 1 - delta`
- `defaultMade=false`, prior streak ≤ 0 → `streak -= delta`

**Slow path** (structures exist — iterate day by day):
- `HabitDateCalculator.shouldTrackHabitOnDate()` → skip non-scheduled days

| Structure? | `completed` | Prior streak | Result |
|---|---|---|---|
| yes | true | > 0 | `streak++` |
| yes | true | ≤ 0 | save `lastNegativeStreak`, `streak = 1` |
| no (inferred) | — | any (`defaultMade=true`) | `streak++` |
| yes or no | false | > 0 | `streak = 0`, clear `lastNegativeStreak` |
| yes or no | false | ≤ 0 | `streak--` |

4. Write `streak`, `longestStreak`, and optionally `lastNegativeStreak` back to `Habit` doc via `MongoTemplate.updateFirst()`

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Cron schedule | `UpdateScheduler.scheduledUpdate()` cron expression | `0 5 0 * * ?` = 00:05 server time |
| Startup run behavior | `UpdateScheduler.runOnStartup()` | fires on every container restart |
| Idempotency collection | `LastRunDateService` — collection `last_run_date` | grows unbounded, no cleanup implemented |
| Scheduling math (which days a habit is due) | `HabitDateCalculator.calculateNextOccurrence()`, `HabitDateCalculator.shouldTrackHabitOnDate()` | two callers: `HabitUpdateService` and `StreakCalculationService` |
| Initial `completed` value on structure creation | `HabitStructureManager.createHabitStructure()` | currently = `defaultMade` |
| Streak logic for explicit completions | `StreakCalculationService.updateHabitStreak()` slow path | |
| Streak logic for missed days (bulk) | `StreakCalculationService.updateHabitStreak()` fast path + `countScheduledDays()` | |

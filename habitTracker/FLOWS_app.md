# Core App Flows

Files: `HabitReadController.java`, `HabitWriteController.java`, `HabitService.java`, `StructureService.java`, `RuleService.java`, `HabitStructureRepository.java`

---

## Today View (render)

1. User GET `/` or `/habit`
2. `HabitReadController.getMethodName()` → `StructureService.getTodayStructure()`
3. `StructureService.getStructureForDate(today)`
   - `SecurityUtils.getCurrentUserId()` → scopes query to current user
   - `HabitStructureRepository.findByStructureDateAndUserId(today, userId)`
   - Fetches matching `Habit` docs → filters out `active=false` habits
4. `StructureService.filterFailedNegativeHabits()`
   - Removes habits where `defaultMade=true` AND `completed=false` (shame filter: failed negative habits vanish on refresh)
   - To remove this behavior: `StructureService.filterFailedNegativeHabits()`
5. `StructureDTO` → Thymeleaf `index.html`

Cache-control headers set to `no-cache` on every response — prevents stale checkbox state on back-navigation.

---

## Habit Completion (mark done / undone)

1. User clicks checkbox
2. JS POST `/habits/update/{habitId}?completed=true/false&date=YYYY-MM-DD`
3. `HabitWriteController.updateHabit()` → `StructureService.updateHabitCompletion(habitId, completed, date)`
4. Find or create `HabitStructure` for `(habitId, date)` → set `completed` → `HabitStructureRepository.save()`
5. If `completed=false` AND date == today:
   - `HabitService.restoreNegativeStreak(habitId)` → writes `lastNegativeStreak` back to `streak`, clears `lastNegativeStreak`
6. `RuleService.getRulesByMainId(habitId)` → for each linked sub-habit → recurse `updateHabitCompletion()` with same value
   - Guard: skip `subId == null` or `subId == habitId`

---

## Rules (habit chaining)

- A rule links one main habit to one or more sub-habits
- Creating a rule: POST `/habits/addRule` with `UpdateDTO` → `HabitWriteController.addRule()`
  - `RuleService.addRule()` → writes to `rules` collection
  - `HabitService.updateRule()` → sub-habits set `active=false`, main habit set `active=true`; streaks and frequency aligned across all
- Cascade on completion: when main habit changes state, all sub-habits follow (Habit Completion step 6)
- Stored in `rules` collection; queried by `RuleService.getRulesByMainId(habitId)`

---

## Habit Creation

**Form (browser):** POST `/new-habit`
1. `HabitWriteController.processHabitForm()` → sets `curDate=startDate`, `active=true`, `streak=0`, `longestStreak=0`
2. `HabitService.saveHabit()`:
   - Defaults `defaultMade=false` if null
   - `SecurityUtils.getCurrentUserId()` → stamps `userId`
   - Saves `Habit` to `habits` collection
   - Creates initial `HabitStructure` for `startDate` with `completed=false`

**API:** POST `/habits/custom-add` → `HabitWriteController.addHabitCustom()` → same path.

---

## Habit Edit / Deactivation

1. GET `/habits/edit/{id}` → `HabitReadController.showEditForm()` → `edit-habit.html`
2. POST `/habits/edit/{id}` → `HabitWriteController.updateHabit()` → `HabitService.updateHabit(id, updatedHabit)`
   - Patches only non-null fields: name, frequency, startDate, defaultMade
   - `endDate` always overwritten (allows clearing it)
3. If `active` toggled **off**: deletes today's `HabitStructure` for that habit
4. If `active` toggled **on** (was previously off):
   - Inherits streak from highest-streak main habit in linked rules (`RuleService.getMainIdsBySubId()`)
   - Creates today's `HabitStructure` if missing
   - Clears all rules where this is a sub-habit (`RuleService.deleteBySubId()`)
5. "Delete" = soft-delete: `HabitService.deleteHabit()` sets `active=false` — data never removed from MongoDB

---

## Habit Table View

1. GET `/habits/table?startDate=&endDate=` (defaults: last 7 days)
2. `HabitReadController.getHabitTable()` → `HabitService.getAllUniqueHabitNamesIds()` (active habits only)
3. `StructureService.getStructuresForDateRange(startDate, endDate, habitNames)`
   - Initializes one `StructureDTO` per day in range
   - Fetches `HabitStructure` docs (±1 day buffer)
   - Fills missing entries as `completed=false`
   - Per cell: `StructureService.populateHabitStatuses()` → `ACTIVE_COMPLETED`, `ACTIVE_INCOMPLETE`, or `INACTIVE`
     - Inactive = outside habit's `startDate`/`endDate` or frequency not aligned to that day
     - Alignment check: `StructureService.isHabitActiveOnDate()` — note: duplicates logic in `HabitDateCalculator`, two separate implementations exist
4. Thymeleaf `habit-table.html`

Async variant: GET `/habits/tableAsync` → `HabitReadController.getHabitTableData()` returns same data as JSON.

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Shame filter (hide failed negative habits) | `StructureService.filterFailedNegativeHabits()` | remove or invert the `defaultMade=true && completed=false` filter |
| User data scoping | `StructureService.getStructureForDate()`, `StructureService.fetchHabitStructures()` | falls back to unscoped if `userId` null |
| Cascade completion to sub-habits | `StructureService.updateHabitCompletion()` → `RuleService.getRulesByMainId()` | recursive |
| Negative streak restore on uncheck | `HabitService.restoreNegativeStreak()` | only fires for today's date |
| Fields synced when rule is created | `HabitService.updateRule()` | currently: frequency, streak, active |
| Soft-delete behavior | `HabitService.deleteHabit()` | sets `active=false`; no hard delete |
| Scheduling alignment check (table) | `StructureService.isHabitActiveOnDate()` | duplicates `HabitDateCalculator` — keep in sync |
| Default date range on table view | `HabitReadController.getHabitTable()` | defaults to `now() - 7 days` |

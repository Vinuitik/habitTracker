# Graph Report - HabitTracker  (2026-04-27)

## Corpus Check
- 58 files · ~23,353 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 419 nodes · 692 edges · 38 communities detected
- Extraction: 70% EXTRACTED · 30% INFERRED · 0% AMBIGUOUS · INFERRED: 206 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 42|Community 42]]

## God Nodes (most connected - your core abstractions)
1. `HabitService` - 21 edges
2. `StreakCalculationServiceUnitTest` - 21 edges
3. `StructureService` - 15 edges
4. `KPIService` - 14 edges
5. `KPICollectionNameUtilTest` - 14 edges
6. `DynamicKPIDataRepositoryTest` - 13 edges
7. `DynamicKPIDataRepository` - 12 edges
8. `KPIServiceTest` - 12 edges
9. `HabitReadController` - 11 edges
10. `StreakCalculationServiceIntegrationTest` - 11 edges

## Surprising Connections (you probably didn't know these)
- `Habit Entity (Domain Model)` --references--> `2-Minute Rule (Habit Info Field)`  [INFERRED]
  CLAUDE.md → habitTracker/src/main/resources/templates/info.html
- `Habit Entity (Domain Model)` --references--> `Habit Status (Priority / Maintaining / Abandoned)`  [INFERRED]
  CLAUDE.md → habitTracker/src/main/resources/templates/info.html
- `HabitTracker CLAUDE.md - Project Instructions` --conceptually_related_to--> `HabitTracker README - Comprehensive Documentation`  [EXTRACTED]
  CLAUDE.md → README.md
- `Habit Entity (Domain Model)` --shares_data_with--> `KPI-to-Habit Linking (Cross-Feature Association)`  [EXTRACTED]
  CLAUDE.md → habitTracker/src/main/resources/templates/kpi-create.html
- `HabitStructure Entity (Daily Completion Record)` --shares_data_with--> `HabitStatus Enum (ACTIVE_INCOMPLETE / INACTIVE) in Table View`  [INFERRED]
  CLAUDE.md → habitTracker/src/main/resources/templates/habit-table.html

## Hyperedges (group relationships)
- **All Thymeleaf Templates Share Navigation Bar Pattern** — template_index, template_edit_habit, template_habit_table, template_habits_list, template_info, template_kpi_create, template_kpi_dashboard, template_kpi_list, template_rule_setting, template_new_habit [EXTRACTED 0.95]
- **PWA Icon Asset Set (All Sizes)** — icon_android_192, icon_android_512, icon_apple_touch, icon_favicon_16, icon_favicon_32 [EXTRACTED 1.00]
- **Updater Service Classes (Single Responsibility Refactor)** — concept_cron_scheduler, concept_idempotency, concept_streak_calculation, concept_habit_date_calculator, concept_habit_structure [EXTRACTED 0.95]
- **KPI Feature UI Components** — template_kpi_create, template_kpi_dashboard, template_kpi_list, concept_kpi, concept_chartjs [INFERRED 0.90]

## Communities

### Community 0 - "Community 0"
Cohesion: 0.08
Nodes (5): KPICollectionNameUtil, KPICollectionNameUtilTest, KPIHabitMappingRepository, KPIService, KPIServiceTest

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (3): DynamicKPIDataRepository, DynamicKPIDataRepositoryTest, HabitCompletionSystemTest

### Community 2 - "Community 2"
Cohesion: 0.18
Nodes (2): StreakCalculationServiceIntegrationTest, StreakCalculationServiceUnitTest

### Community 3 - "Community 3"
Cohesion: 0.07
Nodes (3): HabitDTO, HabitReadController, HabitService

### Community 4 - "Community 4"
Cohesion: 0.07
Nodes (32): Backup Service Python Requirements, HabitTracker CLAUDE.md - Project Instructions, Python Backup Service (Google Drive), Chart.js Library (KPI Visualizations), Daily Cron Update Scheduler (12:05 AM), defaultMade Flag (Catch-up Completion Default), 5-Container Docker Stack, Google Drive API (Backup Integration) (+24 more)

### Community 5 - "Community 5"
Cohesion: 0.15
Nodes (2): HabitStructureRepository, StructureService

### Community 6 - "Community 6"
Cohesion: 0.11
Nodes (5): HabitDateCalculator, HabitStructureManager, HabitUpdateService, LastRunDateService, UpdateScheduler

### Community 7 - "Community 7"
Cohesion: 0.11
Nodes (3): HabitWriteController, RuleRepository, RuleService

### Community 8 - "Community 8"
Cohesion: 0.17
Nodes (19): closeModal(), getColorIntensity(), getTrendClass(), getTrendColor(), getTrendText(), handleFormSubmit(), initializeDashboard(), initializeModal() (+11 more)

### Community 9 - "Community 9"
Cohesion: 0.22
Nodes (2): HabitDateCalculatorTest, StreakCalculationService

### Community 10 - "Community 10"
Cohesion: 0.15
Nodes (2): KPIController, KPIRepository

### Community 11 - "Community 11"
Cohesion: 0.22
Nodes (1): HabitRepository

### Community 12 - "Community 12"
Cohesion: 0.33
Nodes (5): closeModal(), deleteKPI(), handleFormSubmit(), initializeModal(), showMessage()

### Community 13 - "Community 13"
Cohesion: 0.38
Nodes (4): showError(), showFieldError(), validateForm(), validateKPIName()

### Community 14 - "Community 14"
Cohesion: 0.43
Nodes (5): gcdArray(), getSelectedFrequencies(), getSelectedHabitsInfo(), saveRule(), updateFrequencyResult()

### Community 15 - "Community 15"
Cohesion: 0.4
Nodes (2): removeHabitWithShameAnimation(), updateHabitStatus()

### Community 16 - "Community 16"
Cohesion: 0.4
Nodes (2): getHabitOrderFromHeaders(), updateTable()

### Community 17 - "Community 17"
Cohesion: 0.33
Nodes (6): PWA / Web App Icons Asset Set, Android Chrome App Icon 192x192, Android Chrome App Icon 512x512, Apple Touch Icon (PWA / Home Screen), Favicon 16x16 PNG, Favicon 32x32 PNG

### Community 18 - "Community 18"
Cohesion: 0.67
Nodes (2): WebConfig, WebMvcConfigurer

### Community 19 - "Community 19"
Cohesion: 0.83
Nodes (3): attachDeleteHandlers(), attachDropdownHandlers(), swapHabitsList()

### Community 20 - "Community 20"
Cohesion: 0.67
Nodes (1): HabitTrackerApplication

### Community 21 - "Community 21"
Cohesion: 0.67
Nodes (1): StructureDTO

### Community 22 - "Community 22"
Cohesion: 0.67
Nodes (1): StructureRepository

### Community 23 - "Community 23"
Cohesion: 0.67
Nodes (1): HabitTrackerApplicationTests

### Community 26 - "Community 26"
Cohesion: 1.0
Nodes (1): Habit

### Community 27 - "Community 27"
Cohesion: 1.0
Nodes (1): KPI

### Community 28 - "Community 28"
Cohesion: 1.0
Nodes (1): KPIData

### Community 29 - "Community 29"
Cohesion: 1.0
Nodes (1): KPIDataDTO

### Community 30 - "Community 30"
Cohesion: 1.0
Nodes (1): KPIDTO

### Community 31 - "Community 31"
Cohesion: 1.0
Nodes (1): KPIHabitMapping

### Community 32 - "Community 32"
Cohesion: 1.0
Nodes (1): Rule

### Community 33 - "Community 33"
Cohesion: 1.0
Nodes (1): RuleDTO

### Community 34 - "Community 34"
Cohesion: 1.0
Nodes (1): UpdateDTO

### Community 35 - "Community 35"
Cohesion: 1.0
Nodes (1): HabitStructure

### Community 36 - "Community 36"
Cohesion: 1.0
Nodes (1): Structure

### Community 37 - "Community 37"
Cohesion: 1.0
Nodes (1): LastRunDate

### Community 38 - "Community 38"
Cohesion: 1.0
Nodes (1): Pair

### Community 42 - "Community 42"
Cohesion: 1.0
Nodes (1): Shared Navigation Bar (6 Links)

## Knowledge Gaps
- **32 isolated node(s):** `Habit`, `KPI`, `KPIData`, `KPIDataDTO`, `KPIDTO` (+27 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 2`** (34 nodes): `StreakCalculationServiceIntegrationTest.java`, `StreakCalculationServiceUnitTest.java`, `.updateAllStreaks()`, `StreakCalculationServiceIntegrationTest`, `.downtime7Days_defaultMadeFalse_streakDecreases7()`, `.downtime7Days_defaultMadeTrue_streakIncreases7_andUpdatesLongest()`, `.downtimeAfterPositiveStreak_streakResetsCorrectly()`, `.explicitCompletion_fromNegative_savesLastNegativeStreak()`, `.explicitCompletion_fromNegative_streakBecomesOne()`, `.inactiveHabit_streakNeverChanges()`, `.multipleHabits_eachUpdatedIndependently()`, `.positiveStreak_missedDay_streakDropsToZero_clearsLastNegativeStreak()`, `.savedDailyHabit()`, `StreakCalculationServiceUnitTest`, `.captureUpdate()`, `.dailyHabit()`, `.fastPath_dailyHabit_7dayGap_defaultMadeTrue_streakIncreases7()`, `.fastPath_dailyHabit_7dayGap_streakDecreases7()`, `.fastPath_negativeStreak_missedMoreDays_continuesDecrementing()`, `.fastPath_positiveStreak_missedDays_resetsToOneMinusDelta()`, `.fastPath_positiveStreak_missedDays_setsLastNegUnset()`, `.fastPath_weeklyHabit_14dayGap_counts2ScheduledDays()`, `.fastPath_weeklyHabit_gapStartsMidCycle_alignsToNextScheduledDay()`, `.lastNegFrom()`, `.lastNegUnset()`, `.setUp()`, `.slowPath_explicitCompletion_fromNegative_savesLastNegativeStreak()`, `.slowPath_explicitCompletion_fromNegative_streakBecomesOne()`, `.slowPath_inactiveHabit_isSkippedEntirely()`, `.slowPath_notCompleted_alreadyNegative_continuesDecrementing()`, `.slowPath_notCompleted_fromPositive_streakDropsToZero_clearsLastNeg()`, `.streakFrom()`, `.stubWith()`, `.weeklyHabit()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 5`** (24 nodes): `.getMethodName()`, `.getHabitsByIds()`, `.restoreNegativeStreak()`, `HabitStructureRepository`, `.findByHabitIdAndStructureDate()`, `.findByStructureDate()`, `.findByStructureDateBetween()`, `HabitStructureRepository.java`, `StructureService.java`, `StructureService`, `.fetchHabitStructures()`, `.fillMissingHabits()`, `.filterFailedNegativeHabits()`, `.getHabitIdToNameMap()`, `.getHabitIdToNameMapFromIds()`, `.getStructureForDate()`, `.getStructuresForDateRange()`, `.getTodayStructure()`, `.initializeStructureMap()`, `.isHabitActiveOnDate()`, `.populateHabitStatuses()`, `.populateStructureDTO()`, `.populateStructureMap()`, `.updateHabitCompletion()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 9`** (18 nodes): `.shouldTrackHabitOnDate()`, `HabitDateCalculatorTest`, `.frequency1_afterEndDate_notTracked()`, `.frequency1_afterStartDate_tracked()`, `.frequency1_beforeStartDate_notTracked()`, `.frequency1_nullStartDate_alwaysTracked()`, `.frequency1_onEndDate_tracked()`, `.frequency7_betweenScheduledDays_notTracked()`, `.frequency7_onScheduledDays_tracked()`, `.frequency7_onStartDate_tracked()`, `.habit()`, `StreakCalculationService.java`, `HabitDateCalculatorTest.java`, `StreakCalculationService`, `.countScheduledDays()`, `.fetchHabitStructures()`, `.StreakCalculationService()`, `.updateHabitStreak()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 10`** (15 nodes): `.findByHabitId()`, `KPIController.java`, `KPIRepository.java`, `KPIController`, `.addKPIData()`, `.createKPI()`, `.deleteKPI()`, `.getKPIsByHabit()`, `.showKPIDashboard()`, `.showKPIList()`, `KPIRepository`, `.findByActive()`, `.findByNameIn()`, `.getAllActiveKPIs()`, `.getKPIsByHabitId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 11`** (9 nodes): `HabitRepository`, `.findByCurDate()`, `.findByEndDateBetween()`, `.findByName()`, `.findByStartDateBetween()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqual()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqualAndFrequency()`, `.getHabitsByDate()`, `HabitRepository.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 15`** (6 nodes): `input.js`, `applyStreakColor()`, `moveHabitToPosition()`, `organizeHabitsOnLoad()`, `removeHabitWithShameAnimation()`, `updateHabitStatus()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 16`** (6 nodes): `fetchUpdatedTable()`, `findHabitKeyByName()`, `getHabitOrderFromHeaders()`, `updateHabitStatus()`, `updateTable()`, `habit-table.js`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 18`** (4 nodes): `WebConfig.java`, `WebConfig`, `.addResourceHandlers()`, `WebMvcConfigurer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 20`** (3 nodes): `HabitTrackerApplication.java`, `HabitTrackerApplication`, `.main()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 21`** (3 nodes): `StructureDTO.java`, `StructureDTO`, `.StructureDTO()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 22`** (3 nodes): `StructureRepository.java`, `StructureRepository`, `.findByDateBetween()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 23`** (3 nodes): `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests`, `.contextLoads()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 26`** (2 nodes): `Habit`, `Habit.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 27`** (2 nodes): `KPI.java`, `KPI`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 28`** (2 nodes): `KPIData.java`, `KPIData`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 29`** (2 nodes): `KPIDataDTO.java`, `KPIDataDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 30`** (2 nodes): `KPIDTO.java`, `KPIDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 31`** (2 nodes): `KPIHabitMapping.java`, `KPIHabitMapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 32`** (2 nodes): `Rule.java`, `Rule`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 33`** (2 nodes): `RuleDTO.java`, `RuleDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 34`** (2 nodes): `UpdateDTO.java`, `UpdateDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 35`** (2 nodes): `HabitStructure`, `HabitStructure.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 36`** (2 nodes): `Structure.java`, `Structure`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 37`** (2 nodes): `LastRunDate.java`, `LastRunDate`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 38`** (2 nodes): `Pair.java`, `Pair`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 42`** (1 nodes): `Shared Navigation Bar (6 Links)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `HabitService` connect `Community 3` to `Community 11`, `Community 5`, `Community 7`?**
  _High betweenness centrality (0.114) - this node is a cross-community bridge._
- **Why does `KPIService` connect `Community 0` to `Community 1`, `Community 10`?**
  _High betweenness centrality (0.036) - this node is a cross-community bridge._
- **What connects `Habit`, `KPI`, `KPIData` to the rest of the system?**
  _32 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.08 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.11 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `Community 4` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
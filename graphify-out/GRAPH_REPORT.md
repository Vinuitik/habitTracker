# Graph Report - .  (2026-04-27)

## Corpus Check
- Corpus is ~23,249 words - fits in a single context window. You may not need a graph.

## Summary
- 430 nodes · 736 edges · 35 communities detected
- Extraction: 72% EXTRACTED · 28% INFERRED · 0% AMBIGUOUS · INFERRED: 208 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Project Docs and Architecture|Project Docs and Architecture]]
- [[_COMMUNITY_Write Controller and Service Methods|Write Controller and Service Methods]]
- [[_COMMUNITY_System Tests and Structure Queries|System Tests and Structure Queries]]
- [[_COMMUNITY_Habit DTO and Read Controller|Habit DTO and Read Controller]]
- [[_COMMUNITY_Streak Calculation and Tests|Streak Calculation and Tests]]
- [[_COMMUNITY_KPI Data Repository|KPI Data Repository]]
- [[_COMMUNITY_Habit Date Calculator and Tests|Habit Date Calculator and Tests]]
- [[_COMMUNITY_KPI Dashboard JavaScript|KPI Dashboard JavaScript]]
- [[_COMMUNITY_KPI Controller and Repository|KPI Controller and Repository]]
- [[_COMMUNITY_Update Scheduler and Idempotency|Update Scheduler and Idempotency]]
- [[_COMMUNITY_KPI List JavaScript|KPI List JavaScript]]
- [[_COMMUNITY_KPI Habit Mapping Repository|KPI Habit Mapping Repository]]
- [[_COMMUNITY_KPI Create Form Validation|KPI Create Form Validation]]
- [[_COMMUNITY_Rule Frequency Calculator JS|Rule Frequency Calculator JS]]
- [[_COMMUNITY_Habit Input and Streak UI|Habit Input and Streak UI]]
- [[_COMMUNITY_Habit Table Update JS|Habit Table Update JS]]
- [[_COMMUNITY_Web MVC Configuration|Web MVC Configuration]]
- [[_COMMUNITY_Habits List JavaScript|Habits List JavaScript]]
- [[_COMMUNITY_Spring Boot Application Entry|Spring Boot Application Entry]]
- [[_COMMUNITY_Structure DTO|Structure DTO]]
- [[_COMMUNITY_Structure Repository|Structure Repository]]
- [[_COMMUNITY_Application Context Tests|Application Context Tests]]
- [[_COMMUNITY_Habit Entity|Habit Entity]]
- [[_COMMUNITY_KPI Entity|KPI Entity]]
- [[_COMMUNITY_KPI Data Entity|KPI Data Entity]]
- [[_COMMUNITY_KPI Data DTO|KPI Data DTO]]
- [[_COMMUNITY_KPI DTO|KPI DTO]]
- [[_COMMUNITY_KPI Habit Mapping Entity|KPI Habit Mapping Entity]]
- [[_COMMUNITY_Rule Entity|Rule Entity]]
- [[_COMMUNITY_Rule DTO|Rule DTO]]
- [[_COMMUNITY_Update DTO|Update DTO]]
- [[_COMMUNITY_Habit Structure Entity|Habit Structure Entity]]
- [[_COMMUNITY_Structure Entity|Structure Entity]]
- [[_COMMUNITY_Last Run Date Entity|Last Run Date Entity]]
- [[_COMMUNITY_Utility Pair Class|Utility Pair Class]]

## God Nodes (most connected - your core abstractions)
1. `HabitService` - 21 edges
2. `StreakCalculationServiceUnitTest` - 21 edges
3. `StructureService` - 15 edges
4. `KPIService` - 14 edges
5. `KPICollectionNameUtilTest` - 14 edges
6. `Habit Entity (Domain Model)` - 14 edges
7. `DynamicKPIDataRepositoryTest` - 13 edges
8. `DynamicKPIDataRepository` - 12 edges
9. `KPIServiceTest` - 12 edges
10. `HabitReadController` - 11 edges

## Surprising Connections (you probably didn't know these)
- `2-Minute Rule (Habit Info Field)` --references--> `Habit Entity (Domain Model)`  [INFERRED]
  habitTracker/src/main/resources/templates/info.html → CLAUDE.md
- `Habit Status (Priority / Maintaining / Abandoned)` --references--> `Habit Entity (Domain Model)`  [INFERRED]
  habitTracker/src/main/resources/templates/info.html → CLAUDE.md
- `HabitStatus Enum (ACTIVE_INCOMPLETE / INACTIVE) in Table View` --shares_data_with--> `HabitStructure Entity (Daily Completion Record)`  [INFERRED]
  habitTracker/src/main/resources/templates/habit-table.html → CLAUDE.md
- `Chart.js Library (KPI Visualizations)` --references--> `KPI (Key Performance Indicator) Feature`  [INFERRED]
  habitTracker/src/main/resources/templates/kpi-dashboard.html → CLAUDE.md
- `HabitTracker README - Comprehensive Documentation` --conceptually_related_to--> `HabitTracker CLAUDE.md - Project Instructions`  [EXTRACTED]
  README.md → CLAUDE.md

## Hyperedges (group relationships)
- **All Thymeleaf Templates Share Navigation Bar Pattern** — template_index, template_edit_habit, template_habit_table, template_habits_list, template_info, template_kpi_create, template_kpi_dashboard, template_kpi_list, template_rule_setting, template_new_habit [EXTRACTED 0.95]
- **PWA Icon Asset Set (All Sizes)** — icon_android_192, icon_android_512, icon_apple_touch, icon_favicon_16, icon_favicon_32 [EXTRACTED 1.00]
- **Updater Service Classes (Single Responsibility Refactor)** — concept_cron_scheduler, concept_idempotency, concept_streak_calculation, concept_habit_date_calculator, concept_habit_structure [EXTRACTED 0.95]
- **KPI Feature UI Components** — template_kpi_create, template_kpi_dashboard, template_kpi_list, concept_kpi, concept_chartjs [INFERRED 0.90]

## Communities

### Community 0 - "Project Docs and Architecture"
Cohesion: 0.07
Nodes (50): Backup Service Python Requirements, HabitTracker CLAUDE.md - Project Instructions, Python Backup Service (Google Drive), Chart.js Library (KPI Visualizations), Daily Cron Update Scheduler (12:05 AM), defaultMade Flag (Catch-up Completion Default), 5-Container Docker Stack, Google Drive API (Backup Integration) (+42 more)

### Community 1 - "Write Controller and Service Methods"
Cohesion: 0.07
Nodes (5): HabitStructureRepository, HabitWriteController, RuleRepository, RuleService, StructureService

### Community 2 - "System Tests and Structure Queries"
Cohesion: 0.08
Nodes (4): HabitCompletionSystemTest, KPICollectionNameUtil, KPICollectionNameUtilTest, KPIService

### Community 3 - "Habit DTO and Read Controller"
Cohesion: 0.06
Nodes (4): HabitDTO, HabitReadController, HabitRepository, HabitService

### Community 4 - "Streak Calculation and Tests"
Cohesion: 0.14
Nodes (3): StreakCalculationService, StreakCalculationServiceIntegrationTest, StreakCalculationServiceUnitTest

### Community 5 - "KPI Data Repository"
Cohesion: 0.14
Nodes (3): DynamicKPIDataRepository, DynamicKPIDataRepositoryTest, KPIServiceTest

### Community 6 - "Habit Date Calculator and Tests"
Cohesion: 0.14
Nodes (4): HabitDateCalculator, HabitDateCalculatorTest, HabitStructureManager, HabitUpdateService

### Community 7 - "KPI Dashboard JavaScript"
Cohesion: 0.17
Nodes (19): closeModal(), getColorIntensity(), getTrendClass(), getTrendColor(), getTrendText(), handleFormSubmit(), initializeDashboard(), initializeModal() (+11 more)

### Community 8 - "KPI Controller and Repository"
Cohesion: 0.15
Nodes (2): KPIController, KPIRepository

### Community 9 - "Update Scheduler and Idempotency"
Cohesion: 0.24
Nodes (2): LastRunDateService, UpdateScheduler

### Community 10 - "KPI List JavaScript"
Cohesion: 0.33
Nodes (5): closeModal(), deleteKPI(), handleFormSubmit(), initializeModal(), showMessage()

### Community 11 - "KPI Habit Mapping Repository"
Cohesion: 0.29
Nodes (1): KPIHabitMappingRepository

### Community 12 - "KPI Create Form Validation"
Cohesion: 0.38
Nodes (4): showError(), showFieldError(), validateForm(), validateKPIName()

### Community 13 - "Rule Frequency Calculator JS"
Cohesion: 0.43
Nodes (5): gcdArray(), getSelectedFrequencies(), getSelectedHabitsInfo(), saveRule(), updateFrequencyResult()

### Community 14 - "Habit Input and Streak UI"
Cohesion: 0.4
Nodes (2): removeHabitWithShameAnimation(), updateHabitStatus()

### Community 15 - "Habit Table Update JS"
Cohesion: 0.4
Nodes (2): getHabitOrderFromHeaders(), updateTable()

### Community 16 - "Web MVC Configuration"
Cohesion: 0.67
Nodes (2): WebConfig, WebMvcConfigurer

### Community 17 - "Habits List JavaScript"
Cohesion: 0.83
Nodes (3): attachDeleteHandlers(), attachDropdownHandlers(), swapHabitsList()

### Community 18 - "Spring Boot Application Entry"
Cohesion: 0.67
Nodes (1): HabitTrackerApplication

### Community 19 - "Structure DTO"
Cohesion: 0.67
Nodes (1): StructureDTO

### Community 20 - "Structure Repository"
Cohesion: 0.67
Nodes (1): StructureRepository

### Community 21 - "Application Context Tests"
Cohesion: 0.67
Nodes (1): HabitTrackerApplicationTests

### Community 24 - "Habit Entity"
Cohesion: 1.0
Nodes (1): Habit

### Community 25 - "KPI Entity"
Cohesion: 1.0
Nodes (1): KPI

### Community 26 - "KPI Data Entity"
Cohesion: 1.0
Nodes (1): KPIData

### Community 27 - "KPI Data DTO"
Cohesion: 1.0
Nodes (1): KPIDataDTO

### Community 28 - "KPI DTO"
Cohesion: 1.0
Nodes (1): KPIDTO

### Community 29 - "KPI Habit Mapping Entity"
Cohesion: 1.0
Nodes (1): KPIHabitMapping

### Community 30 - "Rule Entity"
Cohesion: 1.0
Nodes (1): Rule

### Community 31 - "Rule DTO"
Cohesion: 1.0
Nodes (1): RuleDTO

### Community 32 - "Update DTO"
Cohesion: 1.0
Nodes (1): UpdateDTO

### Community 33 - "Habit Structure Entity"
Cohesion: 1.0
Nodes (1): HabitStructure

### Community 34 - "Structure Entity"
Cohesion: 1.0
Nodes (1): Structure

### Community 35 - "Last Run Date Entity"
Cohesion: 1.0
Nodes (1): LastRunDate

### Community 36 - "Utility Pair Class"
Cohesion: 1.0
Nodes (1): Pair

## Knowledge Gaps
- **26 isolated node(s):** `Habit`, `KPI`, `KPIData`, `KPIDataDTO`, `KPIDTO` (+21 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `KPI Controller and Repository`** (15 nodes): `.findByHabitId()`, `KPIController.java`, `KPIRepository.java`, `KPIController`, `.addKPIData()`, `.createKPI()`, `.deleteKPI()`, `.getKPIsByHabit()`, `.showKPIDashboard()`, `.showKPIList()`, `KPIRepository`, `.findByActive()`, `.findByNameIn()`, `.getAllActiveKPIs()`, `.getKPIsByHabitId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Update Scheduler and Idempotency`** (12 nodes): `LastRunDateService.java`, `UpdateScheduler.java`, `LastRunDateService`, `.getLastRunDate()`, `.hasRunToday()`, `.LastRunDateService()`, `.markRunToday()`, `UpdateScheduler`, `.performDailyUpdate()`, `.runOnStartup()`, `.scheduledUpdate()`, `.UpdateScheduler()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Habit Mapping Repository`** (7 nodes): `KPIHabitMappingRepository.java`, `KPIHabitMappingRepository`, `.deleteByHabitId()`, `.deleteByKpiNameAndHabitId()`, `.findByHabitId()`, `.findByKpiName()`, `.convertToDTO()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Input and Streak UI`** (6 nodes): `input.js`, `applyStreakColor()`, `moveHabitToPosition()`, `organizeHabitsOnLoad()`, `removeHabitWithShameAnimation()`, `updateHabitStatus()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Table Update JS`** (6 nodes): `fetchUpdatedTable()`, `findHabitKeyByName()`, `getHabitOrderFromHeaders()`, `updateHabitStatus()`, `updateTable()`, `habit-table.js`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Web MVC Configuration`** (4 nodes): `WebConfig.java`, `WebConfig`, `.addResourceHandlers()`, `WebMvcConfigurer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Spring Boot Application Entry`** (3 nodes): `HabitTrackerApplication.java`, `HabitTrackerApplication`, `.main()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure DTO`** (3 nodes): `StructureDTO.java`, `StructureDTO`, `.StructureDTO()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Repository`** (3 nodes): `StructureRepository.java`, `StructureRepository`, `.findByDateBetween()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Application Context Tests`** (3 nodes): `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests`, `.contextLoads()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Entity`** (2 nodes): `Habit`, `Habit.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Entity`** (2 nodes): `KPI.java`, `KPI`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Data Entity`** (2 nodes): `KPIData.java`, `KPIData`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Data DTO`** (2 nodes): `KPIDataDTO.java`, `KPIDataDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI DTO`** (2 nodes): `KPIDTO.java`, `KPIDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Habit Mapping Entity`** (2 nodes): `KPIHabitMapping.java`, `KPIHabitMapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Rule Entity`** (2 nodes): `Rule.java`, `Rule`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Rule DTO`** (2 nodes): `RuleDTO.java`, `RuleDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Update DTO`** (2 nodes): `UpdateDTO.java`, `UpdateDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Structure Entity`** (2 nodes): `HabitStructure`, `HabitStructure.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Entity`** (2 nodes): `Structure.java`, `Structure`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Last Run Date Entity`** (2 nodes): `LastRunDate.java`, `LastRunDate`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Utility Pair Class`** (2 nodes): `Pair.java`, `Pair`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `HabitService` connect `Habit DTO and Read Controller` to `Write Controller and Service Methods`?**
  _High betweenness centrality (0.108) - this node is a cross-community bridge._
- **Why does `KPIService` connect `System Tests and Structure Queries` to `KPI Controller and Repository`, `KPI Habit Mapping Repository`, `KPI Data Repository`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **What connects `Habit`, `KPI`, `KPIData` to the rest of the system?**
  _26 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Project Docs and Architecture` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `Write Controller and Service Methods` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `System Tests and Structure Queries` be split into smaller, more focused modules?**
  _Cohesion score 0.08 - nodes in this community are weakly interconnected._
- **Should `Habit DTO and Read Controller` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
# Graph Report - .  (2026-05-03)

## Corpus Check
- Corpus is ~26,296 words - fits in a single context window. You may not need a graph.

## Summary
- 637 nodes · 1071 edges · 54 communities detected
- Extraction: 72% EXTRACTED · 28% INFERRED · 0% AMBIGUOUS · INFERRED: 300 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Core Domain + Habit UI|Core Domain + Habit UI]]
- [[_COMMUNITY_Auth API (JWT)|Auth API (JWT)]]
- [[_COMMUNITY_Habit Service Layer|Habit Service Layer]]
- [[_COMMUNITY_KPI Service + Collection Naming|KPI Service + Collection Naming]]
- [[_COMMUNITY_KPI Data Repository + Tests|KPI Data Repository + Tests]]
- [[_COMMUNITY_Streak Calculation Tests|Streak Calculation Tests]]
- [[_COMMUNITY_Structure Service + Read Controller|Structure Service + Read Controller]]
- [[_COMMUNITY_Daily Updater Pipeline|Daily Updater Pipeline]]
- [[_COMMUNITY_KPI Dashboard JS|KPI Dashboard JS]]
- [[_COMMUNITY_Streak + Date Calculator|Streak + Date Calculator]]
- [[_COMMUNITY_KPI Controller + Repository|KPI Controller + Repository]]
- [[_COMMUNITY_Infrastructure Config|Infrastructure Config]]
- [[_COMMUNITY_Rule Service + Repository|Rule Service + Repository]]
- [[_COMMUNITY_KPI List JS|KPI List JS]]
- [[_COMMUNITY_Habit Repository|Habit Repository]]
- [[_COMMUNITY_Rule Setting JS|Rule Setting JS]]
- [[_COMMUNITY_Input + Streak Color JS|Input + Streak Color JS]]
- [[_COMMUNITY_KPI Create Form JS|KPI Create Form JS]]
- [[_COMMUNITY_Habit Table JS|Habit Table JS]]
- [[_COMMUNITY_PWA Icons (small)|PWA Icons (small)]]
- [[_COMMUNITY_KPI-Habit Mapping Repository|KPI-Habit Mapping Repository]]
- [[_COMMUNITY_Habits List JS|Habits List JS]]
- [[_COMMUNITY_Web MVC Config|Web MVC Config]]
- [[_COMMUNITY_PWA Brand + Icons (large)|PWA Brand + Icons (large)]]
- [[_COMMUNITY_Spring Boot Entry Point|Spring Boot Entry Point]]
- [[_COMMUNITY_Structure DTO|Structure DTO]]
- [[_COMMUNITY_Structure Repository|Structure Repository]]
- [[_COMMUNITY_Application Tests|Application Tests]]
- [[_COMMUNITY_Docker Compose Runner|Docker Compose Runner]]
- [[_COMMUNITY_Python Backup Script|Python Backup Script]]
- [[_COMMUNITY_User Entity|User Entity]]
- [[_COMMUNITY_Habit Entity|Habit Entity]]
- [[_COMMUNITY_KPI Entity|KPI Entity]]
- [[_COMMUNITY_KPI Data Entity|KPI Data Entity]]
- [[_COMMUNITY_KPI Data DTO|KPI Data DTO]]
- [[_COMMUNITY_KPI DTO|KPI DTO]]
- [[_COMMUNITY_KPI-Habit Mapping Entity|KPI-Habit Mapping Entity]]
- [[_COMMUNITY_Rule Entity|Rule Entity]]
- [[_COMMUNITY_Rule DTO|Rule DTO]]
- [[_COMMUNITY_Update DTO|Update DTO]]
- [[_COMMUNITY_HabitStructure Entity|HabitStructure Entity]]
- [[_COMMUNITY_Structure Entity|Structure Entity]]
- [[_COMMUNITY_LastRunDate Entity|LastRunDate Entity]]
- [[_COMMUNITY_Pair Utility|Pair Utility]]
- [[_COMMUNITY_New Habit Form JS|New Habit Form JS]]
- [[_COMMUNITY_Edit Habit Form JS|Edit Habit Form JS]]
- [[_COMMUNITY_Habit Info JS|Habit Info JS]]
- [[_COMMUNITY_PWA Color Palette|PWA Color Palette]]
- [[_COMMUNITY_Backup Requirements|Backup Requirements]]
- [[_COMMUNITY_README|README]]
- [[_COMMUNITY_TLSHTTPS Config|TLS/HTTPS Config]]
- [[_COMMUNITY_Timezone Handling|Timezone Handling]]
- [[_COMMUNITY_Spring Boot Docs|Spring Boot Docs]]
- [[_COMMUNITY_Web Config (CORS)|Web Config (CORS)]]

## God Nodes (most connected - your core abstractions)
1. `HabitService` - 22 edges
2. `StreakCalculationServiceUnitTest` - 22 edges
3. `UserPrincipal` - 20 edges
4. `StructureService` - 16 edges
5. `KPIService` - 15 edges
6. `KPICollectionNameUtilTest` - 15 edges
7. `DynamicKPIDataRepositoryTest` - 14 edges
8. `Habit Entity` - 14 edges
9. `DynamicKPIDataRepository` - 13 edges
10. `KPIServiceTest` - 13 edges

## Surprising Connections (you probably didn't know these)
- `Updater Service Refactor Rationale` --rationale_for--> `UpdateScheduler`  [EXTRACTED]
  README.md → CLAUDE.md
- `API Endpoints Definition` --references--> `HabitReadController`  [INFERRED]
  README.md → CLAUDE.md
- `API Endpoints Definition` --references--> `HabitWriteController`  [INFERRED]
  README.md → CLAUDE.md
- `Step 11: Fix HabitCompletionSystemTest regression` --references--> `HabitWriteController`  [INFERRED]
  TODOS.md → CLAUDE.md
- `register.html (Create Account Page)` --references--> `Authentication and Multi-User Support (Planned)`  [EXTRACTED]
  habitTracker/src/main/resources/templates/register.html → TODOS.md

## Hyperedges (group relationships)
- **Auth Implementation Steps (TODOS)** — todos_userid_fields, todos_user_repository, todos_user_document, todos_userprincipal, todos_securityconfig, todos_jwtutil, todos_jwtauthfilter, todos_apiauthcontroller, todos_service_userid_filter, todos_csrf_fix, todos_test_regression_fix, todos_env_config [EXTRACTED 1.00]
- **Navigation Routes (All Views)** — nav_route_today, nav_route_habits_list, nav_route_habits_table, nav_route_habits_rules, nav_route_kpis, nav_route_kpis_dashboard [EXTRACTED 1.00]
- **5-Container Docker Stack** — claudemd_container_mongodbhabit, claudemd_container_javaapp, claudemd_container_mongobackup, claudemd_container_caddy, claudemd_container_cloudflared [EXTRACTED 1.00]
- **Daily Cron Updater Subsystem** — claudemd_updatescheduler, claudemd_habitupdateservice, claudemd_streakcalculationservice, claudemd_lastrundate_service, claudemd_habitdatecalculator, claudemd_habitstructuremanager [EXTRACTED 1.00]
- **Core Domain Entities** — claudemd_habit_entity, claudemd_habitstructure_entity [EXTRACTED 1.00]
- **Ingress Chain (Cloudflare→cloudflared→caddy→javaapp)** — claudemd_container_cloudflared, claudemd_container_caddy, claudemd_container_javaapp, claudemd_ingress_flow [EXTRACTED 1.00]

## Communities

### Community 0 - "Core Domain + Habit UI"
Cohesion: 0.04
Nodes (71): Chart.js External Library, defaultMade Catch-up Default Design, Habit Entity, HabitDateCalculator, HabitReadController, HabitStructure Entity, HabitStructureManager, HabitUpdateService (+63 more)

### Community 1 - "Auth API (JWT)"
Cohesion: 0.05
Nodes (12): ApiAuthController, JwtAuthFilter, JwtUtil, OidcUser, OncePerRequestFilter, RegisterController, SecurityConfig, UserDetails (+4 more)

### Community 2 - "Habit Service Layer"
Cohesion: 0.05
Nodes (5): HabitDTO, HabitReadController, HabitService, HabitWriteController, SecurityUtils

### Community 3 - "KPI Service + Collection Naming"
Cohesion: 0.08
Nodes (4): KPICollectionNameUtil, KPICollectionNameUtilTest, KPIService, KPIServiceTest

### Community 4 - "KPI Data Repository + Tests"
Cohesion: 0.1
Nodes (3): DynamicKPIDataRepository, DynamicKPIDataRepositoryTest, HabitCompletionSystemTest

### Community 5 - "Streak Calculation Tests"
Cohesion: 0.17
Nodes (2): StreakCalculationServiceIntegrationTest, StreakCalculationServiceUnitTest

### Community 6 - "Structure Service + Read Controller"
Cohesion: 0.12
Nodes (2): HabitStructureRepository, StructureService

### Community 7 - "Daily Updater Pipeline"
Cohesion: 0.08
Nodes (5): HabitDateCalculator, HabitStructureManager, HabitUpdateService, LastRunDateService, UpdateScheduler

### Community 8 - "KPI Dashboard JS"
Cohesion: 0.23
Nodes (22): addDataToKPI(), closeModal(), formatDate(), getColorIntensity(), getCsrfToken(), getTrendClass(), getTrendColor(), getTrendText() (+14 more)

### Community 9 - "Streak + Date Calculator"
Cohesion: 0.18
Nodes (2): HabitDateCalculatorTest, StreakCalculationService

### Community 10 - "KPI Controller + Repository"
Cohesion: 0.12
Nodes (2): KPIController, KPIRepository

### Community 11 - "Infrastructure Config"
Cohesion: 0.15
Nodes (17): backup.py (Python Backup Service), Caddy Caddyfile (Reverse Proxy Config), cloudflared config.yml (Tunnel Ingress), caddy Container (caddy:2), cloudflared Container, javaapp Container (eclipse-temurin:21-jre-alpine), mongo-backup Container (python:3.10-slim), mongodbHabit Container (mongo:7) (+9 more)

### Community 12 - "Rule Service + Repository"
Cohesion: 0.18
Nodes (2): RuleRepository, RuleService

### Community 13 - "KPI List JS"
Cohesion: 0.44
Nodes (9): addData(), closeModal(), deleteKPI(), getCsrfToken(), handleFormSubmit(), initializeModal(), setTodayAsDefault(), showMessage() (+1 more)

### Community 14 - "Habit Repository"
Cohesion: 0.2
Nodes (1): HabitRepository

### Community 15 - "Rule Setting JS"
Cohesion: 0.5
Nodes (7): gcd(), gcdArray(), getCsrfToken(), getSelectedFrequencies(), getSelectedHabitsInfo(), saveRule(), updateFrequencyResult()

### Community 16 - "Input + Streak Color JS"
Cohesion: 0.54
Nodes (6): applyStreakColor(), getCsrfToken(), moveHabitToPosition(), organizeHabitsOnLoad(), removeHabitWithShameAnimation(), updateHabitStatus()

### Community 17 - "KPI Create Form JS"
Cohesion: 0.5
Nodes (6): initializeFormValidation(), setupFormInteractions(), showError(), showFieldError(), validateForm(), validateKPIName()

### Community 18 - "Habit Table JS"
Cohesion: 0.5
Nodes (6): fetchUpdatedTable(), findHabitKeyByName(), getCsrfToken(), getHabitOrderFromHeaders(), updateHabitStatus(), updateTable()

### Community 19 - "PWA Icons (small)"
Cohesion: 0.38
Nodes (7): Android Chrome App Icon (192x192), Favicon 16x16, App Favicon (32x32), HabitTracker Brand Identity, HabitTracker Brand Identity, HabitTracker Progressive Web App, Static Assets Directory

### Community 20 - "KPI-Habit Mapping Repository"
Cohesion: 0.33
Nodes (1): KPIHabitMappingRepository

### Community 21 - "Habits List JS"
Cohesion: 0.67
Nodes (4): attachDeleteHandlers(), attachDropdownHandlers(), getCsrfToken(), swapHabitsList()

### Community 22 - "Web MVC Config"
Cohesion: 0.6
Nodes (2): WebConfig, WebMvcConfigurer

### Community 23 - "PWA Brand + Icons (large)"
Cohesion: 0.7
Nodes (5): Android Chrome App Icon (512x512), Cross / Plus-Sign Iconography, HabitTracker Brand Identity, PWA Manifest Icon Asset, Teal/Neon Design Language

### Community 24 - "Spring Boot Entry Point"
Cohesion: 0.5
Nodes (1): HabitTrackerApplication

### Community 25 - "Structure DTO"
Cohesion: 0.5
Nodes (1): StructureDTO

### Community 26 - "Structure Repository"
Cohesion: 0.5
Nodes (1): StructureRepository

### Community 27 - "Application Tests"
Cohesion: 0.5
Nodes (1): HabitTrackerApplicationTests

### Community 28 - "Docker Compose Runner"
Cohesion: 0.67
Nodes (1): Run-DockerCompose()

### Community 29 - "Python Backup Script"
Cohesion: 0.67
Nodes (1): do_backup()

### Community 30 - "User Entity"
Cohesion: 0.67
Nodes (1): User

### Community 31 - "Habit Entity"
Cohesion: 0.67
Nodes (1): Habit

### Community 32 - "KPI Entity"
Cohesion: 0.67
Nodes (1): KPI

### Community 33 - "KPI Data Entity"
Cohesion: 0.67
Nodes (1): KPIData

### Community 34 - "KPI Data DTO"
Cohesion: 0.67
Nodes (1): KPIDataDTO

### Community 35 - "KPI DTO"
Cohesion: 0.67
Nodes (1): KPIDTO

### Community 36 - "KPI-Habit Mapping Entity"
Cohesion: 0.67
Nodes (1): KPIHabitMapping

### Community 37 - "Rule Entity"
Cohesion: 0.67
Nodes (1): Rule

### Community 38 - "Rule DTO"
Cohesion: 0.67
Nodes (1): RuleDTO

### Community 39 - "Update DTO"
Cohesion: 0.67
Nodes (1): UpdateDTO

### Community 40 - "HabitStructure Entity"
Cohesion: 0.67
Nodes (1): HabitStructure

### Community 41 - "Structure Entity"
Cohesion: 0.67
Nodes (1): Structure

### Community 42 - "LastRunDate Entity"
Cohesion: 0.67
Nodes (1): LastRunDate

### Community 43 - "Pair Utility"
Cohesion: 0.67
Nodes (1): Pair

### Community 44 - "New Habit Form JS"
Cohesion: 0.67
Nodes (1): getCsrfToken()

### Community 45 - "Edit Habit Form JS"
Cohesion: 0.67
Nodes (1): getCsrfToken()

### Community 46 - "Habit Info JS"
Cohesion: 0.67
Nodes (1): getCsrfToken()

### Community 47 - "PWA Color Palette"
Cohesion: 1.0
Nodes (3): Teal-on-Dark Color Palette, HabitTracker PWA / Brand Identity, Apple Touch Icon (PWA App Icon)

### Community 48 - "Backup Requirements"
Cohesion: 1.0
Nodes (2): Backup Service Python Requirements, Backup Service (Python+Google Drive)

### Community 53 - "README"
Cohesion: 1.0
Nodes (1): HabitTracker README Overview

### Community 54 - "TLS/HTTPS Config"
Cohesion: 1.0
Nodes (1): TLS/HTTPS via Caddy (Auto Cert)

### Community 55 - "Timezone Handling"
Cohesion: 1.0
Nodes (1): Timezone Auto-Detection and Mapping

### Community 56 - "Spring Boot Docs"
Cohesion: 1.0
Nodes (1): Spring Boot Reference Documentation Links

### Community 57 - "Web Config (CORS)"
Cohesion: 1.0
Nodes (1): WebConfig (CORS/MVC)

## Knowledge Gaps
- **35 isolated node(s):** `HabitTracker README Overview`, `Backup Service (Python+Google Drive)`, `Updater Service Refactor Rationale`, `TLS/HTTPS via Caddy (Auto Cert)`, `Timezone Auto-Detection and Mapping` (+30 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Streak Calculation Tests`** (36 nodes): `StreakCalculationServiceIntegrationTest.java`, `StreakCalculationServiceUnitTest.java`, `StreakCalculationServiceIntegrationTest.java`, `StreakCalculationServiceUnitTest.java`, `.updateAllStreaks()`, `StreakCalculationServiceIntegrationTest`, `.downtime7Days_defaultMadeFalse_streakDecreases7()`, `.downtime7Days_defaultMadeTrue_streakIncreases7_andUpdatesLongest()`, `.downtimeAfterPositiveStreak_streakResetsCorrectly()`, `.explicitCompletion_fromNegative_savesLastNegativeStreak()`, `.explicitCompletion_fromNegative_streakBecomesOne()`, `.inactiveHabit_streakNeverChanges()`, `.multipleHabits_eachUpdatedIndependently()`, `.positiveStreak_missedDay_streakDropsToZero_clearsLastNegativeStreak()`, `.savedDailyHabit()`, `StreakCalculationServiceUnitTest`, `.captureUpdate()`, `.dailyHabit()`, `.fastPath_dailyHabit_7dayGap_defaultMadeTrue_streakIncreases7()`, `.fastPath_dailyHabit_7dayGap_streakDecreases7()`, `.fastPath_negativeStreak_missedMoreDays_continuesDecrementing()`, `.fastPath_positiveStreak_missedDays_resetsToOneMinusDelta()`, `.fastPath_positiveStreak_missedDays_setsLastNegUnset()`, `.fastPath_weeklyHabit_14dayGap_counts2ScheduledDays()`, `.fastPath_weeklyHabit_gapStartsMidCycle_alignsToNextScheduledDay()`, `.lastNegFrom()`, `.lastNegUnset()`, `.setUp()`, `.slowPath_explicitCompletion_fromNegative_savesLastNegativeStreak()`, `.slowPath_explicitCompletion_fromNegative_streakBecomesOne()`, `.slowPath_inactiveHabit_isSkippedEntirely()`, `.slowPath_notCompleted_alreadyNegative_continuesDecrementing()`, `.slowPath_notCompleted_fromPositive_streakDropsToZero_clearsLastNeg()`, `.streakFrom()`, `.stubWith()`, `.weeklyHabit()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Service + Read Controller`** (29 nodes): `HabitStructureRepository.java`, `StructureService.java`, `.getMethodName()`, `.getHabitsByIds()`, `.restoreNegativeStreak()`, `HabitStructureRepository`, `.findByHabitIdAndStructureDate()`, `.findByStructureDate()`, `.findByStructureDateAndUserId()`, `.findByStructureDateBetween()`, `.findByStructureDateBetweenAndUserId()`, `HabitStructureRepository.java`, `StructureService.java`, `.updateHabit()`, `StructureService`, `.fetchHabitStructures()`, `.fillMissingHabits()`, `.filterFailedNegativeHabits()`, `.getHabitIdToNameMap()`, `.getHabitIdToNameMapFromIds()`, `.getStructureForDate()`, `.getStructuresForDateRange()`, `.getTodayStructure()`, `.initializeStructureMap()`, `.isHabitActiveOnDate()`, `.populateHabitStatuses()`, `.populateStructureDTO()`, `.populateStructureMap()`, `.updateHabitCompletion()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Streak + Date Calculator`** (20 nodes): `StreakCalculationService.java`, `HabitDateCalculatorTest.java`, `.shouldTrackHabitOnDate()`, `HabitDateCalculatorTest`, `.frequency1_afterEndDate_notTracked()`, `.frequency1_afterStartDate_tracked()`, `.frequency1_beforeStartDate_notTracked()`, `.frequency1_nullStartDate_alwaysTracked()`, `.frequency1_onEndDate_tracked()`, `.frequency7_betweenScheduledDays_notTracked()`, `.frequency7_onScheduledDays_tracked()`, `.frequency7_onStartDate_tracked()`, `.habit()`, `StreakCalculationService.java`, `HabitDateCalculatorTest.java`, `StreakCalculationService`, `.countScheduledDays()`, `.fetchHabitStructures()`, `.StreakCalculationService()`, `.updateHabitStreak()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Controller + Repository`** (19 nodes): `KPIController.java`, `KPIRepository.java`, `.findByHabitId()`, `KPIController.java`, `KPIRepository.java`, `KPIController`, `.addKPIData()`, `.createKPI()`, `.deleteKPI()`, `.getKPIsByHabit()`, `.showKPIDashboard()`, `.showKPIList()`, `KPIRepository`, `.existsByNameAndUserId()`, `.findByActive()`, `.findByActiveAndUserId()`, `.findByNameIn()`, `.getAllActiveKPIs()`, `.getKPIsByHabitId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Rule Service + Repository`** (13 nodes): `RuleRepository.java`, `RuleService.java`, `RuleRepository.java`, `RuleService.java`, `RuleRepository`, `.deleteByHabitSubId()`, `.findByHabitOwnerId()`, `.findByHabitSubId()`, `RuleService`, `.addRule()`, `.deleteBySubId()`, `.getMainIdsBySubId()`, `.getRulesByMainId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Repository`** (10 nodes): `HabitRepository.java`, `HabitRepository`, `.findByCurDate()`, `.findByEndDateBetween()`, `.findByName()`, `.findByStartDateBetween()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqual()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqualAndFrequency()`, `.getHabitsByDate()`, `HabitRepository.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI-Habit Mapping Repository`** (6 nodes): `KPIHabitMappingRepository.java`, `KPIHabitMappingRepository.java`, `KPIHabitMappingRepository`, `.deleteByHabitId()`, `.deleteByKpiNameAndHabitId()`, `.findByHabitId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Web MVC Config`** (5 nodes): `WebConfig.java`, `WebConfig.java`, `WebConfig`, `.addResourceHandlers()`, `WebMvcConfigurer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Spring Boot Entry Point`** (4 nodes): `HabitTrackerApplication.java`, `HabitTrackerApplication.java`, `HabitTrackerApplication`, `.main()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure DTO`** (4 nodes): `StructureDTO.java`, `StructureDTO.java`, `StructureDTO`, `.StructureDTO()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Repository`** (4 nodes): `StructureRepository.java`, `StructureRepository.java`, `StructureRepository`, `.findByDateBetween()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Application Tests`** (4 nodes): `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests`, `.contextLoads()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Docker Compose Runner`** (3 nodes): `docker-compose-runner-v1.ps1`, `docker-compose-runner-v1.ps1`, `Run-DockerCompose()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Python Backup Script`** (3 nodes): `backup.py`, `do_backup()`, `backup.py`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `User Entity`** (3 nodes): `User.java`, `User.java`, `User`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Entity`** (3 nodes): `Habit.java`, `Habit`, `Habit.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Entity`** (3 nodes): `KPI.java`, `KPI.java`, `KPI`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Data Entity`** (3 nodes): `KPIData.java`, `KPIData.java`, `KPIData`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI Data DTO`** (3 nodes): `KPIDataDTO.java`, `KPIDataDTO.java`, `KPIDataDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI DTO`** (3 nodes): `KPIDTO.java`, `KPIDTO.java`, `KPIDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `KPI-Habit Mapping Entity`** (3 nodes): `KPIHabitMapping.java`, `KPIHabitMapping.java`, `KPIHabitMapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Rule Entity`** (3 nodes): `Rule.java`, `Rule.java`, `Rule`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Rule DTO`** (3 nodes): `RuleDTO.java`, `RuleDTO.java`, `RuleDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Update DTO`** (3 nodes): `UpdateDTO.java`, `UpdateDTO.java`, `UpdateDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `HabitStructure Entity`** (3 nodes): `HabitStructure.java`, `HabitStructure`, `HabitStructure.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Entity`** (3 nodes): `Structure.java`, `Structure.java`, `Structure`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `LastRunDate Entity`** (3 nodes): `LastRunDate.java`, `LastRunDate.java`, `LastRunDate`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Pair Utility`** (3 nodes): `Pair.java`, `Pair.java`, `Pair`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `New Habit Form JS`** (3 nodes): `new-habit.js`, `new-habit.js`, `getCsrfToken()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Edit Habit Form JS`** (3 nodes): `edit-habit.js`, `getCsrfToken()`, `edit-habit.js`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Info JS`** (3 nodes): `info.js`, `info.js`, `getCsrfToken()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Backup Requirements`** (2 nodes): `Backup Service Python Requirements`, `Backup Service (Python+Google Drive)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README`** (1 nodes): `HabitTracker README Overview`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `TLS/HTTPS Config`** (1 nodes): `TLS/HTTPS via Caddy (Auto Cert)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Timezone Handling`** (1 nodes): `Timezone Auto-Detection and Mapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Spring Boot Docs`** (1 nodes): `Spring Boot Reference Documentation Links`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Web Config (CORS)`** (1 nodes): `WebConfig (CORS/MVC)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `UserPrincipal` connect `Auth API (JWT)` to `Habit Service Layer`?**
  _High betweenness centrality (0.048) - this node is a cross-community bridge._
- **Why does `HabitService` connect `Habit Service Layer` to `Habit Repository`, `Structure Service + Read Controller`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Why does `deleteKPI()` connect `KPI List JS` to `KPI Service + Collection Naming`?**
  _High betweenness centrality (0.018) - this node is a cross-community bridge._
- **What connects `HabitTracker README Overview`, `Backup Service (Python+Google Drive)`, `Updater Service Refactor Rationale` to the rest of the system?**
  _35 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Core Domain + Habit UI` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `Auth API (JWT)` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `Habit Service Layer` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
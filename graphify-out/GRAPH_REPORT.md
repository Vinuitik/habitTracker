# Graph Report - .  (2026-04-28)

## Corpus Check
- Corpus is ~25,417 words - fits in a single context window. You may not need a graph.

## Summary
- 629 nodes · 1069 edges · 51 communities detected
- Extraction: 73% EXTRACTED · 27% INFERRED · 0% AMBIGUOUS · INFERRED: 290 edges (avg confidence: 0.8)
- Token cost: 797 input · 692 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Core Service + Auth Security|Core Service + Auth Security]]
- [[_COMMUNITY_JWT Authentication Module|JWT Authentication Module]]
- [[_COMMUNITY_KPI Tracking System|KPI Tracking System]]
- [[_COMMUNITY_Domain Model + Streak Logic|Domain Model + Streak Logic]]
- [[_COMMUNITY_Streak Testing Suite|Streak Testing Suite]]
- [[_COMMUNITY_HTTP Controllers + Routing|HTTP Controllers + Routing]]
- [[_COMMUNITY_Daily Cron Updater Pipeline|Daily Cron Updater Pipeline]]
- [[_COMMUNITY_Cron Scheduling Concepts|Cron Scheduling Concepts]]
- [[_COMMUNITY_Structure Persistence|Structure Persistence]]
- [[_COMMUNITY_KPI Dashboard Frontend|KPI Dashboard Frontend]]
- [[_COMMUNITY_KPI List + System Tests|KPI List + System Tests]]
- [[_COMMUNITY_Streak + Date Calc Tests|Streak + Date Calc Tests]]
- [[_COMMUNITY_Rules Feature|Rules Feature]]
- [[_COMMUNITY_Habit Repository Queries|Habit Repository Queries]]
- [[_COMMUNITY_Infrastructure + Deployment|Infrastructure + Deployment]]
- [[_COMMUNITY_Rule Setting Frontend|Rule Setting Frontend]]
- [[_COMMUNITY_App Icons + Branding|App Icons + Branding]]
- [[_COMMUNITY_Habit Input Frontend|Habit Input Frontend]]
- [[_COMMUNITY_KPI Create Frontend|KPI Create Frontend]]
- [[_COMMUNITY_Habit Table Frontend|Habit Table Frontend]]
- [[_COMMUNITY_Habits List Frontend|Habits List Frontend]]
- [[_COMMUNITY_CORS  MVC Config|CORS / MVC Config]]
- [[_COMMUNITY_PWA Brand Design|PWA Brand Design]]
- [[_COMMUNITY_Spring Boot Entry Point|Spring Boot Entry Point]]
- [[_COMMUNITY_Structure DTO|Structure DTO]]
- [[_COMMUNITY_Structure Repository|Structure Repository]]
- [[_COMMUNITY_App Context Test|App Context Test]]
- [[_COMMUNITY_iOS PWA Assets|iOS PWA Assets]]
- [[_COMMUNITY_Docker Runner Script|Docker Runner Script]]
- [[_COMMUNITY_MongoDB Backup Script|MongoDB Backup Script]]
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
- [[_COMMUNITY_Habit Structure Entity|Habit Structure Entity]]
- [[_COMMUNITY_Structure Entity|Structure Entity]]
- [[_COMMUNITY_Last Run Date Entity|Last Run Date Entity]]
- [[_COMMUNITY_Pair Utility|Pair Utility]]
- [[_COMMUNITY_Create Habit JS|Create Habit JS]]
- [[_COMMUNITY_Edit Habit JS|Edit Habit JS]]
- [[_COMMUNITY_Info Page JS|Info Page JS]]
- [[_COMMUNITY_Project Config (CLAUDE.md)|Project Config (CLAUDE.md)]]
- [[_COMMUNITY_Web MVC Config Concept|Web MVC Config Concept]]
- [[_COMMUNITY_README Overview|README Overview]]
- [[_COMMUNITY_Docker Timezone Utility|Docker Timezone Utility]]

## God Nodes (most connected - your core abstractions)
1. `HabitService` - 22 edges
2. `StreakCalculationServiceUnitTest` - 22 edges
3. `UserPrincipal` - 20 edges
4. `StructureService` - 16 edges
5. `KPIService` - 15 edges
6. `KPICollectionNameUtilTest` - 15 edges
7. `DynamicKPIDataRepositoryTest` - 14 edges
8. `DynamicKPIDataRepository` - 13 edges
9. `KPIServiceTest` - 13 edges
10. `Auth Implementation TODO List` - 13 edges

## Surprising Connections (you probably didn't know these)
- `Backup Service Python Requirements` --references--> `Mongo Backup Container`  [INFERRED]
  backup/requirements.txt → CLAUDE.md
- `new-habit.html (Create Habit Form)` --shares_data_with--> `Habit Entity`  [INFERRED]
  habitTracker/src/main/resources/static/addHabitView/new-habit.html → CLAUDE.md
- `edit-habit.html (Edit Habit Form)` --shares_data_with--> `Habit Entity`  [INFERRED]
  habitTracker/src/main/resources/templates/edit-habit.html → CLAUDE.md
- `habits-list.html (My Habits List)` --shares_data_with--> `Habit Entity`  [INFERRED]
  habitTracker/src/main/resources/templates/habits-list.html → CLAUDE.md
- `info.html (Edit Habit Info)` --shares_data_with--> `Habit Entity`  [INFERRED]
  habitTracker/src/main/resources/templates/info.html → CLAUDE.md

## Hyperedges (group relationships)
- **5-Container Docker Compose Stack** — claudemd_springboot_app, claudemd_mongodb_container, claudemd_mongo_backup_container, claudemd_caddy_container, claudemd_cloudflared_container [EXTRACTED 1.00]
- **Updater Service Components** — claudemd_updatescheduler, claudemd_habitupdateservice, claudemd_streakcalcservice, claudemd_lastrundateservice, claudemd_habitdatecalculator, claudemd_habitstructuremanager [EXTRACTED 1.00]
- **Navigation Routes (All Views)** — nav_route_today, nav_route_habits_list, nav_route_habits_table, nav_route_habits_rules, nav_route_kpis, nav_route_kpis_dashboard [EXTRACTED 1.00]
- **Auth Implementation Steps (TODOS)** — todos_userid_fields, todos_user_repository, todos_user_document, todos_userprincipal, todos_securityconfig, todos_jwtutil, todos_jwtauthfilter, todos_apiauthcontroller, todos_service_userid_filter, todos_csrf_fix, todos_test_regression_fix, todos_env_config [EXTRACTED 1.00]
- **All Thymeleaf Page Templates** — template_index_html, template_habits_list_html, template_habit_table_html, template_edit_habit_html, template_info_html, template_kpi_create_html, template_kpi_dashboard_html, template_kpi_list_html, template_login_html, template_register_html, template_rule_setting_html [INFERRED 0.90]

## Communities

### Community 0 - "Core Service + Auth Security"
Cohesion: 0.05
Nodes (6): DynamicKPIDataRepository, DynamicKPIDataRepositoryTest, HabitDTO, HabitService, KPIServiceTest, SecurityUtils

### Community 1 - "JWT Authentication Module"
Cohesion: 0.05
Nodes (12): ApiAuthController, JwtAuthFilter, JwtUtil, OidcUser, OncePerRequestFilter, RegisterController, SecurityConfig, UserDetails (+4 more)

### Community 2 - "KPI Tracking System"
Cohesion: 0.06
Nodes (5): KPICollectionNameUtil, KPICollectionNameUtilTest, KPIController, KPIRepository, KPIService

### Community 3 - "Domain Model + Streak Logic"
Cohesion: 0.06
Nodes (47): Chart.js External Library, defaultMade: missed days assumed completed, Habit Entity, HabitStructure Entity, StreakCalculationService, CSRF Protection (_csrf token meta tag), Habit Completion Toggle (complete/uncomplete), Habit Description and Status Fields (+39 more)

### Community 4 - "Streak Testing Suite"
Cohesion: 0.17
Nodes (2): StreakCalculationServiceIntegrationTest, StreakCalculationServiceUnitTest

### Community 5 - "HTTP Controllers + Routing"
Cohesion: 0.07
Nodes (3): HabitReadController, HabitWriteController, KPIHabitMappingRepository

### Community 6 - "Daily Cron Updater Pipeline"
Cohesion: 0.08
Nodes (5): HabitDateCalculator, HabitStructureManager, HabitUpdateService, LastRunDateService, UpdateScheduler

### Community 7 - "Cron Scheduling Concepts"
Cohesion: 0.1
Nodes (27): Cron Idempotency (last_run_date guard), HabitDateCalculator, HabitReadController, HabitStructureManager, HabitUpdateService, HabitWriteController, LastRunDateService, UpdateScheduler (+19 more)

### Community 8 - "Structure Persistence"
Cohesion: 0.14
Nodes (2): HabitStructureRepository, StructureService

### Community 9 - "KPI Dashboard Frontend"
Cohesion: 0.23
Nodes (22): addDataToKPI(), closeModal(), formatDate(), getColorIntensity(), getCsrfToken(), getTrendClass(), getTrendColor(), getTrendText() (+14 more)

### Community 10 - "KPI List + System Tests"
Cohesion: 0.14
Nodes (10): HabitCompletionSystemTest, addData(), closeModal(), deleteKPI(), getCsrfToken(), handleFormSubmit(), initializeModal(), setTodayAsDefault() (+2 more)

### Community 11 - "Streak + Date Calc Tests"
Cohesion: 0.18
Nodes (2): HabitDateCalculatorTest, StreakCalculationService

### Community 12 - "Rules Feature"
Cohesion: 0.18
Nodes (2): RuleRepository, RuleService

### Community 13 - "Habit Repository Queries"
Cohesion: 0.2
Nodes (1): HabitRepository

### Community 14 - "Infrastructure + Deployment"
Cohesion: 0.27
Nodes (10): Backup Service Python Requirements, Caddy Reverse Proxy Container, Cloudflared Tunnel Container, Ingress Chain: Cloudflareв†’cloudflaredв†’caddyв†’javaapp, Mongo Backup Container, MongoDB Container (mongodbHabit), Spring Boot Application (javaapp), Spring Boot Reference Documentation Links (+2 more)

### Community 15 - "Rule Setting Frontend"
Cohesion: 0.5
Nodes (7): gcd(), gcdArray(), getCsrfToken(), getSelectedFrequencies(), getSelectedHabitsInfo(), saveRule(), updateFrequencyResult()

### Community 16 - "App Icons + Branding"
Cohesion: 0.31
Nodes (9): Android Chrome App Icon (192x192), App Brand Identity, Favicon 16x16, App Favicon (32x32), HabitTracker Web Application, HabitTracker Brand Identity, HabitTracker Brand Identity, HabitTracker Progressive Web App (+1 more)

### Community 17 - "Habit Input Frontend"
Cohesion: 0.54
Nodes (6): applyStreakColor(), getCsrfToken(), moveHabitToPosition(), organizeHabitsOnLoad(), removeHabitWithShameAnimation(), updateHabitStatus()

### Community 18 - "KPI Create Frontend"
Cohesion: 0.5
Nodes (6): initializeFormValidation(), setupFormInteractions(), showError(), showFieldError(), validateForm(), validateKPIName()

### Community 19 - "Habit Table Frontend"
Cohesion: 0.5
Nodes (6): fetchUpdatedTable(), findHabitKeyByName(), getCsrfToken(), getHabitOrderFromHeaders(), updateHabitStatus(), updateTable()

### Community 20 - "Habits List Frontend"
Cohesion: 0.67
Nodes (4): attachDeleteHandlers(), attachDropdownHandlers(), getCsrfToken(), swapHabitsList()

### Community 21 - "CORS / MVC Config"
Cohesion: 0.6
Nodes (2): WebConfig, WebMvcConfigurer

### Community 22 - "PWA Brand Design"
Cohesion: 0.7
Nodes (5): Android Chrome App Icon (512x512), Cross / Plus-Sign Iconography, HabitTracker Brand Identity, PWA Manifest Icon Asset, Teal/Neon Design Language

### Community 23 - "Spring Boot Entry Point"
Cohesion: 0.5
Nodes (1): HabitTrackerApplication

### Community 24 - "Structure DTO"
Cohesion: 0.5
Nodes (1): StructureDTO

### Community 25 - "Structure Repository"
Cohesion: 0.5
Nodes (1): StructureRepository

### Community 26 - "App Context Test"
Cohesion: 0.5
Nodes (1): HabitTrackerApplicationTests

### Community 27 - "iOS PWA Assets"
Cohesion: 0.67
Nodes (4): Teal-on-Dark Color Palette, HabitTracker PWA / Brand Identity, Apple Touch Icon (PWA App Icon), Static Assets Directory

### Community 28 - "Docker Runner Script"
Cohesion: 0.67
Nodes (1): Run-DockerCompose()

### Community 29 - "MongoDB Backup Script"
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

### Community 40 - "Habit Structure Entity"
Cohesion: 0.67
Nodes (1): HabitStructure

### Community 41 - "Structure Entity"
Cohesion: 0.67
Nodes (1): Structure

### Community 42 - "Last Run Date Entity"
Cohesion: 0.67
Nodes (1): LastRunDate

### Community 43 - "Pair Utility"
Cohesion: 0.67
Nodes (1): Pair

### Community 44 - "Create Habit JS"
Cohesion: 0.67
Nodes (1): getCsrfToken()

### Community 45 - "Edit Habit JS"
Cohesion: 0.67
Nodes (1): getCsrfToken()

### Community 46 - "Info Page JS"
Cohesion: 0.67
Nodes (1): getCsrfToken()

### Community 47 - "Project Config (CLAUDE.md)"
Cohesion: 1.0
Nodes (1): HabitTracker Project (CLAUDE.md)

### Community 48 - "Web MVC Config Concept"
Cohesion: 1.0
Nodes (1): WebConfig (CORS/MVC)

### Community 49 - "README Overview"
Cohesion: 1.0
Nodes (1): HabitTracker README Overview

### Community 50 - "Docker Timezone Utility"
Cohesion: 1.0
Nodes (1): Timezone Auto-Detection and Mapping

## Knowledge Gaps
- **28 isolated node(s):** `HabitTracker Project (CLAUDE.md)`, `HabitDateCalculator`, `HabitReadController`, `WebConfig (CORS/MVC)`, `Cron Idempotency (last_run_date guard)` (+23 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Streak Testing Suite`** (36 nodes): `StreakCalculationServiceIntegrationTest.java`, `StreakCalculationServiceUnitTest.java`, `StreakCalculationServiceIntegrationTest.java`, `StreakCalculationServiceUnitTest.java`, `.updateAllStreaks()`, `StreakCalculationServiceIntegrationTest`, `.downtime7Days_defaultMadeFalse_streakDecreases7()`, `.downtime7Days_defaultMadeTrue_streakIncreases7_andUpdatesLongest()`, `.downtimeAfterPositiveStreak_streakResetsCorrectly()`, `.explicitCompletion_fromNegative_savesLastNegativeStreak()`, `.explicitCompletion_fromNegative_streakBecomesOne()`, `.inactiveHabit_streakNeverChanges()`, `.multipleHabits_eachUpdatedIndependently()`, `.positiveStreak_missedDay_streakDropsToZero_clearsLastNegativeStreak()`, `.savedDailyHabit()`, `StreakCalculationServiceUnitTest`, `.captureUpdate()`, `.dailyHabit()`, `.fastPath_dailyHabit_7dayGap_defaultMadeTrue_streakIncreases7()`, `.fastPath_dailyHabit_7dayGap_streakDecreases7()`, `.fastPath_negativeStreak_missedMoreDays_continuesDecrementing()`, `.fastPath_positiveStreak_missedDays_resetsToOneMinusDelta()`, `.fastPath_positiveStreak_missedDays_setsLastNegUnset()`, `.fastPath_weeklyHabit_14dayGap_counts2ScheduledDays()`, `.fastPath_weeklyHabit_gapStartsMidCycle_alignsToNextScheduledDay()`, `.lastNegFrom()`, `.lastNegUnset()`, `.setUp()`, `.slowPath_explicitCompletion_fromNegative_savesLastNegativeStreak()`, `.slowPath_explicitCompletion_fromNegative_streakBecomesOne()`, `.slowPath_inactiveHabit_isSkippedEntirely()`, `.slowPath_notCompleted_alreadyNegative_continuesDecrementing()`, `.slowPath_notCompleted_fromPositive_streakDropsToZero_clearsLastNeg()`, `.streakFrom()`, `.stubWith()`, `.weeklyHabit()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Persistence`** (25 nodes): `HabitStructureRepository.java`, `StructureService.java`, `.getMethodName()`, `.getHabitsByIds()`, `HabitStructureRepository`, `.findByStructureDate()`, `.findByStructureDateAndUserId()`, `.findByStructureDateBetween()`, `.findByStructureDateBetweenAndUserId()`, `HabitStructureRepository.java`, `StructureService.java`, `StructureService`, `.fetchHabitStructures()`, `.fillMissingHabits()`, `.filterFailedNegativeHabits()`, `.getHabitIdToNameMap()`, `.getHabitIdToNameMapFromIds()`, `.getStructureForDate()`, `.getStructuresForDateRange()`, `.getTodayStructure()`, `.initializeStructureMap()`, `.isHabitActiveOnDate()`, `.populateHabitStatuses()`, `.populateStructureDTO()`, `.populateStructureMap()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Streak + Date Calc Tests`** (20 nodes): `StreakCalculationService.java`, `HabitDateCalculatorTest.java`, `.shouldTrackHabitOnDate()`, `HabitDateCalculatorTest`, `.frequency1_afterEndDate_notTracked()`, `.frequency1_afterStartDate_tracked()`, `.frequency1_beforeStartDate_notTracked()`, `.frequency1_nullStartDate_alwaysTracked()`, `.frequency1_onEndDate_tracked()`, `.frequency7_betweenScheduledDays_notTracked()`, `.frequency7_onScheduledDays_tracked()`, `.frequency7_onStartDate_tracked()`, `.habit()`, `StreakCalculationService.java`, `HabitDateCalculatorTest.java`, `StreakCalculationService`, `.countScheduledDays()`, `.fetchHabitStructures()`, `.StreakCalculationService()`, `.updateHabitStreak()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Rules Feature`** (13 nodes): `RuleRepository.java`, `RuleService.java`, `RuleRepository.java`, `RuleService.java`, `RuleRepository`, `.deleteByHabitSubId()`, `.findByHabitOwnerId()`, `.findByHabitSubId()`, `RuleService`, `.addRule()`, `.deleteBySubId()`, `.getMainIdsBySubId()`, `.getRulesByMainId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Habit Repository Queries`** (10 nodes): `HabitRepository.java`, `HabitRepository`, `.findByCurDate()`, `.findByEndDateBetween()`, `.findByName()`, `.findByStartDateBetween()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqual()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqualAndFrequency()`, `.getHabitsByDate()`, `HabitRepository.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `CORS / MVC Config`** (5 nodes): `WebConfig.java`, `WebConfig.java`, `WebConfig`, `.addResourceHandlers()`, `WebMvcConfigurer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Spring Boot Entry Point`** (4 nodes): `HabitTrackerApplication.java`, `HabitTrackerApplication.java`, `HabitTrackerApplication`, `.main()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure DTO`** (4 nodes): `StructureDTO.java`, `StructureDTO.java`, `StructureDTO`, `.StructureDTO()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Repository`** (4 nodes): `StructureRepository.java`, `StructureRepository.java`, `StructureRepository`, `.findByDateBetween()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `App Context Test`** (4 nodes): `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests`, `.contextLoads()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Docker Runner Script`** (3 nodes): `docker-compose-runner-v1.ps1`, `docker-compose-runner-v1.ps1`, `Run-DockerCompose()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `MongoDB Backup Script`** (3 nodes): `backup.py`, `do_backup()`, `backup.py`
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
- **Thin community `Habit Structure Entity`** (3 nodes): `HabitStructure.java`, `HabitStructure`, `HabitStructure.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Structure Entity`** (3 nodes): `Structure.java`, `Structure.java`, `Structure`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Last Run Date Entity`** (3 nodes): `LastRunDate.java`, `LastRunDate.java`, `LastRunDate`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Pair Utility`** (3 nodes): `Pair.java`, `Pair.java`, `Pair`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Create Habit JS`** (3 nodes): `new-habit.js`, `new-habit.js`, `getCsrfToken()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Edit Habit JS`** (3 nodes): `edit-habit.js`, `getCsrfToken()`, `edit-habit.js`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Info Page JS`** (3 nodes): `info.js`, `info.js`, `getCsrfToken()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Project Config (CLAUDE.md)`** (1 nodes): `HabitTracker Project (CLAUDE.md)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Web MVC Config Concept`** (1 nodes): `WebConfig (CORS/MVC)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README Overview`** (1 nodes): `HabitTracker README Overview`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Docker Timezone Utility`** (1 nodes): `Timezone Auto-Detection and Mapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `UserPrincipal` connect `JWT Authentication Module` to `Core Service + Auth Security`, `HTTP Controllers + Routing`?**
  _High betweenness centrality (0.050) - this node is a cross-community bridge._
- **Why does `HabitService` connect `Core Service + Auth Security` to `Structure Persistence`, `Habit Repository Queries`, `HTTP Controllers + Routing`?**
  _High betweenness centrality (0.032) - this node is a cross-community bridge._
- **What connects `HabitTracker Project (CLAUDE.md)`, `HabitDateCalculator`, `HabitReadController` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Core Service + Auth Security` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `JWT Authentication Module` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `KPI Tracking System` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
- **Should `Domain Model + Streak Logic` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
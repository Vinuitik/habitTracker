# Graph Report - HabitTracker  (2026-05-03)

## Corpus Check
- 70 files · ~26,296 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 567 nodes · 941 edges · 47 communities detected
- Extraction: 68% EXTRACTED · 32% INFERRED · 0% AMBIGUOUS · INFERRED: 300 edges (avg confidence: 0.81)
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
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
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
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]

## God Nodes (most connected - your core abstractions)
1. `HabitService` - 21 edges
2. `StreakCalculationServiceUnitTest` - 21 edges
3. `UserPrincipal` - 19 edges
4. `StructureService` - 15 edges
5. `KPIService` - 14 edges
6. `KPICollectionNameUtilTest` - 14 edges
7. `Habit Entity` - 14 edges
8. `DynamicKPIDataRepositoryTest` - 13 edges
9. `Auth Implementation TODO List` - 13 edges
10. `DynamicKPIDataRepository` - 12 edges

## Surprising Connections (you probably didn't know these)
- `Updater Service Refactor Rationale` --rationale_for--> `UpdateScheduler`  [EXTRACTED]
  README.md → CLAUDE.md
- `API Endpoints Definition` --references--> `HabitReadController`  [INFERRED]
  README.md → CLAUDE.md
- `API Endpoints Definition` --references--> `HabitWriteController`  [INFERRED]
  README.md → CLAUDE.md
- `Step 11: Fix HabitCompletionSystemTest regression` --references--> `HabitWriteController`  [INFERRED]
  TODOS.md → CLAUDE.md
- `Authentication and Multi-User Support (Planned)` --references--> `register.html (Create Account Page)`  [EXTRACTED]
  TODOS.md → habitTracker/src/main/resources/templates/register.html

## Hyperedges (group relationships)
- **Auth Implementation Steps (TODOS)** — todos_userid_fields, todos_user_repository, todos_user_document, todos_userprincipal, todos_securityconfig, todos_jwtutil, todos_jwtauthfilter, todos_apiauthcontroller, todos_service_userid_filter, todos_csrf_fix, todos_test_regression_fix, todos_env_config [EXTRACTED 1.00]
- **Navigation Routes (All Views)** — nav_route_today, nav_route_habits_list, nav_route_habits_table, nav_route_habits_rules, nav_route_kpis, nav_route_kpis_dashboard [EXTRACTED 1.00]
- **5-Container Docker Stack** — claudemd_container_mongodbhabit, claudemd_container_javaapp, claudemd_container_mongobackup, claudemd_container_caddy, claudemd_container_cloudflared [EXTRACTED 1.00]
- **Daily Cron Updater Subsystem** — claudemd_updatescheduler, claudemd_habitupdateservice, claudemd_streakcalculationservice, claudemd_lastrundate_service, claudemd_habitdatecalculator, claudemd_habitstructuremanager [EXTRACTED 1.00]
- **Core Domain Entities** — claudemd_habit_entity, claudemd_habitstructure_entity [EXTRACTED 1.00]
- **Ingress Chain (Cloudflare→cloudflared→caddy→javaapp)** — claudemd_container_cloudflared, claudemd_container_caddy, claudemd_container_javaapp, claudemd_ingress_flow [EXTRACTED 1.00]

## Communities

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (7): DynamicKPIDataRepository, DynamicKPIDataRepositoryTest, KPICollectionNameUtil, KPICollectionNameUtilTest, KPIHabitMappingRepository, KPIService, KPIServiceTest

### Community 1 - "Community 1"
Cohesion: 0.04
Nodes (71): Chart.js External Library, defaultMade Catch-up Default Design, Habit Entity, HabitDateCalculator, HabitReadController, HabitStructure Entity, HabitStructureManager, HabitUpdateService (+63 more)

### Community 2 - "Community 2"
Cohesion: 0.05
Nodes (7): HabitCompletionSystemTest, HabitDTO, HabitReadController, HabitService, HabitStructureManager, HabitUpdateService, HabitWriteController

### Community 3 - "Community 3"
Cohesion: 0.05
Nodes (12): ApiAuthController, JwtAuthFilter, JwtUtil, OidcUser, OncePerRequestFilter, RegisterController, SecurityConfig, UserDetails (+4 more)

### Community 4 - "Community 4"
Cohesion: 0.14
Nodes (3): StreakCalculationService, StreakCalculationServiceIntegrationTest, StreakCalculationServiceUnitTest

### Community 5 - "Community 5"
Cohesion: 0.12
Nodes (3): HabitStructureRepository, SecurityUtils, StructureService

### Community 6 - "Community 6"
Cohesion: 0.17
Nodes (20): closeModal(), getColorIntensity(), getCsrfToken(), getTrendClass(), getTrendColor(), getTrendText(), handleFormSubmit(), initializeDashboard() (+12 more)

### Community 7 - "Community 7"
Cohesion: 0.14
Nodes (2): KPIController, KPIRepository

### Community 8 - "Community 8"
Cohesion: 0.15
Nodes (17): backup.py (Python Backup Service), Caddy Caddyfile (Reverse Proxy Config), cloudflared config.yml (Tunnel Ingress), caddy Container (caddy:2), cloudflared Container, javaapp Container (eclipse-temurin:21-jre-alpine), mongo-backup Container (python:3.10-slim), mongodbHabit Container (mongo:7) (+9 more)

### Community 9 - "Community 9"
Cohesion: 0.28
Nodes (2): HabitDateCalculator, HabitDateCalculatorTest

### Community 10 - "Community 10"
Cohesion: 0.24
Nodes (2): LastRunDateService, UpdateScheduler

### Community 11 - "Community 11"
Cohesion: 0.22
Nodes (2): RuleRepository, RuleService

### Community 12 - "Community 12"
Cohesion: 0.33
Nodes (6): closeModal(), deleteKPI(), getCsrfToken(), handleFormSubmit(), initializeModal(), showMessage()

### Community 13 - "Community 13"
Cohesion: 0.22
Nodes (1): HabitRepository

### Community 14 - "Community 14"
Cohesion: 0.39
Nodes (6): gcdArray(), getCsrfToken(), getSelectedFrequencies(), getSelectedHabitsInfo(), saveRule(), updateFrequencyResult()

### Community 15 - "Community 15"
Cohesion: 0.43
Nodes (3): getCsrfToken(), removeHabitWithShameAnimation(), updateHabitStatus()

### Community 16 - "Community 16"
Cohesion: 0.38
Nodes (4): showError(), showFieldError(), validateForm(), validateKPIName()

### Community 17 - "Community 17"
Cohesion: 0.38
Nodes (4): getCsrfToken(), getHabitOrderFromHeaders(), updateHabitStatus(), updateTable()

### Community 18 - "Community 18"
Cohesion: 0.38
Nodes (7): Android Chrome App Icon (192x192), Favicon 16x16, App Favicon (32x32), HabitTracker Brand Identity, HabitTracker Brand Identity, HabitTracker Progressive Web App, Static Assets Directory

### Community 19 - "Community 19"
Cohesion: 0.6
Nodes (3): attachDeleteHandlers(), attachDropdownHandlers(), swapHabitsList()

### Community 20 - "Community 20"
Cohesion: 0.7
Nodes (5): Android Chrome App Icon (512x512), Cross / Plus-Sign Iconography, HabitTracker Brand Identity, PWA Manifest Icon Asset, Teal/Neon Design Language

### Community 21 - "Community 21"
Cohesion: 0.67
Nodes (2): WebConfig, WebMvcConfigurer

### Community 22 - "Community 22"
Cohesion: 0.67
Nodes (1): HabitTrackerApplication

### Community 23 - "Community 23"
Cohesion: 0.67
Nodes (1): StructureDTO

### Community 24 - "Community 24"
Cohesion: 0.67
Nodes (1): StructureRepository

### Community 25 - "Community 25"
Cohesion: 0.67
Nodes (1): HabitTrackerApplicationTests

### Community 26 - "Community 26"
Cohesion: 1.0
Nodes (3): Teal-on-Dark Color Palette, HabitTracker PWA / Brand Identity, Apple Touch Icon (PWA App Icon)

### Community 29 - "Community 29"
Cohesion: 1.0
Nodes (1): User

### Community 30 - "Community 30"
Cohesion: 1.0
Nodes (1): Habit

### Community 31 - "Community 31"
Cohesion: 1.0
Nodes (1): KPI

### Community 32 - "Community 32"
Cohesion: 1.0
Nodes (1): KPIData

### Community 33 - "Community 33"
Cohesion: 1.0
Nodes (1): KPIDataDTO

### Community 34 - "Community 34"
Cohesion: 1.0
Nodes (1): KPIDTO

### Community 35 - "Community 35"
Cohesion: 1.0
Nodes (1): KPIHabitMapping

### Community 36 - "Community 36"
Cohesion: 1.0
Nodes (1): Rule

### Community 37 - "Community 37"
Cohesion: 1.0
Nodes (1): RuleDTO

### Community 38 - "Community 38"
Cohesion: 1.0
Nodes (1): UpdateDTO

### Community 39 - "Community 39"
Cohesion: 1.0
Nodes (1): HabitStructure

### Community 40 - "Community 40"
Cohesion: 1.0
Nodes (1): Structure

### Community 41 - "Community 41"
Cohesion: 1.0
Nodes (1): LastRunDate

### Community 42 - "Community 42"
Cohesion: 1.0
Nodes (1): Pair

### Community 46 - "Community 46"
Cohesion: 1.0
Nodes (2): Backup Service Python Requirements, Backup Service (Python+Google Drive)

### Community 49 - "Community 49"
Cohesion: 1.0
Nodes (1): HabitTracker README Overview

### Community 50 - "Community 50"
Cohesion: 1.0
Nodes (1): TLS/HTTPS via Caddy (Auto Cert)

### Community 51 - "Community 51"
Cohesion: 1.0
Nodes (1): Timezone Auto-Detection and Mapping

### Community 52 - "Community 52"
Cohesion: 1.0
Nodes (1): Spring Boot Reference Documentation Links

### Community 53 - "Community 53"
Cohesion: 1.0
Nodes (1): WebConfig (CORS/MVC)

## Knowledge Gaps
- **49 isolated node(s):** `User`, `Habit`, `KPI`, `KPIData`, `KPIDataDTO` (+44 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 7`** (17 nodes): `.findByHabitId()`, `KPIController.java`, `KPIRepository.java`, `KPIController`, `.addKPIData()`, `.createKPI()`, `.deleteKPI()`, `.getKPIsByHabit()`, `.showKPIDashboard()`, `.showKPIList()`, `KPIRepository`, `.existsByNameAndUserId()`, `.findByActive()`, `.findByActiveAndUserId()`, `.findByNameIn()`, `.getAllActiveKPIs()`, `.getKPIsByHabitId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 9`** (15 nodes): `HabitDateCalculator`, `.calculateNextOccurrence()`, `.shouldTrackHabitOnDate()`, `HabitDateCalculatorTest`, `.frequency1_afterEndDate_notTracked()`, `.frequency1_afterStartDate_tracked()`, `.frequency1_beforeStartDate_notTracked()`, `.frequency1_nullStartDate_alwaysTracked()`, `.frequency1_onEndDate_tracked()`, `.frequency7_betweenScheduledDays_notTracked()`, `.frequency7_onScheduledDays_tracked()`, `.frequency7_onStartDate_tracked()`, `.habit()`, `HabitDateCalculator.java`, `HabitDateCalculatorTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 10`** (12 nodes): `LastRunDateService.java`, `UpdateScheduler.java`, `LastRunDateService`, `.getLastRunDate()`, `.hasRunToday()`, `.LastRunDateService()`, `.markRunToday()`, `UpdateScheduler`, `.performDailyUpdate()`, `.runOnStartup()`, `.scheduledUpdate()`, `.UpdateScheduler()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 11`** (11 nodes): `RuleRepository.java`, `RuleService.java`, `RuleRepository`, `.deleteByHabitSubId()`, `.findByHabitOwnerId()`, `.findByHabitSubId()`, `RuleService`, `.addRule()`, `.deleteBySubId()`, `.getMainIdsBySubId()`, `.getRulesByMainId()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 13`** (9 nodes): `HabitRepository`, `.findByCurDate()`, `.findByEndDateBetween()`, `.findByName()`, `.findByStartDateBetween()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqual()`, `.findByStartDateLessThanEqualAndEndDateGreaterThanEqualAndFrequency()`, `.getHabitsByDate()`, `HabitRepository.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 21`** (4 nodes): `WebConfig.java`, `WebConfig`, `.addResourceHandlers()`, `WebMvcConfigurer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 22`** (3 nodes): `HabitTrackerApplication.java`, `HabitTrackerApplication`, `.main()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 23`** (3 nodes): `StructureDTO.java`, `StructureDTO`, `.StructureDTO()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 24`** (3 nodes): `StructureRepository.java`, `StructureRepository`, `.findByDateBetween()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 25`** (3 nodes): `HabitTrackerApplicationTests.java`, `HabitTrackerApplicationTests`, `.contextLoads()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 29`** (2 nodes): `User.java`, `User`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 30`** (2 nodes): `Habit`, `Habit.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 31`** (2 nodes): `KPI.java`, `KPI`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 32`** (2 nodes): `KPIData.java`, `KPIData`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 33`** (2 nodes): `KPIDataDTO.java`, `KPIDataDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 34`** (2 nodes): `KPIDTO.java`, `KPIDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 35`** (2 nodes): `KPIHabitMapping.java`, `KPIHabitMapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 36`** (2 nodes): `Rule.java`, `Rule`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 37`** (2 nodes): `RuleDTO.java`, `RuleDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 38`** (2 nodes): `UpdateDTO.java`, `UpdateDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 39`** (2 nodes): `HabitStructure`, `HabitStructure.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 40`** (2 nodes): `Structure.java`, `Structure`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 41`** (2 nodes): `LastRunDate.java`, `LastRunDate`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 42`** (2 nodes): `Pair.java`, `Pair`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 46`** (2 nodes): `Backup Service Python Requirements`, `Backup Service (Python+Google Drive)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 49`** (1 nodes): `HabitTracker README Overview`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 50`** (1 nodes): `TLS/HTTPS via Caddy (Auto Cert)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 51`** (1 nodes): `Timezone Auto-Detection and Mapping`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 52`** (1 nodes): `Spring Boot Reference Documentation Links`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 53`** (1 nodes): `WebConfig (CORS/MVC)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `UserPrincipal` connect `Community 3` to `Community 2`?**
  _High betweenness centrality (0.046) - this node is a cross-community bridge._
- **Why does `HabitService` connect `Community 2` to `Community 0`, `Community 5`, `Community 13`?**
  _High betweenness centrality (0.033) - this node is a cross-community bridge._
- **Why does `deleteKPI()` connect `Community 12` to `Community 0`?**
  _High betweenness centrality (0.019) - this node is a cross-community bridge._
- **What connects `User`, `Habit`, `KPI` to the rest of the system?**
  _49 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
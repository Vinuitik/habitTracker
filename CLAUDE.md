# HabitTracker - CLAUDE.md

## What this is
Personal habit tracking web app. Spring Boot + Thymeleaf (server-side rendering), MongoDB, hosted on a personal PC via Cloudflare Tunnel. Max ~20 concurrent users. Built primarily as a learning project.

## Stack (5 containers)
| Container | Image | Purpose |
|-----------|-------|---------|
| `mongodbHabit` | mongo:7 | Database, no public port (internal only) |
| `javaapp` | eclipse-temurin:21-jre-alpine | Spring Boot 3.4.4 web app + daily cron scheduler, no public port |
| `mongo-backup` | python:3.10-slim | Dumps MongoDB to Google Drive every 12h |
| `caddy` | caddy:2 | Single public gateway, ports 80+443, auto TLS via Let's Encrypt |
| `cloudflared` | cloudflare/cloudflared | Cloudflare Tunnel → caddy |

## Ingress
Cloudflare edge → cloudflared → caddy:80 → javaapp:8089

## Running
```powershell
.\docker-compose-runner-v1.ps1   # builds + starts, auto-detects Windows timezone
docker-compose logs -f           # tail logs
docker-compose down              # stop
```

## Source layout
```
habitTracker/          main Spring Boot app
  src/main/java/habitTracker/
    Habit/             Habit entity, repo, service, DTO
    Structure/         HabitStructure entity (daily completion records)
    KPI/               KPI tracking feature (entities, service, controller)
    Rules/             Rule entity and service
    updater/           Daily cron logic (merged from former standalone container)
      UpdateScheduler      fires @PostConstruct + @Scheduled(cron="0 5 0 * * ?")
      HabitUpdateService   advances curDate, creates HabitStructure records
      StreakCalculationService  updates streak/longestStreak on all habits
      LastRunDateService   idempotency guard (last_run_date collection)
      HabitDateCalculator  frequency math
      HabitStructureManager  creates HabitStructure docs
    HabitReadController    GET routes + Thymeleaf views
    HabitWriteController   POST routes
    WebConfig              CORS / MVC config
  src/main/resources/
    templates/         Thymeleaf HTML templates
    static/            CSS, JS, per-view assets

backup/                Python backup service (backup.py + requirements.txt)
caddy/Caddyfile        Reverse proxy config
cloudflared/config.yml Tunnel ingress rules (habittrackerdima.me)
```

## MongoDB
- DB: `habits` | auth: `root/example` | authSource: `admin`
- URI: `mongodb://root:example@mongodbHabit:27017/habits?authSource=admin`
- Collections: `habits`, `habit_structures`, `last_run_date`, KPI collections (dynamic names)
- WiredTiger cache capped at 256MB (`--wiredTigerCacheSizeGB 0.25`)

## Key domain facts
- `Habit` — core entity with `id` (Integer), `frequency` (days between occurrences), `curDate` (next scheduled date), `streak`, `longestStreak`, `defaultMade` (used as catch-up default when updater missed days)
- `HabitStructure` — one doc per habit per day it was due; `completed` field tracks whether user marked it done
- Cron runs at 12:05 AM, is idempotent (checks `last_run_date` before acting)
- `defaultMade=true` means missed days are assumed completed (affects streak calculation)

## Resource limits (docker-compose)
| Service | mem_limit | JVM / DB cap |
|---------|-----------|--------------|
| mongodbHabit | 512m | wiredTigerCacheSizeGB 0.25 |
| javaapp | 384m | -Xmx256m -Xms64m |
| mongo-backup | 128m | — |
| caddy | 64m | — |
| cloudflared | 64m | — |

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)

### Running /graphify — use the scripts, not manual commands
IMPORTANT: Do NOT run graphify steps manually (individual python -c commands). Use the two scripts:
1. `.\graphify_run.ps1` — detect, cache check, AST extraction. Read its output to get the uncached file list.
2. Dispatch subagents (parallel) for uncached non-code files. Wait for completion.
3. `.\graphify_finish.ps1` — merge, build, cluster. Read compact community table. Write labels to `graphify_labels.json`.
4. `.\graphify_finish.ps1 -LabelsFile graphify_labels.json` — final report + HTML + cleanup.
This keeps round-trips at ~4 instead of 15+, saving significant token quota.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore

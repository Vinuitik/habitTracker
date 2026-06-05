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

-------


    

## UI Design System

### Aesthetic: Focused Dark
Dark, muted surfaces. Think a well-designed dev tool or CLI dashboard — calm,
purposeful, never flashy. Not pure black — use deep slate (#0f1117 range).
Accent color: a single muted amber or cool indigo. One accent only.
No purple gradients. No glassmorphism. No hero sections.

### Typography
Load from Google Fonts. Use Space Grotesk or Syne for headings,
IBM Plex Mono or DM Mono for labels, metrics, timestamps.
Body copy: Inter is the fallback only — prefer DM Sans or Outfit.
Font sizes: headings 20-28px / body 14-15px / labels/meta 12px.
No font below 12px. Letter-spacing: -0.02em on headings.

### Color Rules
- Background stack: page #0d0f14 → surface #161920 → card #1e2128
- Text: primary #e8eaf0 / secondary #8b90a0 / muted #555a6a
- Accent: use ONE of — amber #d4a017 OR indigo #6c86f5. Pick at project start, stick to it.
- Borders: 1px solid rgba(255,255,255,0.07) — subtle, not invisible
- Danger: #e05c5c / Success: #4caf85
- All values in CSS custom properties in a single :root block in styles/tokens.css

### Motion & Transitions
- Page load: stagger-in elements with opacity 0→1 + translateY(8px→0),
  delay 50ms per element, duration 200ms, ease-out. No bounce.
- Hover states: 150ms ease on bg/border color. No scale transforms on cards.
- Button click: scale(0.97) for 100ms only.
- Route/section change: 180ms fade-out → swap content → 180ms fade-in.
- NO: parallax, scroll-triggered explosions, infinite looping animations.
- Performance rule: if it runs on scroll, it must use CSS only or requestAnimationFrame.

### Layout
- Max content width: 1100px, centered.
- Sidebar (if present): 220px fixed, same surface color as page.
- Cards: border-radius 10px, padding 20px 24px.
- Spacing scale (use only these): 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64px.
- No full-viewport hero sections in a productivity app. Content starts high.

### Component Architecture (Atomic Design)
Structure every feature from atoms up. Never write one-off styles in page files.

styles/
  tokens.css          # ALL CSS vars: colors, spacing, fonts, radii
  reset.css           # box-sizing, margin reset, base font
  atoms/
    button.css        # .btn, .btn--primary, .btn--ghost
    input.css         # .input, .input--error
    badge.css
    icon.css
  molecules/
    card.css          # .card, .card__header, .card__body
    form-group.css    # .form-group, label + input combo
    nav-item.css
  organisms/
    sidebar.css
    topbar.css
    data-table.css
  pages/
    dashboard.css
    settings.css

js/
  env.js              # single source of truth for all config
  atoms/
    toast.js
    modal.js
  molecules/
    nav.js
  organisms/
    sidebar.js
    table.js
  pages/
    dashboard.js

### env.js — Config Single Source of Truth
ALL hardcoded values live here. Never hardcode URLs, keys, or feature flags
anywhere else. If it might change, it goes in env.js.

Example structure:
const ENV = {
  API_BASE:     'https://api.yourapp.com/v1',
  WS_BASE:      'wss://ws.yourapp.com',
  AUTH_URL:     '/auth/login',
  REDIRECT_URI: 'http://localhost:3000/callback',
  FEATURES: {
    DARK_MODE:    true,
    BETA_WIDGET:  false,
  },
  LIMITS: {
    MAX_UPLOAD_MB: 10,
    ITEMS_PER_PAGE: 25,
  }
};
export default ENV;  # or window.ENV = ENV if no bundler

### What to NEVER do
- inline style="" on page-level elements (atoms/molecules handle it)
- hardcode any URL or config value outside env.js
- use !important
- write CSS without a corresponding atom/molecule/organism file
- add animations that trigger on every scroll event without throttling
- build a new component without checking if an atom/molecule already covers it
  

# HabitTracker - CLAUDE.md

## What this is
Personal habit tracking web app. Spring Boot + Thymeleaf (server-side rendering), MongoDB, hosted on a personal PC via Cloudflare Tunnel. Max ~20 concurrent users.

## Stack
| Container | Purpose |
|-----------|---------|
| `mongodbHabit` | MongoDB 7, port 27019 (host) |
| `javaapp` | Spring Boot 3.4.4, Java 21, Thymeleaf UI, port 8079→8089 |
| `updater` | Spring Boot 3.4.4, Java 21, **single daily cron only** (12:05 AM) |
| `mongo-backup` | Python 3.10, dumps MongoDB to Google Drive every 12h |
| `caddy` | Reverse proxy HTTP→javaapp:8089, port 8080 |
| `cloudflared` | Cloudflare Tunnel → caddy → javaapp |

## Key architecture facts
- `updater` is a full JVM running 24/7 for one `@Scheduled` cron. Should be merged into `javaapp`.
- Ingress chain: Cloudflare edge → cloudflared → caddy → javaapp. Caddy adds nothing here.
- No JVM heap limits set — both Spring Boot apps use default sizing (up to 25% system RAM each).
- MongoDB has no WiredTiger cache limit — defaults to ~256MB minimum.
- `nginx/` directory is dead code (superseded by Caddy).

## Running
```powershell
.\docker-compose-runner-v1.ps1   # builds + starts, auto-detects Windows timezone
docker-compose logs -f           # tail logs
docker-compose down              # stop
```

## Source layout
- `habitTracker/` — main web app (controllers, Thymeleaf templates, static assets)
- `updater/updater/` — scheduled updater (habits + streaks daily update)
- `backup/` — Python backup script
- `caddy/Caddyfile` — reverse proxy config (active)
- `cloudflared/config.yml` — tunnel ingress rules
- `nginx/` — deprecated, ignore

## MongoDB
- DB: `habits`, auth: `root/example`
- URI: `mongodb://root:example@mongodbHabit:27017/habits?authSource=admin`

# Infrastructure Flow

Files: `docker-compose.yml`, `caddy/Caddyfile`, `cloudflared/config.yml`, `docker-compose-runner-v1.ps1`

## Ingress Chain

```
Cloudflare edge → cloudflared (tunnel) → Caddy:80 → javaapp:8089
```

| Hop | Config | Key detail |
|---|---|---|
| Cloudflare → cloudflared | `cloudflared/config.yml` | Tunnel token in env; routes `habittrackerdima.me` |
| cloudflared → Caddy | `cloudflared/config.yml` → `ingress[].service` | Points to `http://caddy:80` (internal Docker network) |
| Caddy → javaapp | `caddy/Caddyfile` | Reverse proxy to `javaapp:8089`; auto-TLS from Let's Encrypt |
| javaapp | Spring Boot | Listens on `8089`; no public port exposed |

To change the public domain: `cloudflared/config.yml` + `caddy/Caddyfile` (both reference the hostname).
To change TLS: Caddy handles it automatically — no cert files needed unless switching off Let's Encrypt.

---

## Containers

| Container | Image | Public ports | mem_limit |
|---|---|---|---|
| `mongodbHabit` | `mongo:7` | none (internal only) | 512m |
| `javaapp` | `eclipse-temurin:21-jre-alpine` | none | 384m / JVM max 256m |
| `mongo-backup` | `python:3.10-slim` | none | 128m |
| `caddy` | `caddy:2` | 80, 443 | 64m |
| `cloudflared` | `cloudflare/cloudflared` | none | 64m |

---

## Starting the Stack

```powershell
.\docker-compose-runner-v1.ps1   # builds + starts; auto-detects Windows timezone
docker-compose logs -f           # tail all logs
docker-compose down              # stop all
```

Timezone is injected at runtime by the runner script — affects cron scheduling in `javaapp`.
To change cron time: `UpdateScheduler.scheduledUpdate()` in source + rebuild `javaapp`.

---

## Secrets / Env Reference

| Variable | Used by | Where to set |
|---|---|---|
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth login | `.env` → `javaapp` |
| `jwt.secret` | JWT signing | `application.properties` or `.env` |
| `jwt.expiration-ms` | JWT token lifetime | `application.properties` or `.env` |
| `MONGO_USER` / `MONGO_PASS` / `MONGO_DB` | All MongoDB connections | `.env` → `javaapp` + `mongo-backup` |
| Cloudflare tunnel token | `cloudflared` auth | `.env` → `cloudflared` |
| `habitbackup.json` | Google Drive backup | File mounted into `mongo-backup` container |

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Public domain / hostname | `cloudflared/config.yml` + `caddy/Caddyfile` | both must match |
| TLS / HTTPS | `caddy/Caddyfile` | auto Let's Encrypt by default; change to manual cert if needed |
| Port javaapp listens on | `docker-compose.yml` + `caddy/Caddyfile` proxy target | currently `8089` |
| Cron schedule (server timezone) | `docker-compose-runner-v1.ps1` timezone injection | affects `@Scheduled` in `UpdateScheduler` |
| Container memory limits | `docker-compose.yml` → `mem_limit` per service | JVM heap also capped in `javaapp` entrypoint |
| MongoDB cache size | `docker-compose.yml` → `mongodbHabit` command `--wiredTigerCacheSizeGB` | currently `0.25` |

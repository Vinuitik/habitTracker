# Infrastructure Flow

Files: `docker-compose.yml`, `caddy/Caddyfile`, `cloudflared/config.yml`, `docker-compose-runner-v1.sh` (Linux) / `docker-compose-runner-v1.ps1` (Windows), `scripts/deploy.sh`, `.githooks/pre-push`

## Ingress Chain

```
Cloudflare edge → cloudflared (tunnel) → Caddy:80 → javaapp:8089
                                                   → /mcp/* → mongo-backup:8091 (MCP)
```

| Hop | Config | Key detail |
|---|---|---|
| Cloudflare → cloudflared | `cloudflared/config.yml` | Tunnel token in env; routes `habittrackerdima.me` |
| cloudflared → Caddy | `cloudflared/config.yml` → `ingress[].service` | Points to `http://caddy:80` (internal Docker network) |
| Caddy → javaapp | `caddy/Caddyfile` | Reverse proxy to `javaapp:8089`; auto-TLS from Let's Encrypt |
| Caddy → mongo-backup (MCP) | `caddy/Caddyfile` `handle_path /mcp/*` | Strips `/mcp` prefix; proxies to `mongo-backup:8091` |
| javaapp | Spring Boot | Listens on `8089`; no public port exposed |
| mongo-backup internal API | FastAPI/uvicorn | `8092`, docker network only (not in Caddyfile), **no auth**; javaapp → `mongo-backup:8092` — see `backup/FLOWS_mcp.md` |
| mongo-backup MCP | FastMCP SSE | Listens on `8091`; no public port exposed — see `backup/FLOWS_mcp.md` |

To change the public domain: `cloudflared/config.yml` + `caddy/Caddyfile` (both reference the hostname).
To change TLS: Caddy handles it automatically — no cert files needed unless switching off Let's Encrypt.

---

## Containers

| Container | Image | Public ports | mem_limit |
|---|---|---|---|
| `mongodbHabit` | `mongo:7` | none (internal only) | 512m |
| `javaapp` | `eclipse-temurin:21-jre-alpine` | none | 384m / JVM max 256m |
| `mongo-backup` | `python:3.10-slim-bookworm` + Node/Claude CLI | none | 768m (Claude CLI subprocess) |
| `caddy` | `caddy:2` | 80, 443 | 64m |
| `cloudflared` | `cloudflare/cloudflared` | none | 64m |

---

## Starting the Stack

```bash
./docker-compose-runner-v1.sh    # Linux: builds + starts the WHOLE stack, auto-detects timezone
docker compose logs -f           # tail all logs
docker compose down              # stop all
```
(`docker-compose-runner-v1.ps1` is the Windows equivalent.)

Timezone is injected at runtime by the runner script — affects cron scheduling in `javaapp`.
To change cron time: `UpdateScheduler.scheduledUpdate()` in source + rebuild `javaapp`.

---

## Deploy on push (this Linux box is both dev and server)

```
git push origin master
  → .githooks/pre-push (only when a pushed ref is refs/heads/master)
      → (habitTracker) ./mvnw test -q        — failure BLOCKS the push
      → scripts/deploy.sh                    — failure only warns; the push still goes through
          stamps sw.js `const VERSION` = git short SHA (file restored on exit, even on failure)
          → docker compose up -d --build javaapp   (only javaapp; other containers untouched)
          → polls `docker inspect` health of javaapp up to 120s (compose healthcheck: wget :8089)
  → installed PWAs notice the changed sw.js on next load/refocus → "Reload" banner
    (click-gated, see habitTracker/src/main/resources/static/js/offline/FLOWS.md)
```

- Hooks are enabled by `git config core.hooksPath .githooks` — per clone, NOT automatic after
  `git clone`. Without it the pre-push hook never runs.
- `.githooks/pre-commit` (compile check) and `post-commit` are tracked but not executable, so git
  skips them. The old untracked `.git/hooks/post-commit` (full-stack rebuild after EVERY commit)
  is ignored while `core.hooksPath` is set; delete it if you don't want it back.
- Deploy runs only from the machine that pushes. A push from another machine deploys nothing.
- `git push --no-verify` skips tests and deploy; the server then keeps running the previous build.
- Downtime: `javaapp` is recreated in place (single container) — a few seconds of 530s through the tunnel.

---

## Secrets / Env Reference

| Variable | Used by | Where to set |
|---|---|---|
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth login | `.env` → `javaapp` |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` | Per-user Drive offline-sync connect (shared with mongo-backup's own Drive OAuth client, but each habitTracker user's consent yields their own refresh token — see `habitTracker/src/main/java/habitTracker/sync/FLOWS.md`) | `.env` → `javaapp` + `mongo-backup` |
| `jwt.secret` | JWT signing | `application.properties` or `.env` |
| `jwt.expiration-ms` | JWT token lifetime | `application.properties` or `.env` |
| `MONGO_USER` / `MONGO_PASS` / `MONGO_DB` | All MongoDB connections | `.env` → `javaapp` + `mongo-backup` |
| Cloudflare tunnel token | `cloudflared` auth | `.env` → `cloudflared` |
| `CLAUDE_HOME` | Host dir with Claude CLI login, mounted rw at `/root/.claude` in mongo-backup (default `~/.claude`) | `.env` → compose volume |
| `habitbackup.json` | Google Drive backup | File mounted into `mongo-backup` container |

---

## Change Index

| What to change | Where | Note |
|---|---|---|
| Public domain / hostname | `cloudflared/config.yml` + `caddy/Caddyfile` | both must match |
| TLS / HTTPS | `caddy/Caddyfile` | auto Let's Encrypt by default; change to manual cert if needed |
| Port javaapp listens on | `docker-compose.yml` + `caddy/Caddyfile` proxy target | currently `8089` |
| Cron schedule (server timezone) | runner script timezone injection (`.sh` / `.ps1`) | affects `@Scheduled` in `UpdateScheduler` |
| Container memory limits | `docker-compose.yml` → `mem_limit` per service | JVM heap also capped in `javaapp` entrypoint |
| Deploy-on-push behaviour (tests, health timeout) | `.githooks/pre-push`, `scripts/deploy.sh` (`HEALTH_TIMEOUT_S`) | hooks need `core.hooksPath` |
| MongoDB cache size | `docker-compose.yml` → `mongodbHabit` command `--wiredTigerCacheSizeGB` | currently `0.25` |

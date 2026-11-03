# Relay AI — decisions made without you (review these)

| # | Decision | Why | Undo / change |
|---|---|---|---|
| 1 | Project link stored as `runnerDeviceId`, `runnerProjectId`, `folderName` on Project; extra `mapJobId` so only the latest map job can write the map | prevents a stray job overwriting the map | Project.java, ProjectRunnerService |
| 2 | Runner calls take the user explicitly (`call(userId, deviceId, …)`), ownership checked every call | lets schedulers (KPI repair) call runners without a browser session | relay/RunnerClient |
| 3 | Unlink keeps the stored map and leaves the folder registered on the runner | relinking doesn't cost a remap | ProjectRunnerService.unlink |
| 4 | Map job: maxTurns 200 (`relay.map.max-turns`), orchestrator uses runner's default model, readers haiku/sonnet | cost vs depth | application properties, prompts/project-map.md |
| 5 | Folder-picker empty state links to `/relay#/install` (runner install), not `/install` (app install) | different pages | js/organisms/repo-link.js |
| 6 | Map progress lives in page memory: survives board switches, lost on reload | no resume-by-id endpoint yet | repo-link.js |
| 7 | Planner: Esc/✕ discards the conversation (asks first if anything exists); reload loses it | no resume-by-id yet | js/organisms/planner.js |
| 8 | Planner: one answer box per clarifying question, sent together | clearer than one blob reply | planner.js |
| 9 | Planner: draft read-only while AI drafts, editable after; schedule ↔ card matched by title | avoids edits being clobbered by streaming | planner.js |
| 10 | Planner "working" indicator = a seconds counter on the current step, no looping animation | design rules forbid infinite loops | planner.css |
| 11 | Old plan drawer (`plan-drawer.js`, `drawer.css`) and `PROJECT_PLAN/APPLY` URLs removed from the frontend now; old backend endpoints removed in cleanup stage | dead code | — |
| 12 | Runner confinement = Claude CLI `--restricted` + tool allowlist + `dontAsk`; proven: outside paths, `../`, symlinks, subagents all refused | strongest CLI-level option | Relay runner/internal/jobs/run.go |
| 13 | **Security note:** confinement is CLI-enforced, not an OS sandbox; everything inside the linked folder (incl. `.env` secrets) is readable by the job, and map notes stored in Mongo could quote them | inherent to "read the repo" | consider a deny-list for `.env*`, `*.pem`, `secrets/` — not done |
| 14 | Running/queued jobs count as busy → runner won't idle-suspend mid-job | don't kill paid work | Relay cmd/runnerd/main.go busyStatus |
| 15 | Runner jobs in memory only; runner restart loses them (hub sees 404 → job failed) | simplicity | runner/internal/jobs |
| 16 | `auth.findCLI` exported as `FindCLI` to reuse claude lookup | reuse | Relay runner/internal/auth |
| 17 | Map/plan jobs deny reading `.env*`, keys (`*.pem`, `*.key`, `id_rsa*`, `id_ed25519*`…), `secrets/**`, `.git/config`, `credentials*.json` — proven in a real run, subagents included (Relay 0059351) | keep secrets out of Mongo map notes | runner/internal/jobs/run.go deny list |
| 18 | `.env.example` / `.env.sample` are blocked too — CLI rules can't exclude them; allowing them needs a PreToolUse hook (new mechanism, not built) | fail safe | ask if you want the hook |
| 19 | Deny-list is by filename: secrets under other names (`config/prod.yml`, `.npmrc`) are still readable | fixed list limit | add names to the list |
| 20 | KPI runner choice: `relay/OnlineRunnerSelector` — online only, prefer last-used-for-jobs, then latest lastSeenAt; shared by all AI features | one rule everywhere | relay/OnlineRunnerSelector |
| 21 | `CapabilityGenerator.propose/generate` take `userId` first + `runnerAvailable(userId)` | background repair has no logged-in user | KPI/CapabilityGenerator |
| 22 | No runner: propose → 503 "Connect a runner…"; approve/regenerate → TEST_FAILED with that reason, attempt not counted; busy runner → 409 | — | KPI/RunnerCapabilityGenerator |
| 23 | Repair with no runner → in-memory waiting set, 60s check (`kpi.repair.runner-check-ms`), breaker untouched; restart loses the waiting set (KPI stays NEEDS_REPAIR until "Fix it") | relay stays independent of KPI code; ≤60s delay | KPI repair trigger |
| 24 | `HttpCapabilityGenerator` deleted; `backup/` untouched until cleanup | — | — |
| 25 | Planner: map notes over 60K chars → drop least-relevant subsystem notes (word overlap with your messages); caps map/board/history 60K/20K/30K, maxTurns 80 (`relay.plan.*`) | fit context, save tokens | application properties |
| 26 | Add to board: draft list matching an existing open list name reuses it; card due = own due → schedule date → deadline (12:00 UTC); importance in the ```meta block Trello tooling reads | no duplicate lists | PlannerService.apply |
| 27 | Planner busy → 409 `planner_busy`; runner forgot job → conversation `failed` | no stuck spinners | PlannerService |
| 28 | `pace` accepted but ignored [NOT IMPLEMENTED] | scope | PlannerService |
| 29 | Applying twice would duplicate cards → fixing in integration: conversation marked applied, 2nd apply → 409 | — | integration stage |
| 30 | Removed server-side Claude entirely: old /plan /apply, whole TrelloBoardGateway (only had plan/apply), backup claude_cli.py + /internal/agent/*; Node + Claude CLI out of mongo-backup image; mem 768m → 256m | server spends no credits now | git history |
| 31 | KEPT `mcp_server.py` + `trello_mcp/` — it's your public TRELLO_MCP connector (Caddy /mcp*) + hourly cron | still used | — |
| 32 | Leftover `internal_api.py` (port 8092: POST/DELETE /internal/boards) has no callers and is unauthenticated (internal network only) → removing in integration stage | dead code that can delete boards | backup/internal_api.py |
| 33 | Planner double apply → 409 `planner_already_applied`, UI "Already added to the board" | no duplicate cards | PlannerService |
| 34 | KPI capability creation is now a background job (`/api/kpis/{name}/capability/jobs`), in-memory, 4 threads, one per user+KPI, kept 15 min; old blocking endpoints kept for tests/API, nightly repair still blocking | live steps need async | KPI/CapabilityTaskService |
| 35 | Progress reaches jobs via a ThreadLocal hook (`CapabilityProgress`) instead of changing the generator interface | smaller change | KPI/CapabilityProgress |
| 36 | Propose prompt asks for `measures` + `dataSource` one-liners, stored on the capability, for the plain-language view | readable result | KPIProxyCapability |
| 37 | Step rows shared molecule `step-list.js` (planner + KPI) | one look | js/molecules/step-list.js |
| 38 | Service worker never caches capability job polls (sw v11) — its cache-then-refresh would replay stale progress | correctness | sw.js API_NO_CACHE |
| 39 | Usage recorded by the three pollers (map, planner, KPI) → `aiusage` package, once per job (unique userId+jobId) | RunnerJobService hook would be bypassed by test mocks | aiusage/AiUsageService |
| 40 | Gap: map/planner jobs are polled by the browser — close the tab before it finishes and usage is only recorded if polled again | browser-driven polling | move polling server-side if it matters |
| 41 | AI usage page at `/projects/usage` (button in Projects header), not a tab | tabs would sit above the full-height board | js/pages/ai-usage.js |

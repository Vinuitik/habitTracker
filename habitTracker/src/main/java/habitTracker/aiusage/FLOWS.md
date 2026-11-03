# AI usage (token accounting of runner jobs)

Files: AiUsage.java, AiUsageRepository.java, AiUsageService.java, AiUsageController.java, static/ai-usage.html, static/js/pages/ai-usage.js, static/styles/pages/ai-usage.css, static/js/molecules/step-list.js (`tokens()`, `usage()`)

## Record (once per finished job)
Every poller passes each runner snapshot to `AiUsageService.record(userId, jobId, snap, Source)`:
- `Project/ProjectRunnerService.pollMap()` → `Source.map(projectId)` (kind `map`)
- `Project/PlannerService.poll()` → `Source.plan(projectId)` (kind `plan`)
- `KPI/RunnerCapabilityGenerator.run()` → `Source.kpi(kind, kpiName)`: `propose()` → `kpi_propose`; `generate()` → `kpi_generate`, or `kpi_repair` when inside `CapabilityAgentService.repairFor()` (thread-local `CapabilityProgress.repairing()/isRepair()`; covers manual "fix it" and nightly repair)

`record()` → state not terminal (`done|failed|error|cancelled|canceled`, `isTerminal()`) → skip → `tokens(snap.usage)` empty/zero → skip → `existsByUserIdAndJobId` → skip → insert `ai_usage` {userId, kind, projectId?, kpiName?, inputTokens, outputTokens, at, jobId} → `DuplicateKeyException` (concurrent poll) swallowed. Any other error → WARN log, never thrown (usage must not break a job).
- Once-guarantee: unique index (userId, jobId) created in `ensureIndexes()` (`auto-index-creation` is off).
- Token parsing `tokens()`: camelCase `inputTokens` (already includes cache reads/writes) + `outputTokens`; raw Anthropic snake_case → `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`, `output_tokens`; fallback `totalTokens|total_tokens`.
To change kinds: constants in `AiUsage`; sources: the three call sites above.

## Read
`GET /api/ai-usage?days=30` → `AiUsageController.summary()` → `AiUsageService.summary(currentUser, days∈[1,365])` → rows `at > now-days` (index userId+at) → in-memory: `totals{input,output}`, `byKind[{kind,tokens,input,output,runs}]`, `byProject[{projectId,name,tokens}]` (names via `ProjectRepository.findAllById`, owner-checked; missing → "Deleted project"), `byKpi[{kpiName,tokens}]`, `recent[{at,kind,label,tokens}]` (last 20). All lists sorted by tokens desc (recent by time).

## UI
`/projects/usage` (`PageController.aiUsage()` → ai-usage.html; "AI usage" button in the Projects index header; nav highlights Projects) → `ai-usage.js init()` → GET `ENV.ENDPOINTS.AI_USAGE(ENV.AI_USAGE.DAYS)` → big total · By kind · By project / By KPI (bars = `<progress class="progress">` atom) · Recent jobs. Nothing recorded → "No AI runs yet".
"Used N tokens" after a job: `StepList.usage(usage)` — KPI Automate dialog (kpi-list.js), planner (`planner.js jobHtml()`, from `usage` on the poll that saw the job finish), repo map (`repo-link.js`: toast + map status line + failed panel).
To change: window `ENV.AI_USAGE.DAYS`; kind labels `KIND_LABELS` in ai-usage.js; number format `StepList.tokens()`.

## Technology Notes
- Recorded only when a poll observes the terminal state. Map/planner are polled by the browser: if the tab is closed before the job ends, the job's usage is recorded on the next poll of that job (map: only if the same job is polled again; otherwise lost). KPI jobs are polled server-side, so they are always recorded (unless the runner drops mid-job).
- A job whose runner reports no `usage` is not recorded at all (no zero rows).
- A runner restart could reuse a job id for the same user → the second job would be silently skipped by the (userId, jobId) index. Accepted: ids are random on the runner today.
- Summary aggregates in Java over the window's rows; fine for ~20 users × hundreds of jobs, would need a Mongo aggregation at much larger volume.
- Package dependency: `Project` and `KPI` → `aiusage` (record), and `aiusage` → `Project.ProjectRepository` (names). Bean graph has no cycle (only the repository is injected).

## Change Index
| Thing | Where |
|---|---|
| Collection / fields | `AiUsage` (`ai_usage`) |
| Once-only index | `AiUsageService.ensureIndexes()` |
| What counts as finished | `AiUsageService.isTerminal()` |
| Token field parsing | `AiUsageService.tokens()` |
| Kind of a KPI job | `RunnerCapabilityGenerator.propose()/generate()`, `CapabilityProgress.repairing()` |
| API shape / window cap | `AiUsageService.summary()`, `MAX_DAYS`, `RECENT_MAX` |
| Endpoint URL | `AiUsageController`, `ENV.ENDPOINTS.AI_USAGE` |
| Page route | `PageController.aiUsage()`, `ENV.ROUTES.AI_USAGE`, `sw.js PAGE_ROUTES` |
| Page layout / styles | `js/pages/ai-usage.js render()`, `styles/pages/ai-usage.css` |
| "Used N tokens" line | `StepList.usage()` / `.step-list__usage` |

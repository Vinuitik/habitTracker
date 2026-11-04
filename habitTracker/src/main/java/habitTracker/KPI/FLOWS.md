# KPI Flows

Files: `CapabilityAgentController.java`, `CapabilityAgentService.java`, `CapabilityTaskService.java`, `CapabilityProgress.java`, `CapabilityGenerator.java`, `RunnerCapabilityGenerator.java`, `CapabilityPrompts.java`, `AgentCapabilityRepairTrigger.java`, `KPI.java`, `KPIData.java`, `KPIDTO.java`, `KPIDataDTO.java`, `KPIController.java`, `KPIService.java`, `KPIRepository.java`, `KPIHabitMappingRepository.java`, `KPICollectionNameUtil.java`, `DynamicKPIDataRepository.java`, `KPIProxyCapability.java`, `KPIProxyCapabilityRepository.java`, `SandboxCapabilityProvider.java`, `SandboxClient.java`, `HttpSandboxClient.java`, `CapabilityStatus.java`, `CapabilityPlatform.java`, `CapabilityGrants.java`, `CapabilitySecret.java`, `CapabilitySecretRepository.java`, `CapabilitySecretService.java`, `CapabilitySecretController.java`, `MissingSecretException.java`, `CapabilityLifecycleService.java`, `CapabilityOutput.java`, `ReadingPlausibility.java`

## Storage model

- `KPI` docs live in the `kpis` collection, one per user-defined metric. Unique per `{name, userId}` — two users (or the same user, historically) can have same-named KPIs.
- Each KPI's data points live in their **own** Mongo collection, named `kpi_data_<kpiId>` (`KPICollectionNameUtil.toCollectionName()`). Keyed by the KPI's own `_id`, not its name — a same-named KPI from a different user gets a different collection since ids never collide. `DynamicKPIDataRepository` wraps `MongoTemplate`'s dynamic-collection-name overloads (`find(query, KPIData.class, collectionName)`) to read/write it.
- `KPIHabitMapping` (collection `kpi_habit_mappings`) links a KPI to zero or more `Habit`s, scoped by `userId`.

To change collection naming: `KPICollectionNameUtil.toCollectionName()`.

## User isolation

Every public `KPIService` method scoped to a single user resolves `userId` via `SecurityUtils.getCurrentUserId()` and threads it through `KPIRepository.findByNameAndUserId` / `findByActiveAndUserId` — never `findByName` alone. Combined with id-keyed collections, this means a same-named KPI from another user is neither readable nor writable through the normal API surface.

The daily cron (`KPIDefaultFillService`, in `habitTracker.updater`) is the one caller that legitimately has no `userId` — see its own section in `updater/FLOWS.md`. It never uses `SecurityUtils`; it takes an already-resolved `KPI` object (which carries its own `userId`/`id`) and only ever touches that KPI's own collection.

## Request flow: log a data point

`POST /api/kpis/{name}/data?date=&value=` → `KPIController.addKPIData()` → `KPIService.addKPIData()`:
1. Resolve KPI by `(name, userId)` — 404-equivalent `IllegalArgumentException` if not found/not owned.
2. `saveKPIDataPoint(kpi, date, value, autoFilled=false)` — upsert by date (same `_id` preserved on update), recompute EMA, save into the KPI's collection.

A manual log always sets `autoFilled = false`, even overwriting a day the cron auto-filled — the human value wins and the flag clears.

## Default-fill (opt-in per KPI)

`KPI.autoFillEnabled` (bool) + `KPI.defaultValue` (nullable) — off by default. When enabled, the nightly cron (`KPIDefaultFillService`, see `updater/FLOWS.md`) fills in `defaultValue` for any day with no logged value, marking `KPIData.autoFilled = true`. Never overwrites an existing point (manual or previously auto-filled).

Set via:
- `POST /api/kpis/create` — `autoFillEnabled`/`defaultValue` in the JSON body.
- `PUT /api/kpis/{name}/default-fill` — post-creation update, same two fields.

Both paths validate: `defaultValue` is required whenever `autoFillEnabled = true` (`KPIService.createKPI()` / `updateDefaultFillSettings()`).

## Habit links (post-creation)

`habitIds` can also be set at creation (`POST /api/kpis/create`) and re-set any time after via
`PUT /api/kpis/{name}/habits` → `KPIService.updateKPIHabitMappings()` — full replace (deletes this
user's existing `KPIHabitMapping` rows for the KPI, then inserts the given `habitIds`, `[]`/`null`
clears all links). 404-equivalent `IllegalArgumentException` if the KPI name isn't owned by the
caller. UI: `static/kpi-list.html` "Manage Habits" button → `openManageHabitsModal()`, reusing the
same `/api/kpis/available-habits` list as the create form.

## EMA calculation

`KPIService.calculateEMA()` — 14-day EMA (smoothing factor `2/15`) over the last 30 points (`DynamicKPIDataRepository.findTopNOrderByDateDesc(30, ...)`). First point in a collection gets `EMA = value`. Drives `KPIDataDTO.trendDirection`/`colorIntensity` (see `convertToDataDTO`) and the dashboard chart's point coloring (`static/js/kpi-dashboard.js` `getTrendColor()`).

## Frontend

Two copies exist per page (`templates/kpi-*.html` and `static/kpi-*.html`); `PageController` forwards `/kpis*` routes to the `.html` paths under `static/` via Spring's static resource handler, so **`static/` is what's actually served** — `templates/kpi-*.html` appears to be a stale leftover.

- `static/kpi-create.html` — create form. Auto-fill checkbox reveals a default-value number input; client-side validates `defaultValue` is present when checked before POSTing.
- `static/kpi-list.html` + `static/js/kpi-list.js` — card grid with Add Data / View Charts / Auto-Fill / Delete per KPI. Cards opted into auto-fill show an "Auto-fill: N" badge. The Auto-Fill button opens a settings modal (`PUT .../default-fill`).
- `static/kpi-dashboard.html` + `static/js/kpi-dashboard.js` — Chart.js line charts (value + EMA trend). Auto-filled points render as a diamond (`rectRot` point style) instead of a circle, and the tooltip appends "— auto-filled".

## Sandboxed capability proxy (M7)

Nightly: `KPIProxyFillService.fillFromProxies()` → KPI with `proxyType=SANDBOX_CAPABILITY` → `ProxyHealthService.needsRepair()` (skip if breaker open) → `SandboxCapabilityProvider.fetchValue()`:
1. `KPIProxyCapabilityRepository.findFirstByKpiIdAndUserIdOrderByVersionDesc()` (collection `kpi_proxy_capabilities`, read fresh every run — editing needs no redeploy). Missing / not `ACTIVE` or `SHADOW` / not `SERVER` platform → `Optional.empty()` (counts as failure).
2. Prepends `TARGET_DATE = "<yesterday>"` to `sourceCode` → `CapabilitySecretService.requestFor()` (hosts + ONLY declared secrets; `MissingSecretException` → no run, `lastResult="NOT_RUN: secret X not set"`, counts as a failure) → `SandboxClient.run()` (`HttpSandboxClient`: `POST http://sandbox:8090/run`, see `/sandbox/FLOWS.md`).
3. `OK` → last non-blank stdout line parsed by `CapabilityOutput.parse()`: a plain number (old scripts) or `{"value": n, "unit"?: "min", "evidence"?: [{"label","value"}, ≤20 rows]}` (`parseLastLine` = its `.value`). The unit is stored on the capability (`lastUnit`) for the plausibility guard. Anything else (`NONZERO_EXIT`, `TIMEOUT`, `SIDECAR_ERROR`, unparseable stdout) → `Optional.empty()`.
4. Back in fill service (S1, SANDBOX_CAPABILITY only, before the anomaly check): `ProxyProvider.context()` (SHADOW? unit) + `ProxyHealthService.plausibilityReason()` → implausible → `KPIService.addFlaggedProxyDataForUser(.., "IMPLAUSIBLE", reason)`; SHADOW → same with flag `SHADOW`; both are forced `pending` (confirm-inbox), count as success, never trip the breaker. Otherwise: value → `isAnomalous()` → `KPIService.addKPIDataForUser(..., PROXY_CAPABILITY)` → `recordSuccess()`/`flagAnomaly()`; empty → `ProxyHealthService.recordFailure()` → N consecutive (`kpi.proxy.failure-threshold`, default 3) → `NEEDS_REPAIR`.
5. The provider also writes `lastRunAt` / `lastResult` / `consecutiveFailures` on the capability doc (diagnostics only; the breaker lives on the KPI).

Calls are sequential: a hanging script blocks the nightly loop for at most its timeout, then the next KPI/user runs normally.

To change script timeout: `sandbox.script-timeout-seconds` (`SANDBOX_SCRIPT_TIMEOUT_SECONDS`). To change sidecar URL: `sandbox.url` (`SANDBOX_URL`). To change HTTP read timeout: `sandbox.read-timeout-seconds`. To change the stdout contract: `SandboxCapabilityProvider.parseLastLine()`.

Egress + secrets: see "Network egress and secrets" below. [NOT IMPLEMENTED] Non-SERVER platforms (WINDOWS/ANDROID generation).

A capability row can still be inserted by hand (`status` must be `ACTIVE`; `_class` is added by Spring Data when saved via the repository):
`{userId, kpiId, platform:"SERVER", language:"python", sourceCode:"print(42)", status:"ACTIVE", version:1}` plus a KPI with `proxyType:"SANDBOX_CAPABILITY"`.

## Capability agent loop (M8, stages 1-4) + repair (M12)

Files: `CapabilityAgentController.java`, `CapabilityAgentService.java`, `CapabilityTaskService.java`, `CapabilityProgress.java`, `CapabilityGenerator.java`, `RunnerCapabilityGenerator.java`, `CapabilityPrompts.java`, `CapabilityGeneratorException.java`, `CapabilityRejectedException.java`, `CapabilityDTO.java`, `AgentCapabilityRepairTrigger.java`, `CapabilityRepairTrigger.java`; UI `static/kpi-list.html` + `static/js/kpi-list.js` (`openAutomateModal()`) + shared `static/js/molecules/step-list.js`; prompts `resources/prompts/kpi-capability-{propose,generate,previous-failure,sandbox-facts}.md`; runner side `relay/RunnerJobService`, `relay/OnlineRunnerSelector` (see `relay/FLOWS.md`). The old `backup/claude_cli.py` `/internal/agent/capability` endpoint is no longer called (removal pending a cleanup stage).

Async UI wrapper (the UI never calls the blocking endpoints below):
```
UI action (Propose / Approve and build / Regenerate / Fix it)
  → POST /api/kpis/{name}/capability/jobs {action: propose|approve|regenerate|repair, id?, description?}
  → CapabilityAgentController.startJob() (userId captured on the request thread) → CapabilityTaskService.start()
      bad action / missing id → 400; a job already running for (user, KPI) → 409 (UI then resumes it via /jobs/active)
  → 202 {jobId}; worker pool (4 daemon threads) → CapabilityProgress.with(sink) → CapabilityAgentService.proposeAs/approveAs/regenerateAs/repairAs(userId, ...)
      service stages → CapabilityProgress.step(): "Drafting a plan on your runner" / "Writing the script on your runner" / "Testing the script in the sandbox for <date>"
      RunnerCapabilityGenerator.forwardEvents(): runner job events (kind step|text, ≤200 chars) → same list; job usage → CapabilityProgress.usage()
  → GET .../capability/jobs/{jobId}?after=<nextSeq> every ENV.TIMING.KPI_CAP_POLL_MS (1.5s)
      → {jobId, state: running|done|failed, events:[{seq,kind,text}] (seq > after), nextSeq, result?: CapabilityDTO,
         error?: one sentence, code?: runner_offline|runner_busy|generator|rejected|invalid|conflict|internal,
         usage?: {inputTokens, outputTokens, totalTokens} (summed over the task's runner jobs)}
      TEST_FAILED result = state done + an "error" event "Did not pass"; unknown/expired/foreign job → 404
  → GET .../capability/jobs/active → {jobId} | 204   (reopened dialog resumes watching)
```
Each runner job is also recorded in `ai_usage` by `RunnerCapabilityGenerator.run()` → `AiUsageService.record()` as `kpi_propose` / `kpi_generate` / `kpi_repair` (repair = inside `CapabilityAgentService.repairFor()`, flagged by `CapabilityProgress.repairing()`; includes the nightly repair). See `aiusage/FLOWS.md`.
To change stage texts: `CapabilityAgentService` `CapabilityProgress.step()` calls. Error → code mapping: `CapabilityTaskService.run()`. Token counting: `CapabilityTaskService.Task.addUsage()`. Pool size / retention: `CapabilityTaskService.WORKERS`, `KEEP_FINISHED_MS` (15 min).

Blocking endpoints (`/propose`, `/{id}/approve`, `/{id}/regenerate`, `/repair`) are kept unchanged for API callers and the integration tests; the nightly repair (`AgentCapabilityRepairTrigger`) calls `repairFor()` directly and never binds a progress sink (all `CapabilityProgress` calls are no-ops there).

Status machine (`CapabilityStatus`): `PROPOSED → PENDING_TEST → AWAITING_CONFIRM → SHADOW → ACTIVE | TEST_FAILED` (S1; see "Confirm gate + shadow" below); `REJECTED` (user said no / superseded); `NEEDS_REPAIR` (old version while a repair version is built) → `DISABLED` once the new one is ACTIVE.

```
1-2 PROPOSE   POST /api/kpis/{name}/capability/propose {description}
              → CapabilityAgentController.propose() → CapabilityAgentService.propose()
              → (409 if latest version is ACTIVE) → CapabilityGenerator.propose()
              → RunnerCapabilityGenerator.run(): OnlineRunnerSelector.pick(userId) (online uplink; most recent job use,
                then lastSeenAt) → none → CapabilityGeneratorException.noRunner() → 503 {"error":"Connect a runner to generate this capability"}
              → CapabilityPrompts.proposePrompt() → RunnerJobService.startJob(kind "kpi", no project, maxTurns 3)
              → pollJob() every kpi.capability.poll-ms (2s) until state done/failed, or kpi.capability.job-timeout-ms (300s) → cancelJob() + 502
              → CapabilityPrompts.parseProposal(result) → {proposal, platform, declaredCapabilities, measures?, dataSource?}
                (measures / dataSource = optional one-sentence summaries for the UI; stored on the version, copied on repair)
              → checkGrantable(): platform != SERVER, or any network host / secret declared → 422, NOTHING stored
              → older PROPOSED/TEST_FAILED rows → REJECTED; new KPIProxyCapability v(latest+1) status=PROPOSED
              → UI shows plain-language facts (Measures / Data comes from / Updates: nightly / Needs from you),
                the full plan collapsed, internet hosts as chips, secret inputs
3-4 APPROVE   POST .../capability/{id}/approve  (approval IS the permission grant)
              → status must be PROPOSED; checkGrantable() re-run on the stored spec
              → attempt(): PENDING_TEST → CapabilityGenerator.generate(owner, ...) (same runner job path;
                CapabilityPrompts.generatePrompt(proposal, declaredCapabilities, previousCode?, previousError?) → parseSourceCode())
                → sourceCode stored on this version, attempts++
              → TEST GATE: SandboxClient.run("TARGET_DATE = <yesterday>\n" + sourceCode) (M7 sandbox)
              → must be OK + last stdout line a finite number or the JSON form (CapabilityOutput.parse)
              → pass: sample {value, unit, evidence, date} stored on the version → AWAITING_CONFIRM (S1; the KPI is NOT wired yet).
                Repair versions (`repair=true`) skip the confirm and go ACTIVE (SHADOW again if the replaced version was still on trial)
                via CapabilityLifecycleService.testGatePassed() → wire(): older live versions → DISABLED,
                KPI.proxyType=SANDBOX_CAPABILITY, proxyStatus=ACTIVE, consecutiveProxyFailures=0
              → fail: TEST_FAILED + failureReason (visible in UI, GET .../capability)
RETRY         POST .../capability/{id}/regenerate  (only TEST_FAILED, attempts < MAX_ATTEMPTS=3, else 409)
              → same attempt(), previousCode + failureReason fed into the prompt
              runner offline/busy/failed/timeout/bad-JSON → TEST_FAILED "Code generation failed: ..." but do NOT count as an attempt
              (no runner → failureReason "Code generation failed: Connect a runner to generate this capability"; regenerate once connected)
REJECT        POST .../capability/{id}/reject (PROPOSED or TEST_FAILED → REJECTED; UI "Start over")
REPAIR (M12)  proxy breaker trips → ProxyHealthService.recordFailure()/flagAnomaly() → CapabilityRepairTrigger.attemptRepair(kpi)
              → AgentCapabilityRepairTrigger (single background thread; non-SANDBOX_CAPABILITY KPIs only logged)
              → CapabilityGenerator.runnerAvailable(owner)? no → parked in in-memory `waiting` (kpiId→userId), nothing counted
                → retryWaiting() every kpi.repair.runner-check-ms (60s): owner has a runner online → re-read KPI
                → still SANDBOX_CAPABILITY + NEEDS_REPAIR → dispatch; else dropped
              → CapabilityAgentService.repairFor(userId, kpi, autoRetry=true)
              → runner drops mid-repair → result TEST_FAILED with the no-runner reason (waitingForRunner()) → auto-retry loop
                stops, KPI parked again; next dispatch continues on that TEST_FAILED version (attempts unchanged)
              → old version → NEEDS_REPAIR; NEW version (copy of spec + old code, failureReason seeded from old lastResult)
              → attempt() up to MAX_ATTEMPTS → pass clears KPI NEEDS_REPAIR (as above); fail leaves it for the manual button
              Manual "Fix it": POST .../capability/repair → repairFor(.., autoRetry=false), one attempt per click
              (409 unless KPI.proxyStatus=NEEDS_REPAIR, or latest is a TEST_FAILED repair version, which is then regenerated)
```

Auth/scoping: KPI resolved by `(name, currentUser)`, capability only via `KPIProxyCapabilityRepository.findByIdAndUserIdAndKpiId()` — never `findById`. Foreign id or KPI → 400 "not found" (no existence leak). Tested in `CapabilityAgentIntegrationTest.anotherUser_cannotSeeOrDriveMyCapability` (cf. commit `b8a1057`).

Enforcement: the **gate** is `CapabilityAgentService.checkGrantable()` → `CapabilityGrants.normalize()` (propose, approve, retry): SERVER only; `declaredCapabilities.network` entries must be plain public FQDNs (lowercased/deduped; no IPs, ports, wildcards, URLs, single-label or internal names such as `localhost`, `javaapp`, `mongodbHabit`, `egress-proxy`, `*.internal`, `*.local`), <= 10 hosts / 10 secrets; secret names must be env-var style and not reserved (`PATH`, `HTTP*`, `PYTHON*`, `LD_*`, `SSL_*`...). Violations → 422 "... cannot be granted". **Approving the proposal is the grant** for exactly the stored (normalized) lists. The **boundary** is the sandbox + proxy: runs without hosts are `--network none`; with hosts they reach only those hosts through `egress-proxy` (see `/egress-proxy/FLOWS.md`).

### Confirm gate + shadow (S1; kpi-sensors.md "Test pipeline" 5-6)
Files: `CapabilityLifecycleService.java` (platform-agnostic state machine: no sandbox/runner types), `CapabilityOutput.java`, `ReadingPlausibility.java`, `ProxyHealthService.plausibilityReason()`, `KPIProxyFillService`, UI `kpi-list.js renderAutomateConfirm()`.
```
test gate passes (first build)  → CapabilityLifecycleService.testGatePassed() → AWAITING_CONFIRM (sample on the version; KPI not wired)
  UI (live-steps panel stays above): "Value for <date>: 47 min" + evidence rows + [Looks right] [Wrong, because…] [Start over]
Looks right  POST .../capability/{id}/confirm (instant) → confirmRight() → SHADOW, shadowUntil = today + kpi.shadow.days (7), KPI wired
              (0 days → straight ACTIVE)
Wrong        job action "wrong" {id, description=reason} (blocking twin: POST .../{id}/wrong {reason})
              → CapabilityAgentService.wrongAs(): recordWrong() (confirmRounds++) → 3rd "wrong" → TEST_FAILED + "You said the number was wrong
                3 times, so I stopped. Start over ..." ; else failureReason = CapabilityLifecycleService.wrongFeedback() (value, evidence, reason)
                → attempt() (generate with previousCode+that text → sandbox test gate) → AWAITING_CONFIRM again.
                Not limited by MAX_ATTEMPTS (test-gate failures inside a round still count; Regenerate is then hidden at 3).
SHADOW       nightly KPIProxyFillService: readings stored pending=true, flag="SHADOW" (every reading, not sampled: confirm-inbox shows all 7)
              → CapabilityLifecycleService.promoteDueShadows() @Scheduled kpi.shadow.promote-cron (0 30 0 * * ?) → ACTIVE when shadowUntil <= today
ACTIVE or SHADOW  plausibility guard on every nightly reading: non-finite; negative while the unit is in ReadingPlausibility.NON_NEGATIVE_UNITS;
              or > 5× median of the last 14 trusted readings (needs ≥5, median > 0; earlier IMPLAUSIBLE-pending points are ignored)
              → stored pending, flag="IMPLAUSIBLE", flagReason (plain language) → confirm-inbox; breaker NOT touched.
```
`KPIData.flag`/`flagReason` are cleared by confirm (`KPIService.confirmKPIData`) and edit. Everything is additive: pre-S1 documents have none of the new fields; existing ACTIVE capabilities are never touched by the promotion job (it only reads status SHADOW).
Reuse for another platform: produce a `CapabilityOutput` from its test run and call `CapabilityLifecycleService.testGatePassed(cap, kpi, out, date, skipConfirm)`; `wire()` currently sets `KPI.proxyType=SANDBOX_CAPABILITY`, so a RUNNER platform needs its own ProxyType there. [NOT IMPLEMENTED] showing `flagReason` in the /today confirm chips (the DTO already carries `flag`, `flagReason`).

## Network egress and secrets

```
UI Automate modal (kpi-list.js): while a job runs → StepList rows (● running + seconds, ✓ done) + "Used N tokens" when usage came back;
  result → renderAutomateSummary() facts + renderAutomateCode() (<details>, monospace <pre>); TEST_FAILED → describeFailure() maps
  failureReason prefixes ("Code generation failed:", "Test gate failed: secret X not set / TIMEOUT / SIDECAR_ERROR / exited 0 …")
  to one sentence + what to do, raw script output only inside a collapsed "What the script printed". All text via textContent / Dom.esc.
UI Automate modal: proposal shows declared hosts + secrets; per secret a write-only password input ("set"/"not set")
  → PUT/DELETE /api/kpi-secrets/{NAME} {value}  (CapabilitySecretController → CapabilitySecretService.set/delete)
      scoped by SecurityUtils.getCurrentUserId(); stored encrypted in `kpi_capability_secrets` (id = userId:NAME);
      GET /api/kpi-secrets returns NAMES only — no endpoint ever returns a value
approve / regenerate / repair → attempt():
  → CapabilitySecretService.missing(owner, declared secrets) non-empty → TEST_FAILED "secret X not set. Set it in the Automate dialog, then retry." (no LLM call, no sandbox run, attempt not consumed)
  → generate code → CapabilitySecretService.requestFor(): hosts = declared network; secrets = decrypt ONLY declared names for cap.userId
  → SandboxClient.Request{allowedHosts, secrets} → HttpSandboxClient → POST sandbox:8090/run → env-file + proxy token (see sandbox/FLOWS.md)
  → HttpSandboxClient.redact() second-pass scrubs secret values from stdout/stderr before they are stored in failureReason/lastResult or fed to an LLM repair prompt
nightly: SandboxCapabilityProvider.fetchValue() → same requestFor() with kpi.userId; a deleted secret = failure (breaker) with "NOT_RUN: secret X not set"
```
Encryption: `VaultEncryptionService.encryptString()/decrypt()` (AES-256-GCM, reused, no new crypto) with key `KPI_SECRETS_KEY` (base64 32 bytes; `kpi.secrets.key`), falling back to a SHA-256 derivation of `JWT_SECRET` when blank. To change: `CapabilitySecretService` constructor. Tests: `CapabilityEgressIntegrationTest` (CRUD, ciphertext at rest, cross-user isolation, only-declared payload, missing-secret gate, nightly path, redaction), `CapabilityGrantsTest`.

Errors: 400 bad input/not found, 409 conflict (wrong status, attempt cap, already ACTIVE, generator busy), 422 proposal not grantable, 502 generator failure.

To change the attempt cap: `CapabilityAgentService.MAX_ATTEMPTS`. To change what is grantable: `CapabilityGrants` (host/secret-name rules; keep `sandbox/app.py` `validate_extras()` in sync) and `CapabilityAgentService.checkGrantable()`. To change test-gate sample date/plausibility: `CapabilityAgentService.attempt()`. To change prompts: `resources/prompts/kpi-capability-*.md` (`{{PLACEHOLDER}}`s, filled once by `CapabilityPrompts.fill()`); JSON contract: `CapabilityPrompts.parseProposal()` / `parseSourceCode()`. To change runner choice: `OnlineRunnerSelector.pick()`. To change job timing: `kpi.capability.job-timeout-ms`, `kpi.capability.poll-ms`; repair wake-up: `kpi.repair.runner-check-ms`. To change repair behaviour: `AgentCapabilityRepairTrigger` / `CapabilityAgentService.repairFor()`.

[NOT IMPLEMENTED] Intake as a multi-turn conversation (today: one free-text description → one proposal; reject + re-describe to iterate). Disabling/removing an ACTIVE capability (propose is 409 while one is ACTIVE). Stage 6-7 scrutiny/confirm-rate tapering for new capabilities. Plausibility check against KPI history in the test gate (only "exit 0 + finite number").  Listing/deleting secrets that no capability uses anymore happens only via the API (no UI screen outside the Automate modal). Per-capability secret rotation reminders.

## Technology Notes

- **Docker socket = host root.** Only the `sandbox` container mounts `/var/run/docker.sock`; `javaapp` never does. Anyone who can make the sidecar run arbitrary docker args owns the host, so the sidecar hardcodes the `docker run` flags (`build_docker_cmd`) and accepts only `{code, timeout}`. It has NO authentication: it relies on being reachable only on the compose network (no published port). Anything else on that network (any container, a compromised `javaapp`) can run code in a capped sandbox, but cannot choose docker flags.
- **Sidecar down / restarting:** `HttpSandboxClient` turns connection errors into `SIDECAR_ERROR` → counts as a failure per KPI. A multi-night outage therefore trips every sandbox KPI's breaker to `NEEDS_REPAIR` after 3 nights, needing a manual reset (`PUT /api/kpis/{name}/proxy/reset`). On sidecar start it force-removes leftover `kpi-sandbox=1` containers.
- **Concurrency:** sidecar semaphore (`SANDBOX_MAX_CONCURRENT`, default 4); waiters give up after 60s with `SIDECAR_ERROR`. `javaapp` calls sequentially anyway. HTTP read timeout (150s) must stay above script timeout + queue wait + pull, or `javaapp` gives up while the container still runs (it is still killed by the sidecar).
- **Timeouts:** enforced in the sidecar (clamped to `SANDBOX_MAX_TIMEOUT_SECONDS`=60) with an explicit `docker rm -f`, since `--rm` does not fire when the client is killed. An OOM kill shows as exit 137 → `NONZERO_EXIT`.
- **Image pull:** runner image `python:3.12-slim` is pulled lazily on first run (before the script timer starts, up to 180s), not at sidecar start. First night after deploy is slower; no internet at that moment = `SIDECAR_ERROR`. The image is never auto-updated afterwards.
- **Isolation limits:** per-run container: 128m, 0.25 cpu, `--network none` (or the `internal` egress network + proxy when hosts are declared), pids 64, all caps dropped, read-only rootfs + 16m tmpfs, uid 65534. Containers share the host kernel (no gVisor); a kernel escape is out of scope per the staged-safety posture. Those container memory caps are on top of the sidecar's own 64m `mem_limit`.
- **Script contract:** python only, source <= 64KB, output truncated at 64KB, value = last non-blank stdout line. Scripts get `TARGET_DATE` as a module constant, their declared secrets as env vars, and proxy env vars if hosts are declared; nothing else.
- **Not persisted:** run output beyond 300 chars of `lastResult`; no audit log of executed code besides the capability doc.
- **Generated code is untrusted (M8).** It is LLM output steered by a free-text user description; it is only ever executed by the sandbox sidecar (never in `javaapp`, never on the host), `--network none` unless it declared (and the user approved) specific hosts, only its own declared secrets, capped CPU/mem/time. The UI renders it and all LLM text with `textContent` (no HTML injection). Approval gates *what is requested*; the sandbox, not the review, is the actual safety boundary.
- **Generation = the KPI owner's own Claude Code via their paired Relay runner** (job kind `kpi`: no project, no tools, text only). The server spends no Claude credits. No runner online → propose is 503 "Connect a runner to generate this capability"; approve/regenerate/repair end TEST_FAILED with that reason (no attempt consumed). If the runner's Claude login expired, the job ends `failed` and its error is shown. Existing ACTIVE capabilities keep running without any runner (they never call the LLM).
- **Runner choice:** `OnlineRunnerSelector` = devices with a live uplink in *this* JVM's `UplinkRegistry`; preference "most recently used for a job" is in-memory (lost on restart → falls back to `lastSeenAt`). A user with two online runners may get consecutive jobs on the same one. Busy runner (409/429 on start/poll) → 409 "Your runner is busy".
- **Async jobs are in-memory, single-node** (`CapabilityTaskService.tasks`): a javaapp restart loses every job and its event log (the poll then 404s and the UI reloads the stored capability status); the capability version itself is in Mongo, but a restart mid-attempt leaves it `PENDING_TEST`. Max 4 jobs run at once across all users (more queue in the executor); one running job per (user, KPI). Finished jobs are kept 15 min, evicted lazily on the next start. Progress reaches the job via a `ThreadLocal` (`CapabilityProgress`) bound only on the worker thread, so nothing changes for blocking callers. The service worker (`static/sw.js`, `API_NO_CACHE`) never caches `/api/kpis/*/capability*` GETs, otherwise stale-while-revalidate would replay old poll snapshots.
- **Token usage** is whatever the runner job reports in `usage` (`input_tokens`/`inputTokens` + `cache_creation_input_tokens` + `output_tokens`/`outputTokens`, or `total_tokens`); absent → no "Used N tokens" line. Cache reads are not counted.
- **Error text:** runner HTTP error bodies (JSON) are logged, never shown (`RunnerCapabilityGenerator.fromCall()`); unparseable model replies say "The AI's reply could not be used (...). Try again" (`CapabilityPrompts.bad()`).
- **Blocking poll.** propose/approve/regenerate/repair hold the request thread while polling the runner job (every 2s, up to 300s; each poll is itself a blocking uplink call bounded by `relay.proxy.timeout-ms`) + sandbox time (up to ~60s). On timeout the job is cancelled. A runner that disconnects mid-job → treated as no runner (job state lives only on the runner, see `relay/FLOWS.md`). Hub restart mid-generation loses the waiting request; the runner job finishes but its result is discarded. a reverse proxy (Caddy/Cloudflare ~100s edge timeout) in front may cut the browser connection while the server keeps working: reopening the dialog shows the resulting state (`GET .../capability`). `PENDING_TEST` is transient in-flight state; a javaapp crash mid-attempt leaves a stuck `PENDING_TEST` row (no sweeper; propose again supersedes it).
- **LLM output is not trusted to be JSON.** `CapabilityPrompts.parseProposal()/parseSourceCode()` (a Java port of the old `claude_cli.parse_capability_result()`) extracts the first `{`..last `}` object, validates keys/types and the 64KB cap; anything else → 502 / "Code generation failed" (not counted as an attempt). Fenced code inside `sourceCode` is stripped.
- **Test gate is weak by design.** One dry run with no real data (no network, so nothing external to read): it proves "runs, exits 0, prints a number", not "prints the right number". Backstops (S1): the user's AWAITING_CONFIRM check against evidence, then 7 days of SHADOW readings in the confirm-inbox, then the plausibility guard forever. The check is only as good as the evidence the script prints (an old plain-number script gives the user nothing to verify) and the guard can't catch a consistently wrong number.
- **Shadow/promotion state is time-based and lives in Mongo** (`shadowUntil`); the promotion job runs in the javaapp JVM at 00:30 (a missed night is caught up the next, it compares dates). `kpi.shadow.days` changes apply to new confirmations only. Plausibility history is the last 14 stored points; with <5 only impossible values are flagged. The unit-can't-be-negative list is a hardcoded set (`ReadingPlausibility.NON_NEGATIVE_UNITS`); unknown units may be negative.
- **Auto-repair runs on one background daemon thread** (`AgentCapabilityRepairTrigger`): lost on restart (KPI then just stays `NEEDS_REPAIR` for the manual button), and one repair at a time across all users (a slow runner delays everyone's repairs).
- **Repairs waiting for a runner are in-memory only** (`AgentCapabilityRepairTrigger.waiting`): a javaapp restart forgets them; the KPI stays `NEEDS_REPAIR` (breaker open, nothing runs) until the next breaker trip re-fires the trigger or the user clicks "Fix it". Wake-up is a 60s poll, not a connect hook, so relay stays free of KPI dependencies; worst case a repair starts ~60s after the runner connects. Waiting never trips or resets the breaker and never consumes an attempt. A new repair version is non-ACTIVE until its test gate passes, so `SandboxCapabilityProvider` (which reads the latest version) runs nothing for that KPI in the meantime; it was already skipped by the open breaker.

## Change Index

| What to change | Where | Note |
|---|---|---|
| Collection naming scheme | `KPICollectionNameUtil.toCollectionName()` | currently `kpi_data_<kpiId>` |
| EMA window / smoothing | `KPIService.calculateEMA()` | 30-point lookback, 14-day smoothing factor |
| Default-fill opt-in validation | `KPIService.createKPI()` / `updateDefaultFillSettings()` | `defaultValue` required when `autoFillEnabled=true` |
| Default-fill write path | `KPIService.fillDefaultIfMissing()` / `saveKPIDataPoint()` | never overwrites an existing data point |
| Default-fill cron trigger | `habitTracker.updater.KPIDefaultFillService` | see `updater/FLOWS.md` |
| Auto-filled chart marker | `static/js/kpi-dashboard.js` `renderChart()` `pointStyle` | diamond vs circle |
| Auto-fill UI (create) | `static/kpi-create.html` | checkbox + conditional number input |
| Auto-fill UI (edit) | `static/kpi-list.html` `openAutoFillModal()` | modal → `PUT /api/kpis/{name}/default-fill` |
| Habit links, post-creation | `KPIService.updateKPIHabitMappings()` | `PUT /api/kpis/{name}/habits`, full replace |
| Habit links UI (edit) | `static/kpi-list.html` `openManageHabitsModal()` | modal → `PUT /api/kpis/{name}/habits` |
| Sandbox proxy provider | `SandboxCapabilityProvider.fetchValue()` | `ProxyType.SANDBOX_CAPABILITY` |
| Capability storage | `KPIProxyCapability` / `KPIProxyCapabilityRepository` | collection `kpi_proxy_capabilities` |
| Script timeout | `sandbox.script-timeout-seconds` / `SANDBOX_SCRIPT_TIMEOUT_SECONDS` | sidecar clamps to its own max |
| Sidecar URL / HTTP timeout | `sandbox.url`, `sandbox.read-timeout-seconds` / `HttpSandboxClient` | default `http://sandbox:8090` |
| Stdout -> value parsing | `CapabilityOutput.parse()` (`SandboxCapabilityProvider.parseLastLine()` = `.value`) | last non-blank line: number or JSON `{value, unit?, evidence?}`, evidence cap `MAX_EVIDENCE` 20 |
| Evidence/unit instructions in the generation prompt | `resources/prompts/kpi-capability-sandbox-facts.md` | |
| Confirm gate (right / wrong) | `CapabilityAgentService.confirm()` / `wrong()`, `CapabilityLifecycleService` | `POST .../{id}/confirm`, job action `wrong` |
| Max "wrong" rounds | `CapabilityLifecycleService.MAX_CONFIRM_ROUNDS` | 3 |
| Shadow length / promotion | `kpi.shadow.days` (`KPI_SHADOW_DAYS`), `kpi.shadow.promote-cron`, `CapabilityLifecycleService.promoteDueShadows()` | |
| Plausibility rules | `ReadingPlausibility.check()` (5× median, ≥5 readings, window 14, non-negative units), `ProxyHealthService.plausibilityReason()` | |
| Flagged readings | `KPIService.addFlaggedProxyDataForUser()`, `KPIData.flag/flagReason` | `SHADOW` / `IMPLAUSIBLE`, forced `pending` |
| Confirm UI | `kpi-list.js renderAutomateConfirm()` / `automateConfirm()`, `.automate__sample/__evidence*` in `kpi.css`, `ENV.ENDPOINTS.KPI_CAP_CONFIRM` | |
| Automate UI | `static/kpi-list.html` `#automateModal`, `static/js/kpi-list.js` `openAutomateModal()` / `renderAutomate()` | styles in `styles/pages/kpi.css` `.automate__*` |
| Automate plain-language facts / failure sentences | `kpi-list.js` `renderAutomateSummary()`, `describeFailure()` | "Updates" text is fixed (nightly fill) |
| Automate job polling | `kpi-list.js` `automateStart()` / `automatePoll()`, `ENV.TIMING.KPI_CAP_POLL_MS`, `ENV.ENDPOINTS.KPI_CAP_*` | |
| Step rows (shared with planner) | `static/js/molecules/step-list.js`, `styles/molecules/step-list.css` | |
| Async capability jobs | `CapabilityTaskService`, `CapabilityAgentController.startJob()/pollJob()/activeJob()` | `WORKERS`, `KEEP_FINISHED_MS` |
| Progress events | `CapabilityProgress`, `RunnerCapabilityGenerator.forwardEvents()` | no-op without a bound sink |
| Agent endpoints | `CapabilityAgentController` | `/api/kpis/{name}/capability/...` |
| Pipeline (propose/approve/test gate/retry) | `CapabilityAgentService` | `propose()`, `approve()`, `attempt()`, `regenerate()` |
| Attempt cap | `CapabilityAgentService.MAX_ATTEMPTS` | 3 |
| What may be granted | `CapabilityAgentService.checkGrantable()` | rejects network/secrets/non-SERVER |
| LLM transport (Java) | `RunnerCapabilityGenerator` | `kpi.capability.job-timeout-ms` (300000), `kpi.capability.poll-ms` (2000); swap via `CapabilityGenerator` |
| Which runner runs the job | `OnlineRunnerSelector.pick()` | online uplink, last job use, then `lastSeenAt` |
| LLM prompt text | `resources/prompts/kpi-capability-*.md` | `{{KPI_NAME}}`, `{{DESCRIPTION}}`, `{{PROPOSAL}}`, `{{DECLARED}}`, `{{SANDBOX_FACTS}}`, `{{PREVIOUS_FAILURE}}` |
| LLM JSON contract | `CapabilityPrompts` | `parseProposal()`, `parseSourceCode()`, `MAX_SOURCE_BYTES` |
| "Connect a runner" message | `CapabilityGeneratorException.NO_RUNNER_MESSAGE` | 503 in `CapabilityAgentController.generator()` |
| Repair waiting for runner | `AgentCapabilityRepairTrigger.retryWaiting()` | `kpi.repair.runner-check-ms` (60000) |
| Auto-repair trigger | `AgentCapabilityRepairTrigger` | replaces former `LoggingCapabilityRepairTrigger` |
| Manual "Fix it" | `CapabilityAgentService.repair()` | `POST .../capability/repair` |
| Container limits / flags | `sandbox/app.py` `build_docker_cmd()`, `SANDBOX_MEM`, `SANDBOX_CPUS` | see `/sandbox/FLOWS.md` |
- **Secrets at rest/in flight (egress feature):** encrypted in Mongo with `KPI_SECRETS_KEY` (or a key derived from `JWT_SECRET` if unset: then rotating `JWT_SECRET` makes every stored secret undecryptable; `resolve()` throws and runs fail, users must re-enter). A DB dump alone does not reveal values; a dump plus the env key does. In flight they pass javaapp → sidecar as plaintext JSON over the internal compose network (no TLS, no sidecar auth), then sit in a 0600 temp file during the run and in the container env (`docker inspect` shows them while it runs). Redaction of output is best-effort (encoded output evades it). Details and the hostname-only allowlist caveats: `sandbox/FLOWS.md`, `egress-proxy/FLOWS.md`.
- **Proxy / egress failure modes:** `egress-proxy` down or `SANDBOX_EGRESS_SECRET` unset => every capability that declares hosts fails (`SIDECAR_ERROR` or a script-side proxy error) and feeds the circuit breaker (3 nights → `NEEDS_REPAIR`) and the auto-repair loop; hostless capabilities are unaffected.

# KPI Flows

Files: `CapabilityAgentController.java`, `CapabilityAgentService.java`, `CapabilityTaskService.java`, `CapabilityProgress.java`, `CapabilityGenerator.java`, `RunnerCapabilityGenerator.java`, `CapabilityPrompts.java`, `AgentCapabilityRepairTrigger.java`, `KPI.java`, `KPIData.java`, `KPIDTO.java`, `KPIDataDTO.java`, `KPIController.java`, `KPIService.java`, `KPIRepository.java`, `KPIHabitMappingRepository.java`, `KPICollectionNameUtil.java`, `DynamicKPIDataRepository.java`, `KPIProxyCapability.java`, `KPIProxyCapabilityRepository.java`, `CapabilityStatus.java`, `CapabilityPlatform.java`, `RunnerOnlyKpiMigration.java`, `CapabilityLifecycleService.java`, `CapabilityOutput.java`, `ReadingPlausibility.java`, `RunnerCollectorPipeline.java`, `RunnerCollectorProvider.java`, `CollectorBundle.java`, `CollectorStaticCheck.java`, `ProxyReadingRecorder.java`, `CapabilityWentLive.java`, `GoalType.java`, `KPITarget.java`, `KPITargetStatus.java`

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

## Runner-only capabilities (docs/designs/runner-only-kpis.md H1)

No user-influenced code runs on the hub. Every AI-built capability is a RUNNER collector (see "RUNNER platform" below); the agent is never offered a platform choice. The former SERVER path (sandbox sidecar, egress proxy, per-user capability secrets, `ProxyType.SANDBOX_CAPABILITY`, `CapabilityPlatform.SERVER/WINDOWS`) is removed. `CapabilityPlatform.ANDROID` stays as a reserved placeholder [NOT IMPLEMENTED].

Legacy data, once at startup:
```
RunnerOnlyKpiMigration.migrate() (@PostConstruct; UpdateScheduler @DependsOn it, so the startup fill sees migrated docs)
  → _migration {_id: "runner-only-kpis-v1"} exists → no-op
  → kpi_proxy_capabilities platform SERVER|WINDOWS → status RETIRED, retiredPlatform = old value, platform = null
  → kpis proxyType SANDBOX_CAPABILITY → proxyType NONE (manual), proxyStatus ACTIVE, consecutiveProxyFailures 0
  → drop kpi_capability_secrets → write the _migration flag
```
All writes are raw (`MongoTemplate` + collection names): a doc still holding a removed enum value cannot be mapped. A RETIRED version is never run; the Automate dialog treats it like REJECTED (describe form = start over). Old `PROXY_CAPABILITY` data points stay.
To change: `RunnerOnlyKpiMigration` (bump `MIGRATION_ID` to re-run a changed migration).

## Capability agent loop (M8, stages 1-4) + repair (M12)

Files: `CapabilityAgentController.java`, `CapabilityAgentService.java`, `CapabilityTaskService.java`, `CapabilityProgress.java`, `CapabilityGenerator.java`, `RunnerCapabilityGenerator.java`, `CapabilityPrompts.java`, `CapabilityGeneratorException.java`, `CapabilityRejectedException.java`, `CapabilityDTO.java`, `AgentCapabilityRepairTrigger.java`, `CapabilityRepairTrigger.java`; UI `static/kpi-list.html` + `static/js/kpi-list.js` (`openAutomateModal()`) + `styles/organisms/automate-dialog.css` + shared `static/js/molecules/step-list.js`; prompts `resources/prompts/kpi-capability-{propose,refine,previous-failure}.md` + `kpi-collector-generate.md`; runner side `relay/RunnerJobService`, `relay/OnlineRunnerSelector` (see `relay/FLOWS.md`). The old `backup/claude_cli.py` `/internal/agent/capability` endpoint is no longer called (removal pending a cleanup stage).

Async UI wrapper (the UI never calls the blocking endpoints below):
```
UI action (Propose / Approve and build / Regenerate / Fix it)
  → POST /api/kpis/{name}/capability/jobs {action: propose|approve|regenerate|repair|wrong|contract|sensor|refine, id?, description? (refine: feedback, 1..2000 chars)}
  → CapabilityAgentController.startJob() (userId captured on the request thread) → CapabilityTaskService.start()
      bad action / missing id → 400; a job already running for (user, KPI) → 409 (UI then resumes it via /jobs/active)
  → 202 {jobId}; worker pool (4 daemon threads) → CapabilityProgress.with(sink) → CapabilityAgentService.proposeAs/approveAs/regenerateAs/repairAs(userId, ...)
      service stages → CapabilityProgress.step(): "Drafting a plan on your runner", then RunnerCollectorPipeline's steps (sensors, write, fixtures, dry run)
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

Status machine (`CapabilityStatus`): `PROPOSED → PENDING_TEST → AWAITING_CONFIRM → SHADOW → ACTIVE | TEST_FAILED` (S1; see "Confirm gate + shadow" below); `REJECTED` (user said no / superseded); `RETIRED` (ex-SERVER/WINDOWS version, see above); `AWAITING_SENSOR` / `AWAITING_CONTRACT` (RUNNER); `NEEDS_REPAIR` (old version while a repair version is built) → `DISABLED` once the new one is ACTIVE. Refine (below) is not a status: from `PROPOSED` / `TEST_FAILED` it creates a NEW `PROPOSED` version and rejects the old one.

```
1-2 PROPOSE   POST /api/kpis/{name}/capability/propose {description}
              → CapabilityAgentController.propose() → CapabilityAgentService.propose()
              → (409 if latest version is ACTIVE) → CapabilityGenerator.propose()
              → RunnerCapabilityGenerator.run(): OnlineRunnerSelector.pick(userId) (online uplink; most recent job use,
                then lastSeenAt) → none → CapabilityGeneratorException.noRunner() → 503 {"error":"Connect a runner to generate this capability"}
              → CapabilityPrompts.proposePrompt() → RunnerJobService.startJob(kind "kpi", no project, maxTurns 3)
              → pollJob() every kpi.capability.poll-ms (2s) until state done/failed, or kpi.capability.job-timeout-ms (300s) → cancelJob() + 502
              → (before the LLM) RunnerCollectorPipeline.runnerCredentials(userId): OnlineRunnerSelector.pick → RunnerCollectorClient.credentials()
                = GET /v1/credentials → [CredentialRef{name, host}] (never values; 404 older runner / 503 store off → empty list)
                → injected as {{CREDENTIALS}} into CapabilityPrompts.proposePrompt()
              → CapabilityPrompts.parseProposal(result) → {proposal, sources, credentials?, measures?, dataSource?} (a legacy "platform" key is ignored)
                (measures / dataSource = optional one-sentence summaries for the UI; stored on the version, copied on repair)
              → checkSources(sources, credentials, onRunner): sources empty → 422 "This can't be captured yet: ...";
                anything outside {activitywatch, file, http} → 422; http with no credential → 422;
                http credential not on the runner → 422 "This needs an API credential on your runner. Add one named "github"
                for api.github.com in Relay → your runner → KPI credentials ..."; same name, other host → 422; NOTHING stored
                To change the wording: CapabilityAgentService.checkSources()
              → older PROPOSED/TEST_FAILED rows → REJECTED; new KPIProxyCapability v(latest+1) platform=RUNNER status=PROPOSED,
                declaredCapabilities {sources: [...], credentials?: ["name@host"]}; an http plan pins runnerDeviceId to the
                runner whose list it was checked against (credentials are per runner)
              → also parsed/stored: assumptions (0-5 short items, assumptions + open questions ending "?"; missing/garbled → empty list, never an error);
                planRevision = 1, changeSummary / refineFeedback null
              → UI shows plain-language facts (Measures / Data comes from / Updates / Needs from you), the full plan collapsed,
                and what the collector may read
3-4 APPROVE   POST .../capability/{id}/approve
              → status must be PROPOSED; checkGrantable() re-run on the stored spec (platform must be RUNNER)
              → attempt(): PENDING_TEST → RunnerCollectorPipeline.attempt() (see "RUNNER platform")
              → pass: sample {value, unit, evidence, date} stored on the version → AWAITING_CONFIRM (S1; the KPI is NOT wired yet).
                Repair versions (`repair=true`) skip the confirm and go ACTIVE (SHADOW again if the replaced version was still on trial)
                via CapabilityLifecycleService.testGatePassed() → wire(): older live versions → DISABLED,
                KPI.proxyType=RUNNER_COLLECTOR, proxyStatus=ACTIVE, consecutiveProxyFailures=0
              → fail: TEST_FAILED + failureReason (visible in UI, GET .../capability)
RETRY         POST .../capability/{id}/regenerate  (only TEST_FAILED, attempts < MAX_ATTEMPTS=3, else 409)
              → same attempt(), previous bundle + failureReason fed into the prompt
              runner offline/busy/failed/timeout/bad-JSON → TEST_FAILED "Code generation failed: ..." but do NOT count as an attempt
              (no runner → failureReason "Code generation failed: Connect a runner to generate this capability"; regenerate once connected)
REFINE        job action "refine" {id, feedback} (blocking twin POST .../capability/{id}/refine {feedback}); jobs endpoint → 202 {jobId}
              → CapabilityAgentService.refineAs(): capability must be PROPOSED or TEST_FAILED else 409 "conflict"; feedback blank / >2000 → 400 "invalid"
                (job start validates feedback up front → 400, no job); latest ACTIVE/SHADOW → 409; step "Revising the plan on your runner"
              → CapabilityGenerator.refine() → RunnerCapabilityGenerator → CapabilityPrompts.refinePrompt() (previous plan in full + original description
                + feedback, both inside <<<...>>> delimiters with those chars stripped, + failureReason when TEST_FAILED) → same parseProposal() (+ changeSummary)
                → same checkSources() gate and runner-credential pinning as propose; usage recorded as ai_usage kind kpi_propose
              → saveProposed(): older PROPOSED/TEST_FAILED/... → REJECTED; new version v(latest+1) PROPOSED, description copied from the old one,
                planRevision = old+1, changeSummary (fallback "Plan updated from your feedback."), refineFeedback = the user's text, assumptions
              → CapabilityDTO gains planRevision (int, 1 for legacy), assumptions (List), changeSummary, refineFeedback (nullable)
              UI (kpi-list.js openAutomateModal()): Refine box under the plan, assumptions list, "Plan vN · what changed" line, then Approve as before
              To change limits/wording: CapabilityAgentService.refineAs(); the prompt: resources/prompts/kpi-capability-refine.md
REJECT        POST .../capability/{id}/reject (PROPOSED / TEST_FAILED / AWAITING_CONFIRM / AWAITING_SENSOR / AWAITING_CONTRACT → REJECTED; UI footer "Discard setup" → inline confirm → describe form prefilled = start over)
              Dialog close (✕ / Esc / backdrop) never changes state; a running job has no cancel endpoint, so Discard is disabled until it finishes [NOT IMPLEMENTED: job cancel, "Resume setup" hint on the KPI card]
REPAIR (M12)  proxy breaker trips → ProxyHealthService.recordFailure()/flagAnomaly() → CapabilityRepairTrigger.attemptRepair(kpi)
              → AgentCapabilityRepairTrigger (single background thread; non-RUNNER_COLLECTOR KPIs only logged)
              → CapabilityGenerator.runnerAvailable(owner)? no → parked in in-memory `waiting` (kpiId→userId), nothing counted
                → retryWaiting() every kpi.repair.runner-check-ms (60s): owner has a runner online → re-read KPI
                → still RUNNER_COLLECTOR + NEEDS_REPAIR → dispatch; else dropped
              → CapabilityAgentService.repairFor(userId, kpi, autoRetry=true)
              → runner drops mid-repair → result TEST_FAILED with the no-runner reason (waitingForRunner()) → auto-retry loop
                stops, KPI parked again; next dispatch continues on that TEST_FAILED version (attempts unchanged)
              → old version → NEEDS_REPAIR; NEW version (copy of spec + bundle + collector id, failureReason seeded from old lastResult)
              → attempt() up to MAX_ATTEMPTS → pass clears KPI NEEDS_REPAIR (as above); fail leaves it for the manual button
              Manual "Fix it": POST .../capability/repair → repairFor(.., autoRetry=false), one attempt per click
              (409 unless KPI.proxyStatus=NEEDS_REPAIR, or latest is a TEST_FAILED repair version, which is then regenerated)
```

Auth/scoping: KPI resolved by `(name, currentUser)`, capability only via `KPIProxyCapabilityRepository.findByIdAndUserIdAndKpiId()` — never `findById`. Foreign id or KPI → 400 "not found" (no existence leak). Tested in `CapabilityAgentIntegrationTest.anotherUser_cannotSeeOrDriveMyCapability` (cf. commit `b8a1057`).

Enforcement: the **gate** is `CapabilityAgentService.checkSources()` (propose, approve, retry): sources ⊆ {activitywatch, file, http}, at least one; http needs a named credential (name + host) the runner holds. The collector script never gets network or secrets: for http the RUNNER makes the GET with the credential and hands the response to the script as `/in/http/<i>.json`. The **boundary** is the runner's own OS sandbox (no network, read-only declared inputs) + the contract approval pinned to the runner-computed hash, not the hub's checks.

UI Automate modal (kpi-list.js): while a job runs → StepList rows (● running + seconds, ✓ done) + "Used N tokens" when usage came back; result → `renderAutomateSummary()` facts + `renderAutomateAccess()` (what it may read) + `renderAutomateCode()`; TEST_FAILED → `describeFailure()` maps failureReason prefixes ("Code generation failed:", "Runner sandbox unavailable:", "Static check failed:", "Fixture test failed:" …) to one sentence + what to do. All text via textContent / Dom.esc.

### Confirm gate + shadow (S1; kpi-sensors.md "Test pipeline" 5-6)
Files: `CapabilityLifecycleService.java` (platform-agnostic state machine: no runner types), `CapabilityOutput.java`, `ReadingPlausibility.java`, `ProxyHealthService.plausibilityReason()`, `KPIProxyFillService`, UI `kpi-list.js renderAutomateConfirm()`.
```
test gate passes (first build)  → CapabilityLifecycleService.testGatePassed() → AWAITING_CONFIRM (sample on the version; KPI not wired)
  UI (live-steps panel stays above): "Value for <date>: 47 min" + evidence rows + [Looks right] [Wrong, because…] [Start over]
Looks right  POST .../capability/{id}/confirm (instant) → confirmRight() → SHADOW, shadowUntil = today + kpi.shadow.days (7), KPI wired
              (0 days → straight ACTIVE)
Wrong        job action "wrong" {id, description=reason} (blocking twin: POST .../{id}/wrong {reason})
              → CapabilityAgentService.wrongAs(): recordWrong() (confirmRounds++) → 3rd "wrong" → TEST_FAILED + "You said the number was wrong
                3 times, so I stopped. Start over ..." ; else failureReason = CapabilityLifecycleService.wrongFeedback() (value, evidence, reason)
                → attempt() (runner build with the previous bundle + that text) → AWAITING_CONFIRM again.
                Not limited by MAX_ATTEMPTS (test-gate failures inside a round still count; Regenerate is then hidden at 3).
SHADOW       RunnerCollectorProvider pull: readings stored pending=true, flag="SHADOW" (every reading, not sampled: confirm-inbox shows all 7)
              → CapabilityLifecycleService.promoteDueShadows() @Scheduled kpi.shadow.promote-cron (0 30 0 * * ?) → ACTIVE when shadowUntil <= today
ACTIVE or SHADOW  plausibility guard on every pulled reading: non-finite; negative while the unit is in ReadingPlausibility.NON_NEGATIVE_UNITS;
              or > 5× median of the last 14 trusted readings (needs ≥5, median > 0; earlier IMPLAUSIBLE-pending points are ignored)
              → stored pending, flag="IMPLAUSIBLE", flagReason (plain language) → confirm-inbox; breaker NOT touched.
```
`KPIData.flag`/`flagReason` are cleared by confirm (`KPIService.confirmKPIData`) and edit. Everything is additive: pre-S1 documents have none of the new fields; existing ACTIVE capabilities are never touched by the promotion job (it only reads status SHADOW).
Reuse for another platform: produce a `CapabilityOutput` from its test run and call `CapabilityLifecycleService.testGatePassed(cap, kpi, out, date, skipConfirm)`; `wire()` sets `KPI.proxyType = RUNNER_COLLECTOR` (a new platform needs its own ProxyType here) and publishes `CapabilityWentLive` (RUNNER: the provider POSTs /activate). [NOT IMPLEMENTED] showing `flagReason` in the /today confirm chips (the DTO already carries `flag`, `flagReason`).

### RUNNER platform (S3; kpi-sensors.md "Iteration 1")
Files: `RunnerCollectorPipeline.java`, `RunnerCollectorProvider.java`, `CollectorBundle.java`, `CollectorStaticCheck.java`, `ProxyReadingRecorder.java`, `CapabilityWentLive.java`, prompt `resources/prompts/kpi-collector-generate.md`; transport `relay/RunnerCollectorClient` (see `relay/FLOWS.md`).
```
PROPOSE   same job; the prompt describes ONLY the runner (local device data: ActivityWatch / app files; no network, stdlib only,
          declared sources only) → Proposal.sources → checkSources() → declaredCapabilities {sources: [...]}, platform RUNNER
APPROVE   attempt() → RunnerCollectorPipeline.attempt():
          runnerDeviceId = OnlineRunnerSelector.pick() (pinned on the version), collectorId = "kpi-<kpiId>-v<version>" (legacy migrated rows keep "kpi-<kpiId>")
          → GET /v1/sensors: sandbox.available=false → TEST_FAILED "Runner sandbox unavailable: ..." (no generation, not an attempt; never runs unsandboxed)
                             activitywatch declared but not installed+running → AWAITING_SENSOR "ActivityWatch isn't running on <runner>"
          → loop (≤ MAX_ATTEMPTS, each round = 1 attempt, failure text fed to the next generation):
              generateCollector() (runner "kpi" job → {manifest, files}) → CollectorStaticCheck.check()
              → POST /v1/collectors (400 → fed back) → contractHash stored → POST /test → per-case "✓/✕ Sample …" step rows
          → runner approved + hash == approvedContractHash (repair / "wrong" round) → dry run directly
          → else AWAITING_CONTRACT (UI: CollectorStaticCheck.contractText(), e.g. "Reads ActivityWatch browser activity · no internet · runs daily at 00:10")
CONTRACT  job action "contract" (blocking twin POST .../{id}/contract) → POST /approve {contractHash} (409 → TEST_FAILED, regenerate)
          → approvedContractHash = contractHash → dryRun(): POST /run {yesterday 00:00 → today 00:00, hub zone}
             503 → AWAITING_SENSOR (not an attempt) · 422 → TEST_FAILED (collector crashed) · 403 → TEST_FAILED
          → CapabilityLifecycleService.testGatePassed() → AWAITING_CONFIRM (value + evidence) → SHADOW → ACTIVE (unchanged S1 flow)
SENSOR    job action "sensor" (twin POST .../{id}/sensor), UI "Install it" / "Check again": already running → continue;
          else POST /v1/sensors/install (501 → "install it from activitywatch.net") → poll GET /v1/sensors every
          kpi.runner.sensor-poll-ms until operation="" (≤ kpi.runner.sensor-install-timeout-ms) → contract approved ? dry run : attempt()
LIVE      wire() → CapabilityWentLive → RunnerCollectorProvider.onLive() → POST /activate (at SHADOW already: the runner
          only schedules active collectors, so SHADOW readings need it) → collectorActivated=true (pull retries if it failed)
PULL      RunnerCollectorProvider.pullDue() @Scheduled kpi.runner.pull-cron (0 40 * * * ?, hourly) → KPI proxyType RUNNER_COLLECTOR,
          breaker closed, latest version SHADOW/ACTIVE RUNNER, lastPulledOn != today → pull(kpi)
          on demand: POST .../capability/pull (UI "Fetch values now") → {outcome, stored}
          pull(): activate if needed → GET /v1/collectors: state "failed" → ProxyHealthService.recordFailure() (→ M12 repair)
          → GET .../readings?since=readingsSince → one value per local day of `from` (same day twice → newest recordedAt)
          → ProxyReadingRecorder.record() (SHADOW / IMPLAUSIBLE flags, anomaly breaker)
          → readingsSince = max recordedAt, lastPulledOn = today
          runner offline / RunnerCallException → skipped, nothing counted (no breaker trip), next hour again
REPAIR    breaker trip → AgentCapabilityRepairTrigger (now also RUNNER_COLLECTOR) → repairFor(): new version copies
          runnerDeviceId, collectorId, bundle, contractHash, approvedContractHash → same pipeline, same collector id
          → unchanged contract: runner keeps the approval → dry run → live (skipConfirm) → activate
          → changed contract: AWAITING_CONTRACT (UI: "The fix needs to read different data …"), never auto-approved
REJECT    "Discard setup" on a first build → DELETE /v1/collectors/{id} (best effort; not for repair versions)
```
KPIProxyFillService skips `RUNNER_COLLECTOR` KPIs (they are pulled, not fetched per night).
Mongo (additive, all nullable on `kpi_proxy_capabilities`): `runnerDeviceId`, `collectorId`, `collectorBundle` (JSON string: file paths contain dots), `contractHash`, `approvedContractHash`, `collectorTests`, `readingsSince`, `lastPulledOn`, `collectorActivated`. New enum values: `CapabilityPlatform.RUNNER`, `CapabilityStatus.AWAITING_SENSOR/AWAITING_CONTRACT/RETIRED`, `ProxyType.RUNNER_COLLECTOR`.
[NOT IMPLEMENTED] hourly collectors on the hub (static check requires `daily HH:MM` + `1d`; the pull writes one value per day); runner time zone (the dry run's "yesterday" uses the hub's zone); choosing which runner when a user has several online (first pick is pinned); a presence hook (worst case ~1h after reconnect); "file" sources beyond the static check (the runner's deny-list is authoritative); multi-proxy fusion.

Errors: 400 bad input/not found, 409 conflict (wrong status, attempt cap, already ACTIVE, generator busy), 422 proposal a runner collector cannot do, 502 generator failure.

To change the attempt cap: `CapabilityAgentService.MAX_ATTEMPTS`. To change what is grantable: `CapabilityAgentService.checkSources()`. To change the build / dry run: `RunnerCollectorPipeline`. To change prompts: `resources/prompts/kpi-capability-*.md` (`{{PLACEHOLDER}}`s, filled once by `CapabilityPrompts.fill()`); JSON contract: `CapabilityPrompts.parseProposal()` / `parseCollectorBundle()`. To change runner choice: `OnlineRunnerSelector.pick()`. To change job timing: `kpi.capability.job-timeout-ms`, `kpi.capability.poll-ms`; repair wake-up: `kpi.repair.runner-check-ms`. To change repair behaviour: `AgentCapabilityRepairTrigger` / `CapabilityAgentService.repairFor()`.

Intake: one description → one proposal, then a refine loop (feedback → revised plan, above); a full multi-turn chat is [NOT IMPLEMENTED] (each refine is stateless: previous plan + one feedback text, no history beyond the last revision). Disabling/removing an ACTIVE capability (propose is 409 while one is ACTIVE). Stage 6-7 scrutiny/confirm-rate tapering for new capabilities. More than one page per http source (no pagination), POST APIs, OAuth flows (the user pastes a token).

## Targets (goalType TARGET)

Files: `GoalType.java`, `KPITarget.java`, `KPITargetStatus.java`, `KPI.java` (`goalType`, `target`, `resolveGoalType()`), `KPIService.java`, `KPIController.java`, `KPIDTO.java`; UI `static/kpi-create.html`, `static/kpi-list.html`, `static/kpi-dashboard.html` + `static/js/kpi-dashboard.js`.

A KPI is judged one of three ways (`GoalType`): `HIGHER` / `LOWER` (open-ended, = legacy `higherIsBetter`) or `TARGET` (reach `target.value` by `target.deadline`).

```
CREATE   POST /api/kpis/create {goalType?, targetValue?, targetDeadline? (yyyy-MM-dd), higherIsBetter?, ...}
         → KPIController.createKPI(): goalType absent + targetValue sent → TARGET; absent + none → null; bad date → 400
         → KPIService.createKPI(.., goalType, targetValue, targetDeadline)
             TARGET → validateTarget() (both required, deadline not in the past, else 400)
                    → KPITarget{value, deadline, baseline = null, setAt = today}; higherIsBetter = null (inferred later)
             HIGHER/LOWER → higherIsBetter = (goalType == HIGHER);  null goalType → GoalType.fromHigherIsBetter()
BASELINE first reading of any kind (manual, auto-fill, proxy) → KPIService.saveKPIDataPoint()
         → TARGET && target.baseline == null → baseline = value, higherIsBetter = KPITarget.inferredHigherIsBetter(), save KPI
RESET    PUT /api/kpis/{name}/target {value, deadline} → KPIController.updateTarget() → KPIService.updateTarget()
         → validateTarget() → new KPITarget{baseline = latestValue(kpi) (null if no readings), setAt = today}
         → goalType = TARGET (also switches a HIGHER/LOWER KPI), higherIsBetter re-inferred → KPIDTO
READ     GET /api/kpis, /api/kpis/dashboard → KPIService.convertToDTO()
         → goalType = kpi.resolveGoalType(); TARGET → target + KPITargetStatus.of(target, latestValue(kpi), today)
STATUS   KPITargetStatus.of() (pure, no I/O):
         direction = target > baseline ? "up" : target < baseline ? "down" : null
         no reading / no baseline → NO_DATA
         reached (up: cur ≥ target, down: cur ≤ target; overshoot counts; baseline == target) → REACHED, gapPct 0, progress 1
         gapPct = |cur − target| / |baseline − target|, progress = clamp01(1 − gapPct)
         expected = clamp01(days(setAt → today) / days(setAt → deadline))   (total ≤ 0 → 1.0)
         today > deadline → MISSED
         progress ≥ expected + PACE_BAND → AHEAD · progress < expected − PACE_BAND → BEHIND · else ON_TRACK   (PACE_BAND 0.10)
         daysLeft = days(today → deadline) (negative after the deadline)
UI       kpi-create.html: 3-way choice Higher / Lower / Target (target value + deadline inputs) → POST create
         kpi-list.html: card state badge from targetStatus.state + "set next target" form → PUT .../target
         kpi-dashboard.js: renderChart() adds targetDataset() = dashed horizontal line at target.value (accent colour);
           trend colours / arrow use targetStatus.direction ("up" = higher better) via resolveHigherIsBetter(), else higherIsBetter
```
To change the pace tolerance: `KPITargetStatus.PACE_BAND`. To change state precedence or the math: `KPITargetStatus.of()`. To change validation: `KPIService.validateTarget()`. To change the dashboard line: `kpi-dashboard.js targetDataset()` / `TARGET_LINE_COLOR`.

[NOT IMPLEMENTED] target history (a new target replaces the old one; past targets and their outcomes are not kept); an explicit direction for TARGET (always inferred from baseline vs target).

## Technology Notes

- **Generated code is untrusted (M8).** It is LLM output steered by a free-text user description; it is only ever executed by the user's own runner in its OS sandbox (never in `javaapp`, never on the hub host), with no network and only its declared, approved inputs. The UI renders it and all LLM text with `textContent` (no HTML injection). Approval gates *what is read*; the runner sandbox, not the review, is the actual safety boundary. A malicious collector can only hurt its own user's machine within that sandbox.
- **Generation = the KPI owner's own Claude Code via their paired Relay runner** (job kind `kpi`: no project, no tools, text only). The server spends no Claude credits. No runner online → propose is 503 "Connect a runner to generate this capability"; approve/regenerate/repair end TEST_FAILED with that reason (no attempt consumed). If the runner's Claude login expired, the job ends `failed` and its error is shown. Live collectors run on the runner's own schedule; readings reach the hub whenever it is online (no runner = no data, manual KPIs only).
- **Runner choice:** `OnlineRunnerSelector` = devices with a live uplink in *this* JVM's `UplinkRegistry`; preference "most recently used for a job" is in-memory (lost on restart → falls back to `lastSeenAt`). A user with two online runners may get consecutive jobs on the same one. Busy runner (409/429 on start/poll) → 409 "Your runner is busy".
- **Async jobs are in-memory, single-node** (`CapabilityTaskService.tasks`): a javaapp restart loses every job and its event log (the poll then 404s and the UI reloads the stored capability status); the capability version itself is in Mongo, but a restart mid-attempt leaves it `PENDING_TEST`. Max 4 jobs run at once across all users (more queue in the executor); one running job per (user, KPI). Finished jobs are kept 15 min, evicted lazily on the next start. Progress reaches the job via a `ThreadLocal` (`CapabilityProgress`) bound only on the worker thread, so nothing changes for blocking callers. The service worker (`static/sw.js`, `API_NO_CACHE`) never caches `/api/kpis/*/capability*` GETs, otherwise stale-while-revalidate would replay old poll snapshots.
- **Token usage** is whatever the runner job reports in `usage` (`input_tokens`/`inputTokens` + `cache_creation_input_tokens` + `output_tokens`/`outputTokens`, or `total_tokens`); absent → no "Used N tokens" line. Cache reads are not counted.
- **Error text:** runner HTTP error bodies (JSON) are logged, never shown (`RunnerCapabilityGenerator.fromCall()`); unparseable model replies say "The AI's reply could not be used (...). Try again" (`CapabilityPrompts.bad()`).
- **Blocking poll.** propose/approve/regenerate/repair hold the request thread while polling the runner job (every 2s, up to 300s; each poll is itself a blocking uplink call bounded by `relay.proxy.timeout-ms`) + runner test/dry-run time (up to `kpi.runner.long-call-timeout-ms`). On timeout the job is cancelled. A runner that disconnects mid-job → treated as no runner (job state lives only on the runner, see `relay/FLOWS.md`). Hub restart mid-generation loses the waiting request; the runner job finishes but its result is discarded. a reverse proxy (Caddy/Cloudflare ~100s edge timeout) in front may cut the browser connection while the server keeps working: reopening the dialog shows the resulting state (`GET .../capability`). `PENDING_TEST` is transient in-flight state; a javaapp crash mid-attempt leaves a stuck `PENDING_TEST` row (no sweeper; propose again supersedes it).
- **LLM output is not trusted to be JSON.** `CapabilityPrompts.parseProposal()/parseCollectorBundle()` extract the first `{`..last `}` object and validate keys/types (bundle content: `CollectorStaticCheck`, 64KB `collector.py` cap); anything else → 502 / "Code generation failed" (not counted as an attempt).
- **Test gate is limited by design.** Fixture cases the agent wrote itself + one dry run on yesterday's real data: it proves "runs and agrees with its own fixtures", not "prints the right number". Backstops (S1): the user's AWAITING_CONFIRM check against evidence, then 7 days of SHADOW readings in the confirm-inbox, then the plausibility guard forever. The check is only as good as the evidence the script prints (an old plain-number script gives the user nothing to verify) and the guard can't catch a consistently wrong number.
- **Shadow/promotion state is time-based and lives in Mongo** (`shadowUntil`); the promotion job runs in the javaapp JVM at 00:30 (a missed night is caught up the next, it compares dates). `kpi.shadow.days` changes apply to new confirmations only. Plausibility history is the last 14 stored points; with <5 only impossible values are flagged. The unit-can't-be-negative list is a hardcoded set (`ReadingPlausibility.NON_NEGATIVE_UNITS`); unknown units may be negative.
- **Auto-repair runs on one background daemon thread** (`AgentCapabilityRepairTrigger`): lost on restart (KPI then just stays `NEEDS_REPAIR` for the manual button), and one repair at a time across all users (a slow runner delays everyone's repairs).
- **Legacy migration is flag-gated and one-shot** (`_migration` `runner-only-kpis-v1`): a SERVER doc restored from an older backup after the flag exists is not rewritten and will fail to map on read; delete the flag doc and restart to re-run. `retiredPlatform` is not a mapped field (raw Mongo only).
- **Repairs waiting for a runner are in-memory only** (`AgentCapabilityRepairTrigger.waiting`): a javaapp restart forgets them; the KPI stays `NEEDS_REPAIR` (breaker open, nothing runs) until the next breaker trip re-fires the trigger or the user clicks "Fix it". Wake-up is a 60s poll, not a connect hook, so relay stays free of KPI dependencies; worst case a repair starts ~60s after the runner connects. Waiting never trips or resets the breaker and never consumes an attempt. A new repair version is non-ACTIVE until its test gate passes, so `RunnerCollectorProvider` (which pulls only for a SHADOW/ACTIVE latest version) pulls nothing for that KPI in the meantime; it was already skipped by the open breaker.

- **Web-API KPIs (H3):** the hub sees credential names + hosts only (GET /v1/credentials). It checks host == credential host twice (`checkSources` at propose, `CollectorStaticCheck.httpProblem` on every bundle), but the runner's install-time URL check + fetch are authoritative. The list is fetched from the runner `OnlineRunnerSelector` picks at propose time: with two runners the other one's credentials are invisible, and the build is pinned to that runner. A credential removed after approval → the dry run / nightly run gets 503 → AWAITING_SENSOR "Add or fix the credential in Relay → <runner> → KPI credentials". A token's scope is the real limit: a collector can GET anything the token reads on its host.
- **"Connect a runner" in the Automate dialog** is a snapshot at open (`GET .../capability/runner` → `RunnerCapabilityGenerator.runnerAvailable`, in-memory uplink registry of this JVM); "Check again" re-asks. A job that still fails with `NO_RUNNER_MESSAGE` shows the same link.
- **RUNNER collectors (S3):** the runner sandbox (bwrap / AppContainer) is the boundary, not the hub's static check (regex over import lines, deny-list mirror). Approval binds to the runner-computed contract hash; the hub trusts the runner's `approved` flag + its own `approvedContractHash` to skip re-approval. Readings live on the runner until pulled (data-dir wipe = lost unpulled readings and lost approvals → collector 403 until re-approved; the pull then logs `PULL_ERROR`). The pull cursor is `recordedAt`, so a re-run of an old window re-writes that day (overwrites a value the user edited). `/test` and `/run` wait up to `kpi.runner.long-call-timeout-ms` (180s; other runner calls `relay.proxy.timeout-ms`, 30s): a runner slower than that is reported as offline (not an attempt) while it may still finish the run. Sensor install polling holds a capability worker thread up to 10 min.

- **Targets:** direction is inferred, not stored by the user: with no baseline yet (KPI created before its first reading) `direction` is null, the state is NO_DATA and the dashboard falls back to `higherIsBetter` (null → treated as lower-is-better server-side, `Boolean.TRUE.equals`). A target equal to the baseline counts as REACHED immediately. Pace is linear in calendar days from `setAt` (server `LocalDate.now()`, hub time zone). Resetting a target overwrites the old one: no history. Legacy KPIs have no `goalType` in Mongo; `KPI.resolveGoalType()` derives it from `higherIsBetter` on every read (no migration — always read via `resolveGoalType()`, never `getGoalType()`). Cost: `convertToDTO()` runs one extra latest-reading query (`findTopNOrderByDateDesc(1, ..)`) per TARGET KPI per list/dashboard request (N TARGET KPIs = N extra queries). The dashboard line is a constant dataset, not the Chart.js annotation plugin (not loaded); it spans only the selected period's x-range and stretches the y-axis to include the target.

## Change Index

| What to change | Where | Note |
|---|---|---|
| Credentials offered to the agent (names + hosts) | `RunnerCollectorPipeline.runnerCredentials()` → `RunnerCollectorClient.credentials()` | GET /v1/credentials on the picked runner |
| http source rules (host match, placeholders, GET only) | `CollectorStaticCheck.httpProblem()` | mirror of Relay API.md "http source"; runner is authoritative |
| Credential ref format / name+host regex | `CredentialRef` (`NAME`, `HOST`, stored `name@host`) | same name rule as the runner |
| Missing-credential message | `CapabilityAgentService.checkSources()` | names the credential + host + "Relay → your runner → KPI credentials" |
| Runner-online check for the dialog | `CapabilityAgentController.runner()` (`GET /api/kpis/{name}/capability/runner`), `kpi-list.js automateConnectRunner()` | env.js `KPI_CAP_RUNNER` |
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
| Capability storage | `KPIProxyCapability` / `KPIProxyCapabilityRepository` | collection `kpi_proxy_capabilities` |
| Collector output -> value parsing | `CapabilityOutput.parse()` / `RunnerCollectorPipeline.dryRun()` | last non-blank line: number or JSON `{value, unit?, evidence?}`, evidence cap `MAX_EVIDENCE` 20 |
| Confirm gate (right / wrong) | `CapabilityAgentService.confirm()` / `wrong()`, `CapabilityLifecycleService` | `POST .../{id}/confirm`, job action `wrong` |
| Max "wrong" rounds | `CapabilityLifecycleService.MAX_CONFIRM_ROUNDS` | 3 |
| Shadow length / promotion | `kpi.shadow.days` (`KPI_SHADOW_DAYS`), `kpi.shadow.promote-cron`, `CapabilityLifecycleService.promoteDueShadows()` | |
| Plausibility rules | `ReadingPlausibility.check()` (5× median, ≥5 readings, window 14, non-negative units), `ProxyHealthService.plausibilityReason()` | |
| Flagged readings | `KPIService.addFlaggedProxyDataForUser()`, `KPIData.flag/flagReason` | `SHADOW` / `IMPLAUSIBLE`, forced `pending` |
| Confirm UI | `kpi-list.js renderAutomateConfirm()` / `automateConfirm()`, `.automate__sample/__evidence*` in `kpi.css`, `ENV.ENDPOINTS.KPI_CAP_CONFIRM` | |
| Automate UI | `static/kpi-list.html` `#automateModal`, `static/js/kpi-list.js` `openAutomateModal()` / `renderAutomate()` | chrome (head/foot, stage rail, breakpoints 900/640) in `styles/organisms/automate-dialog.css`; content styles in `styles/pages/kpi.css` `.automate__*` |
| Automate plain-language facts / failure sentences | `kpi-list.js` `renderAutomateSummary()`, `describeFailure()` | "Updates" text is fixed (runner schedule) |
| Automate job polling | `kpi-list.js` `automateStart()` / `automatePoll()`, `ENV.TIMING.KPI_CAP_POLL_MS`, `ENV.ENDPOINTS.KPI_CAP_*` | |
| Step rows (shared with planner) | `static/js/molecules/step-list.js`, `styles/molecules/step-list.css` | |
| Async capability jobs | `CapabilityTaskService`, `CapabilityAgentController.startJob()/pollJob()/activeJob()` | `WORKERS`, `KEEP_FINISHED_MS` |
| Progress events | `CapabilityProgress`, `RunnerCapabilityGenerator.forwardEvents()` | no-op without a bound sink |
| Agent endpoints | `CapabilityAgentController` | `/api/kpis/{name}/capability/...` |
| Pipeline (propose/approve/test gate/retry) | `CapabilityAgentService` | `propose()`, `approve()`, `attempt()`, `regenerate()` |
| Attempt cap | `CapabilityAgentService.MAX_ATTEMPTS` | 3 |
| What may be granted | `CapabilityAgentService.checkSources()` / `checkGrantable()` | sources ⊆ activitywatch/file/http (+ credentials), platform RUNNER only |
| Legacy SERVER/WINDOWS cleanup | `RunnerOnlyKpiMigration.migrate()` | `_migration` flag `runner-only-kpis-v1` |
| LLM transport (Java) | `RunnerCapabilityGenerator` | `kpi.capability.job-timeout-ms` (300000), `kpi.capability.poll-ms` (2000); swap via `CapabilityGenerator` |
| Which runner runs the job | `OnlineRunnerSelector.pick()` | online uplink, last job use, then `lastSeenAt` |
| LLM prompt text | `resources/prompts/kpi-capability-*.md` | `{{KPI_NAME}}`, `{{DESCRIPTION}}`, `{{PROPOSAL}}`, `{{SOURCES}}`, `{{PREVIOUS_FAILURE}}` |
| LLM JSON contract | `CapabilityPrompts` | `parseProposal()`, `parseCollectorBundle()`; size cap `CollectorStaticCheck.MAX_SOURCE_BYTES` |
| "Connect a runner" message | `CapabilityGeneratorException.NO_RUNNER_MESSAGE` | 503 in `CapabilityAgentController.generator()` |
| Repair waiting for runner | `AgentCapabilityRepairTrigger.retryWaiting()` | `kpi.repair.runner-check-ms` (60000) |
| Auto-repair trigger | `AgentCapabilityRepairTrigger` | replaces former `LoggingCapabilityRepairTrigger` |
| Manual "Fix it" | `CapabilityAgentService.repair()` | `POST .../capability/repair` |
| RUNNER pipeline (sensors → generate → static → install → fixtures → contract → dry run) | `RunnerCollectorPipeline.attempt()` / `approveContract()` / `dryRun()` / `installSensor()` | S3 |
| Static check rules (imports allowlist, deny-list globs, fixtures ≥2 incl. 0) | `CollectorStaticCheck.check()`, `ALLOWED_MODULES`, `DENIED_DIRS/FILES` | keep deny-list in sync with Relay `sources.go` |
| Contract sentence | `CollectorStaticCheck.contractText()` | shown at AWAITING_CONTRACT |
| Collector generation prompt | `resources/prompts/kpi-collector-generate.md`, `CapabilityPrompts.collectorPrompt()` / `parseCollectorBundle()` | `{{SOURCES}}`, `{{CREDENTIALS}}` (approved name + host) |
| Refine loop (feedback → new PROPOSED revision) | `CapabilityAgentService.refineAs()`, `CapabilityTaskService.Action.REFINE`, `CapabilityAgentController.refine()` | feedback cap `MAX_DESCRIPTION_CHARS` (2000) |
| Refine prompt / revision fields | `resources/prompts/kpi-capability-refine.md`, `CapabilityPrompts.refinePrompt()`, `KPIProxyCapability.planRevision/assumptions/changeSummary/refineFeedback` | `CapabilityPrompts.MAX_ASSUMPTIONS` (5) |
| Propose prompt (runner only, no platform choice) | `resources/prompts/kpi-capability-propose.md` | `sources` field |
| Collector id on the runner | `RunnerCollectorPipeline.deployment()` (`"kpi-" + kpiId + "-v" + version`) | per version; legacy rows keep old id |
| Readings pull schedule / on-demand | `kpi.runner.pull-cron` (hourly :40), `RunnerCollectorProvider.pullDue()/pull()`, `POST .../capability/pull` | offline = skip |
| Shared reading guards (SHADOW / IMPLAUSIBLE / anomaly) | `ProxyReadingRecorder.record()` | used by the runner pull |
| Activate on go-live | `CapabilityWentLive` event, `RunnerCollectorProvider.onLive()` | |
| Sensor install polling | `kpi.runner.sensor-poll-ms` (3000), `kpi.runner.sensor-install-timeout-ms` (600000) | |
| RUNNER UI (contract / sensor / tests / fetch) | `kpi-list.js renderAutomateContract()`, `renderAutomateSensor()`, `renderAutomateTests()`, `renderAutomatePull()`, `ENV.ENDPOINTS.KPI_CAP_PULL` | job actions `contract`, `sensor` |
| Goal type (TARGET / HIGHER / LOWER), legacy derivation | `GoalType.fromHigherIsBetter()`, `KPI.resolveGoalType()` | no migration; old docs derive on read |
| Target create / validation | `KPIController.createKPI()` (`goalType`, `targetValue`, `targetDeadline`), `KPIService.createKPI()` / `validateTarget()` | deadline not in the past |
| Target reset | `KPIService.updateTarget()`, `PUT /api/kpis/{name}/target` | baseline = latest reading, setAt = today; replaces old target |
| Target baseline from first reading | `KPIService.saveKPIDataPoint()` | only while `target.baseline == null` |
| Target progress / state math | `KPITargetStatus.of()`, `PACE_BAND` (0.10) | REACHED → MISSED → AHEAD/ON_TRACK/BEHIND |
| Target fields in API | `KPIDTO.goalType/target/targetStatus`, `KPIService.convertToDTO()` / `latestValue()` | one extra query per TARGET KPI |
| Dashboard target line / trend direction | `static/js/kpi-dashboard.js` `targetDataset()`, `resolveHigherIsBetter()`, `TARGET_LINE_COLOR`; `kpi-dashboard.html` `kpiMeta` fill + `upIsBetter()` | dashed constant dataset |
| Target UI (create / list) | `static/kpi-create.html` goal choice, `static/kpi-list.html` state badge + next-target form | |

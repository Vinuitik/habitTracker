# KPI Tracking Agent — Design Doc

Status: draft, not yet built. Written after an extended design conversation (2026-08-31).
Supersedes: nothing (first design doc for this feature).

## Problem

Manual KPI logging doesn't happen. The habit tracker already supports KPIs (manual entry,
auto-fill-on-miss, and — as of this session — editable KPI↔Habit links), but the actual failure
mode isn't the UI, it's that logging a number every day is a chore nobody keeps up with.

The fix isn't a nicer form. It's not requiring the human to be the sensor: an agent that, per KPI,
figures out where the value can come from automatically (an already-installed app, a third-party
API, an OS-level signal), builds whatever's needed to read it, and only asks the human to confirm
occasionally instead of enter data constantly.

## Risk posture (explicit, agreed)

This is a personal prototype with one real user, backups exist, and the value of the "wow, it just
works" version outweighs building this to production-multi-tenant-safety standards on day one.
Safety work here is staged and incremental (timeouts → scoped secrets → sandboxed execution →
tighter isolation later if this ever stops being single-user), not a gate that has to be cleared
before anything ships. Every choice below says explicitly which bucket it's in.

## Two proxy families: pull vs. push

Some data the server can go get on its own. Some data only exists on the user's own device, so
something on that device has to send it in.

```
PULL (server reaches out)              PUSH (device reaches in)

 [nightly cron, existing               [capability running ON the
  updater.UpdateScheduler pattern]      user's phone/laptop]
        |                                       |
        | calls a third-party API              | reads something only that
        | directly (e.g. Trello)               | device can see (focused window,
        |                                       | screen-on event, Health Connect)
        v                                       v
 [KPIService.addKPIDataForUser()]  <----  [writes into that user's Drive
                                            mailbox, same as manual offline
                                            writes already do]
```

Pull proxies are the server's problem entirely. Push proxies need a way to get code onto the
device and a way to get data back — both solved below by extending infrastructure that already
exists rather than inventing a new channel.

## Reusing the existing Drive-mailbox sync (habitTracker.sync)

The app already has exactly the infrastructure a push proxy needs, built for a different reason:
`MailboxConsumeService` drains each user's own Google Drive mailbox folder every 15 minutes,
decrypts files with a per-user AES-256 key, and dispatches by `kind` — one of the existing kinds,
`"kpi-value"`, already calls `KPIService.addKPIDataForUser()`. See
`src/main/java/habitTracker/sync/FLOWS.md` for the full existing mechanism.

A capability running on a device becomes just another writer into that same mailbox. It needs two
things the browser currently gets for free via a session-authed call to `/api/sync/status`:

**Pairing (device gets the mailbox key)**
1. User clicks "Pair a device" while logged into the web app. Server generates a short one-time
   code (same in-memory-nonce pattern `DriveOAuthService` already uses for its OAuth state), shown
   on screen with a short TTL (~10 min).
2. The companion (run once per device) prompts for the code, calls a new unauthenticated
   `POST /api/sync/pair {code}` — unauthenticated because it's not a browser session, but the code
   is single-use, short-lived, and proves the caller was just looking at the logged-in account.
   Server returns `{mailboxFolderId, encryptionKey}` — the only time this leaves the server to a
   non-browser client.
3. The companion gets its own Drive access independently: the standard OAuth-for-installed-apps
   flow (same pattern `gcloud`/`rclone` use — opens a browser to Google's consent screen, listens
   on localhost for the redirect, exchanges for its own refresh token, stores it locally on that
   device only). The server's own refresh token is never touched or shared.
4. From then on the companion writes the same JSON shape the browser's `outbox.js` already
   produces (`{requestId: uuid(), kind, ts, payload}`, AES-256-GCM encrypted) into the mailbox
   folder, using its own locally-refreshed Drive token. Works even when the server is asleep.

**Capability deploy (server gets new/updated code onto the device)**
Reuses the same channel in reverse — a second Drive subfolder (e.g. `_capability_deploy`) that only
the paired device polls. The server writes the current capability's source + version, encrypted
the same way. The companion checks this folder on its own poll cycle, and if it sees a version
newer than its local cache, downloads and starts running it. No inbound connection to the device
in either direction — important on a home network, since opening a port to a home laptop or phone
is not something to build around.

## Data model

New collection `kpi_proxy_capabilities`:
```
{
  _id, userId, kpiId,
  platform:        SERVER | WINDOWS | ANDROID,
  language:        e.g. "python" (server/Windows) | a declarative primitive spec (Android)
  sourceCode:       string (or structured spec for Android primitives)
  declaredCapabilities: { network: ["api.trello.com"], secrets: ["trelloToken"], devicePermissions: [...] }
  status:          PENDING_TEST | ACTIVE | DISABLED | NEEDS_REPAIR
  version:         monotonic int, bumped on every regeneration
  consecutiveFailures: int
  createdAt, lastRunAt, lastResult
}
```
`declaredCapabilities` is set at proposal time (see agent loop) and is the enforcement boundary —
execution only ever gets what was declared and approved, nothing implicit.

KPI data itself keeps flowing through the existing `KPIService.addKPIDataForUser()` /
`addKPIData()` path unchanged. Recommend adding a `source` field (MANUAL | PROXY_TRELLO |
PROXY_CAPABILITY | AUTOFILL) to `KPIData` instead of overloading the existing `autoFilled` bool, so
this stays auditable and doesn't interact with the "never overwrite a value" autofill rule in
unexpected ways.

## Server-side execution (pull proxies + server-hosted capabilities)

Isolation goal: one user's broken/malicious capability must never affect another user's, and must
never be able to take down `javaapp` itself.

Rejected: running generated code in-process inside the Java app (dynamically compiling and
loading it into the running JVM). Two independent reasons, not just "risky": `javaapp` runs on
`eclipse-temurin:21-jre-alpine` — a JRE, no compiler available at all — and even switching images,
a bad script sharing the same 256MB heap and single process as production traffic can take the
whole app down (OOM, a pinned thread), while the JVM's own sandboxing tool for this
(`SecurityManager`) is deprecated and being removed from the platform.

Chosen: **ephemeral, per-execution containers, not per-user containers.** One generic runner image
(e.g. `python:slim`), and every execution is its own short-lived container that reads that user's
current capability source fresh from Mongo right before running, with `--memory`/`--cpus` caps, a
narrow network allowlist (or none), a hard timeout, and only the declared secret(s) injected as
env vars. It dies when the run finishes or times out. Editing a capability needs no redeploy step
— the next execution just reads the new version. This gives per-user isolation without the
operational cost of building/rebuilding an image per user per edit, and a crash only ever kills one
disposable container.

`javaapp` needs the ability to launch containers on demand for this — recommend a small dedicated
sidecar service owning that responsibility (holding the Docker socket) rather than mounting it into
`javaapp` directly, so a bug in the main app doesn't hand over container control as a side effect.
Flagged as a real privilege boundary, not fully resolved here — worth a short spike before building.

## Client-side execution (push proxies)

**Windows**: capability code (a script) can be executed close to as-is — no OS permission broker
stands between a script and things like "which window is focused." Minimal wrapper enforces a
timeout and injects only the declared secret(s) into the subprocess env, keeping the capability
from touching the companion's own Drive refresh token directly. Risk accepted here beyond that is
explicit and intentional (see Risk posture) — a script running as the user has the user's rights,
same as any script you'd run yourself.

**Android**: architecturally different, not just "more locked down." Sensitive data (step count,
etc.) sits behind Android's actual permission+API system (Health Connect) — a generic downloaded
script cannot read it directly, only code that's part of the installed app, with the right
permission grants, can call that API. So Android capabilities are **composed from a fixed set of
primitives the companion app ships with** (read Health Connect steps, read screen-on/off events,
read notification count, `AccessibilityService`-based UI scrape as a fallback) rather than fully
arbitrary generated code. New primitives require a real app update; per-KPI *configuration* of
existing primitives stays dynamic. (Play Store's rule against apps that fetch and run remote code
doesn't apply — this is a personal sideloaded APK, never Store-distributed.)

**Discovery-before-build, for steps specifically**: at capability-proposal time, ask what step app
is installed, check whether it already writes to Health Connect (most modern fitness apps do) —
build nothing if so. Only fall back to `AccessibilityService` UI-scraping (real mechanism, same one
automation tools like Tasker use) if that specific app doesn't sync to Health Connect. Scraping is
inherently fragile — it breaks silently whenever the target app's UI changes — so a
scraping-sourced capability should be watched more closely than an API-based one (see circuit
breaker below). iOS has no equivalent to `AccessibilityService` at all — a step proxy there would
need HealthKit or nothing.

## The agent loop

```
1. INTAKE         clarifying conversation: what's already installed, what's realistic
2. PROPOSAL       concrete written spec BEFORE any code exists — platform (pull/push),
                  data source, and exactly what it will be allowed to touch
                  (declaredCapabilities). User approves here — this is the actual
                  permission-granting moment.
3. IMPLEMENTATION code generated (a Claude Code session), scoped to only what step 2 approved
4. TEST GATE      one dry run against mocked data in the sandbox before going live —
                  must not crash, must respect timeout, must return a plausible value
5. DEPLOY         server: no separate step, next scheduled run reads the new version.
                  client: dropped into the capability-deploy mailbox channel.
6. SCRUTINY       first N real runs land in the confirm-inbox at 100%, not the normal
                  sampled rate, tapering down once it's behaved for several clean runs
7. STEADY STATE   sampled confirm-inbox review (target ~20%, tunable per KPI)
8. MAINTENANCE    circuit breaker: N consecutive failures, or a suspiciously flat/
                  out-of-range value, auto-disables (status → NEEDS_REPAIR) and either
                  auto-triggers a repair pass (re-run steps 2-4 seeded with "this used
                  to work, here's what broke") or waits for a manual "fix it" button
                  in the KPI UI — same underlying pipeline either way
```

Capabilities that turn out to be broadly useful (not user-specific) — e.g. a general Health Connect
step reader — are candidates to eventually "graduate" into the real committed `ProxyProvider` set
via a normal reviewed commit, rather than staying as per-user generated data forever. User-specific
one-off logic (some idiosyncratic personal data source) stays as per-user data in
`kpi_proxy_capabilities`, never in the shared repo — the repo is shared across every account on this
deployment, generated per-user logic is not.

## Confirm-inbox (sampled review)

Proxy-sourced values don't require confirmation every time — that's the exact chore this feature
exists to remove. Instead, a configurable fraction (default ~20%, 100% during the scrutiny window
above) surface as a one-tap confirm/edit chip on `/today` instead of committing silently. Everything
else commits immediately, same trust model `autoFillEnabled` already uses for missed days.

## Milestones

Each milestone is scoped to be handed to a subagent independently — goal, dependencies, concrete
scope, explicit non-goals (so it doesn't creep into the next milestone's work), and suggested tests.
"Depends on" milestones must be done first; milestones with no dependency on each other can run in
parallel.

### M1 — Proxy data model + provider scaffolding (no live data source yet)
**Depends on:** nothing (builds on existing `KPI`/`KPIData`/`updater` code as-is).
**Scope:**
- `KPI` gains `proxyType` (enum: `NONE`, `TRELLO_CARD_COUNT`, `MANUAL_PROMPT`), `proxyConfig`
  (`Map<String,String>`), `confirmSampleRate` (double, default 0.2).
- `KPIData` gains `source` (enum: `MANUAL`, `PROXY_TRELLO`, `PROXY_CAPABILITY`, `AUTOFILL`) and
  `pending` (boolean, default false). Existing docs missing `source` should read back as `MANUAL`.
- `ProxyProvider` interface: `Optional<Double> fetchValue(KPI kpi, LocalDate date)`.
- A registry (e.g. `Map<ProxyType, ProxyProvider>`) and one new step in the existing nightly job
  (`habitTracker.updater`) that, for each active KPI with a non-`NONE`/`MANUAL_PROMPT` proxyType,
  resolves the provider and calls `KPIService.addKPIDataForUser()` if a value comes back.
- A single no-op/stub provider (returns a fixed value) so the wiring is testable without live
  Trello credentials — this is what M1's tests exercise, not real Trello.
**Out of scope:** any real Trello call (M2), any UI beyond what's needed to set `proxyType` via the
existing create/edit API bodies (M2/M3 add the actual UI).
**Suggested tests:**
- Unit: `KPI`/`KPIData` (de)serialize with the new fields; a pre-existing Mongo doc missing `source`
  reads back as `MANUAL`.
- Unit: nightly job's proxy step only touches KPIs with a configured proxyType; `NONE`/
  `MANUAL_PROMPT` KPIs are untouched (regression: existing manual-only KPIs see zero behavior
  change).
- Integration (embedded Mongo or test container): one full nightly-job pass with the stub provider
  writes exactly one `KPIData` with the correct `source`.

### M2 — Trello card-count provider
**Depends on:** M1.
**Scope:**
- Per-user Trello credential storage (token + board/list id) — new small collection, not reused
  session auth.
- `TrelloCardCountProvider` implementing `ProxyProvider`: counts cards moved to a configured "done"
  list on the given date via Trello's REST API.
- KPI create/edit UI: a "Trello card count" proxy option with board/list configuration.
**Out of scope:** credential UI polish, any proxy type besides Trello.
**Suggested tests:**
- Unit: provider logic against a mocked Trello HTTP client — fixtures for empty list, cards with no
  move date, cards moved multiple times, cards moved on a different day.
- Unit: missing/invalid credentials return `Optional.empty()` and log rather than throw or crash
  the nightly batch for other KPIs.
- Integration: fake Trello server (e.g. WireMock) verifying request shape (auth header, correct
  list id) and the resulting `KPIData`.

### M3 — Sampled confirm-inbox UI
**Depends on:** M1 (needs `pending`/`source`/`confirmSampleRate`); can run in parallel with M2.
**Scope:**
- Proxy writes roll `confirmSampleRate` at write time; a "hit" sets `pending=true` instead of
  committing silently.
- `/today` page: fetch this user's pending `KPIData`, render a confirm/edit chip. Confirm clears
  `pending`. Edit overwrites the value, clears `pending`, and sets `source=MANUAL` (human correction
  supersedes the proxy's guess).
- KPI settings UI: a `confirmSampleRate` control per KPI.
**Suggested tests:**
- Unit: sampling decision takes an injectable random source (no flaky random-seeded tests).
- Unit: confirm/edit endpoints enforce per-user ownership (can't confirm another user's `KPIData`).
- Manual/UI: chip appears for a pending point, disappears after confirm or edit; edited value
  persists with `source=MANUAL`.

### M4 (spike) — Container-sandboxing feasibility
**Depends on:** nothing; can start anytime, ideally before M7.
**Scope:** not production code — answer, in a short write-up plus a throwaway proof-of-concept
script: can the host reasonably launch ephemeral `--memory`/`--cpus`/`--network`-capped containers
on demand from something `javaapp`-adjacent; does a small sidecar service holding the Docker socket
(instead of `javaapp` itself) work cleanly with the existing docker-compose network; rough resource
math for how many concurrent ephemeral runs this box can absorb alongside the existing 512MB Mongo +
384MB app containers.
**Suggested tests:** none required (spike) — deliverable is the findings write-up plus the PoC
script showing one script executed in an ephemeral capped container with a timeout enforced.

### M5 — Device pairing
**Depends on:** nothing (independent of M1-M4); needed before M6/M9/M10.
**Scope:**
- Pairing-code endpoint: short one-time code (reuse `DriveOAuthService`'s in-memory nonce pattern),
  tied to `userId`, short TTL. `POST /api/sync/pair {code}` (unauthenticated by design — the code is
  the credential) returns `{mailboxFolderId, encryptionKey}`.
- "Pair a device" UI showing the code.
- A minimal companion script (any single platform first, e.g. Windows) that: prompts for the code,
  redeems it, then performs its own Google OAuth-for-installed-apps flow (localhost redirect) to get
  its own Drive refresh token, independent of the server's.
**Out of scope:** any actual capability execution — this milestone only proves a device can pair and
write one hand-authored test file into the mailbox that `MailboxConsumeService` correctly consumes.
**Suggested tests:**
- Unit: pairing code generation/expiry/single-use enforcement.
- Integration: full pairing handshake against a mocked Google OAuth token endpoint.
- Manual: a real device pairs once, writes one `kind: "kpi-value"` file, and it shows up as real
  `KPIData` within one `MailboxConsumeService` cycle.

### M6 — Capability-deploy mailbox channel (server → device)
**Depends on:** M5.
**Scope:** second Drive subfolder (`_capability_deploy`) the companion polls; server writes
`{capabilityId, version, sourceCode}` encrypted the same way as mailbox writes; companion compares
version to its local cache, downloads and stores newer versions.
**Suggested tests:**
- Unit: version-comparison logic (skip already-current, accept newer, reject older/malformed).
- Unit: encryption round-trip using the existing `VaultEncryptionService` (no new crypto code).
- Integration: server writes a new version, companion polling logic picks it up and updates its
  local copy.

### M7 (spike) — End-to-end sandboxed execution, one fixed script
**Depends on:** M1, M4.
**Scope:** prove the full server-side execution pipeline end to end using one hand-written script
stored in `kpi_proxy_capabilities` (no LLM generation yet) — read from Mongo, run in an ephemeral
capped container per M4's findings, capture stdout, land the result via
`KPIService.addKPIDataForUser()`. This validates the execution model before M8 adds generation on
top of it.
**Suggested tests:**
- Integration: stored script runs, produces a `KPIData` with `source=PROXY_CAPABILITY`.
- Integration: a script that hangs past its timeout is killed and does not affect other users'
  scheduled runs.
- Integration: a script that throws/exits non-zero is recorded as a failure, not silently dropped.

### M8 — Agent proposal + generation loop
**Depends on:** M7.
**Scope:** implements agent-loop stages 1-4 (intake → proposal → implementation → test gate) for
server-side capabilities: a conversational flow that produces a `declaredCapabilities`-scoped spec,
generates `sourceCode` for it, and dry-runs it against mocked data before setting `status=ACTIVE`.
**Suggested tests:**
- Unit: generated capability's declared network/secret access is enforced at execution time — a
  script trying to reach an undeclared host or secret is blocked/fails, not silently allowed.
- Integration: a full intake→proposal→generation→test-gate pass for one concrete example (e.g. a
  hypothetical simple API-based KPI) produces a working `ACTIVE` capability.

### M9 — Windows companion reference capability
**Depends on:** M5, M6, M8 (or M7 if generation isn't ready yet — can ship a hand-written reference
capability first).
**Scope:** one real, working push capability — coding-hours via active-window tracking — including
the timeout + scoped-secret-injection wrapper described in the design doc.
**Suggested tests:**
- Unit: wrapper enforces timeout and only injects declared secrets into the subprocess env.
- Manual: real capability runs on a real Windows machine for several days, values look right.

### M10 — Android companion reference capability (Health Connect)
**Depends on:** M5, M6.
**Scope:** one real primitive — read step count from Health Connect — plus the discovery step (ask
what step app is installed, check whether it already writes to Health Connect) ahead of assuming a
build is needed at all.
**Suggested tests:**
- Unit: Health Connect permission-missing case handled gracefully (prompts for grant, doesn't
  crash).
- Manual: real device, real installed step app, correct daily count flows through to a KPI.

### M11 — Android `AccessibilityService` fallback capability
**Depends on:** M10 (only needed when Health Connect isn't available for a given app).
**Scope:** UI-scraping fallback for a step app that doesn't sync to Health Connect. Flag this
capability type for tighter circuit-breaker scrutiny (M12) given its fragility to target-app UI
changes.
**Suggested tests:**
- Manual only, realistically (UI-scraping against a real third-party app's real screen) — no
  meaningful unit test surface. Document the target app + version tested against, since this is
  exactly what breaks silently on an app update.

### M12 — Maintenance loop
**Depends on:** M1 (or M8, if generated capabilities exist by then).
**Scope:** consecutive-failure / anomalous-value circuit breaker that flips `status=NEEDS_REPAIR`;
auto-repair trigger (re-runs proposal→implementation→test-gate seeded with the failure) and a
manual "fix it" button in the KPI UI hitting the same pipeline.
**Suggested tests:**
- Unit: N consecutive failures (configurable threshold) flips status; a single transient failure
  does not.
- Unit: anomaly check (e.g. value far outside historical EMA range) flags for repair even without an
  outright failure.
- Integration: triggering repair on a broken stub capability produces a new version and clears
  `NEEDS_REPAIR` once the test gate passes.

## Open questions / needs a spike before committing

- Exact mechanism for `javaapp` to launch sandboxed containers without holding the Docker socket
  directly (sidecar service design).
- Whether Chicory/WASM is worth revisiting later for tighter server-side isolation than a
  container-per-execution gives.
- Concrete resource math: how many concurrent ephemeral containers a home server with existing
  512MB Mongo + 384MB app containers can absorb before this needs its own capacity plan.
- Windows companion install/run mechanism in detail (Task Scheduler vs. a background service) —
  noted as "just run a script" for now, not yet designed.

# Runner-only KPIs + runner-held KPI credentials

Status: IN PROGRESS. H1 + H2 + H3 + H4 DONE (2026-11-08; R1/R2 merged in the Relay repo); H5 not started. Supersedes the SERVER platform + egress allowlist parts of `kpi-tracking-agent.md`.
Scope: **KPIs only.** Trello (Projects/planner/board sync + the Trello card-count KPI), Drive sync and Relay chat/files/git stay as they are and must keep working.

## Goal
- No user-influenced code runs on the hub. Every AI-generated KPI collector runs on the user's own runner.
- KPI API credentials (GitHub etc.) live only on the user's runner. A hub breach leaks none of them; a runner breach leaks only that user's.
- The nightly flow is unchanged: the runner scheduler runs collectors at `daily HH:MM` → stores readings → hub pulls them (`RunnerCollectorProvider`).

## What already exists
Runner (Relay repo, `runner/internal/collectors/`):
- `scheduler.go`: ticks every minute, 7-day backfill after downtime.
- `engine.go` + `sandbox/`: runs the script in an OS sandbox with **no network** (128 MB, 60 s).
- `sources.go`: the **runner itself** (trusted code) fetches declared sources (`activitywatch`, `file`) → `/in/...` for the script.
- `manifest.go`: sources + schedule + window = the contract hash the user approves.

Hub ↔ runner transport (already built, see `relay/FLOWS.md`):
```
runner dials OUT: GET /relay/uplink (Bearer device token) → WebSocket held open, pinged every 30s
browser → ANY /relay/api/devices/{id}/v1/** (login + 2FA gate, owner check) → RelayProxyController
        → "req" frame down the WebSocket → runner's local HTTP API → "res" frame back
```
No inbound port on the user's machine, no new connection needed.

## Credential entry (UI → runner volume)
```
Relay → runner page → "KPI credentials" → [name, host, token] → Save
  → PUT /relay/api/devices/{id}/v1/credentials/{name}   (existing proxy, 2FA-gated)
  → uplink WebSocket → runner PUT /v1/credentials/{name}
  → runner stores it in $RELAY_HOME (OS keychain; fallback: encrypted 0600 file)
GET /v1/credentials → names + hosts only, never values.  DELETE /v1/credentials/{name}.
```
The token crosses the hub once, in memory. Rule: the proxy and runner never log request bodies for `/v1/credentials`.

## Collector using a credential
```
Agent writes manifest: sources: [{type:"http", credential:"github", url:"https://api.github.com/...", why}]
User approves the contract: "calls api.github.com with your 'github' credential"
Runner engine fetches it (adds the auth header; GET only; URL host must equal the credential's host;
no off-host redirects; size cap) → /in/http/<i>.json → sandboxed script (still no network) → reading
```
The script never sees the token.

## Stages

| # | Where | What |
|---|-------|------|
| R1 | Relay runner | Credential store + `GET/PUT/DELETE /v1/credentials`. No body logging. |
| R2 | Relay runner | `http` source type (manifest validation, `sources.go` fetch, included in the contract hash). |
| H1 | Hub | **DONE** (`RunnerOnlyKpiMigration` also sets SANDBOX_CAPABILITY KPIs to manual). **Remove server KPIs completely**: `SandboxCapabilityProvider`, `SandboxClient`, `HttpSandboxClient`, `CapabilitySecret*`, egress token code, `ProxyType.SANDBOX_CAPABILITY`, `CapabilityPlatform.SERVER/WINDOWS` (the runner is OS-independent, so WINDOWS is redundant; ANDROID stays reserved, see "Phone"), the sandbox-facts prompt, the non-runner branches in `CapabilityAgentService`/`CapabilityLifecycleService.wire()`. Migration: existing SERVER capability docs → `RETIRED`; drop `kpi_capability_secrets` (0 rows). |
| H2 | Infra | **DONE** (old containers need one manual `docker compose up -d --remove-orphans`, see FLOWS_infra.md). Delete `sandbox/` + `egress-proxy/` (containers, dirs, `SANDBOX_EGRESS_SECRET`): 7 → 5 containers. CLAUDE.md, FLOWS_infra.md. |
| H3 | Hub | **DONE** (`CredentialRef`, `RunnerCollectorPipeline.runnerCredentials`, `checkSources` names the missing credential, `CollectorStaticCheck.httpProblem`). Agent prompt `kpi-collector-generate.md`: teach the `http` source + the user's credential names (fetched from the runner, names only). |
| H4 | Hub UI | **DONE** (`relay-credentials.js` on `#/d/<id>`; `GET .../capability/runner` + "Connect a runner" in Automate; `RelayProxyCredentialLoggingTest`). "KPI credentials" section on the Relay runner page. Automate dialog with no runner online → "Connect a runner". |
| H5 | Both | Regression check: Trello (board sync, planner Add-to-board, Trello KPI), Relay chat/files/git, a full runner collector flow end to end. FLOWS + graphify update. |

Order: H1+H2 (independent, can start now) → R1 → R2 → H3 → H4 → H5.

## Untouched on purpose
- Trello: `TrelloCardCountProvider` (our own code, built-in) + `trello_credentials` + Projects. Not user code, so not a sandbox concern.
- Drive sync (`UserSyncSettings`), TOTP, `KPI_SECRETS_KEY`.

## Phone (ANDROID) — later phase, [NOT IMPLEMENTED]
A runner on the phone is not realistic (battery, background kill), so the phone gets its own path:
- habitTracker's Android side holds a set of collection scripts that run **when the user opens the phone/app** (no always-on daemon).
- Prefer pre-built tools: read existing data (Health Connect steps from another app, UsageStats screen time).
- Fallback: on-demand capabilities, e.g. light automation that opens an app to read a value, or its own proxy (a built-in step counter).
- This extends the 2026-11-04 "phone as dumb sensor" idea in `kpi-sensors.md`, which keeps the thinking on the PC runner. That trade-off gets its own design pass.
`CapabilityPlatform.ANDROID` stays in the enum as a placeholder until then.

## Technology Notes (where this breaks)
- **Runner offline**: no collection. Backfill covers 7 days; a longer gap leaves holes.
- **Proxy timeout**: proxied calls give up after 30 s (`relay.proxy.timeout-ms`); a credential save is tiny, fine.
- **Credentials are per runner**: two runners = enter twice. Reinstall or a lost machine = re-enter (the price of not storing them centrally).
- **In-transit exposure**: the hub sees the token once while proxying. A compromised hub could intercept *new* entries, but there is no stored pile to steal.
- **Token scope**: a collector can GET anything the token can read on its one bound host. The UI should advise read-only, minimal-scope tokens.
- **No runner = manual KPIs only.** Acceptable for a free app.

# KPI multi-device, installs, redefine — frozen contract (wave 0)

Status: wave 0 frozen 2026-11-08. Builds on `runner-only-kpis.md`. Every agent works ONLY to these shapes.
If a shape here is wrong for your task, STOP and report back — never invent a variant.

Repos: hub = `/home/victor/Desktop/habitTracker` (branch master), runner = `/home/victor/Documents/Relay` (branch main).
Go is NOT installed locally: run runner tests with
`docker run --rm -v /home/victor/Documents/Relay:/src -w /src/runner golang:1.22 go test ./...` (use your worktree path).
Hub tests: `./mvnw -q test` in `habitTracker/`.

## Principles
1. **One script per KPI.** `collector.py` + fixtures are identical on every device. OS differences live only in manifest variants.
2. **Variants keep the `/in` layout identical**: every variant has the same number of sources, same `type` sequence; only
   paths / URLs / buckets / package ids differ. So fixtures are shared.
3. **Desired state.** The hub stores what *should* be on each device (`kpi_deployments`); a reconciler converges when a runner is online.
4. **The runner installs packages itself** (trusted code) from the approved manifest. The LLM never gets a shell.
5. **Combined series = existing `KPIData`.** Per-device raw values live in `kpi_device_readings`; charts/targets/goals are untouched.

## Hub bundle manifest (stored in `KPIProxyCapability.collectorBundle`)
```json
{
  "schedule": "daily 23:30", "window": "1d", "unit": "hours", "aggregation": "sum",
  "combine": "sum",                       // sum | max | avg | latest — across devices
  "variants": {
    "windows": {"requires": [...], "sources": [...]},
    "linux":   {"requires": [...], "sources": [...]},
    "darwin":  {"requires": [...], "sources": [...]}
  }
}
```
OS keys = Go `runtime.GOOS` values (`windows`, `linux`, `darwin`). A legacy bundle with top-level `sources` and no
`variants` = one variant for the OS of the device it was installed on (migration rewrites it).

**Resolved manifest** = what the hub sends one runner (`POST /v1/collectors`): `{schedule, window, unit, aggregation, requires, sources}`
taken from the device's variant. `combine`/`variants` never reach the runner.
Hub helper: `CollectorBundle.resolve(String os) → ObjectNode` (throws if no variant for that os).

## Runner: `requires` + package ledger (Relay)
Manifest gains optional `requires` (max 5):
```json
{"manager": "relay|winget|brew|flatpak|pip", "id": "<exact package id>", "why": "<shown to the user>"}
```
- `relay` = built-in installers; only id `activitywatch` (existing `sensors.go`, pinned AWVersion).
- id regex per manager: winget `^[A-Za-z0-9.+-]{1,128}$`, brew `^[a-z0-9@+._-]{1,64}(/[a-z0-9._-]+){0,2}$`,
  flatpak reverse-DNS `^[A-Za-z0-9_.-]{3,128}$`, pip `^[A-Za-z0-9._-]{1,64}(==[A-Za-z0-9.*+!-]{1,32})?$`. No URLs, no spaces, no flags.
- **`ContractHash` covers `{sources, schedule, window, requires}`** (add `requires`; absent = not hashed, so old hashes stay valid).
- No sudo ever. apt/dnf are not managers: the agent must not declare them.

Ledger file `$RELAY_HOME/packages.json` (0600):
```json
[{"manager":"winget","id":"ActivityWatch.ActivityWatch","installedAt":"RFC3339","installedByRelay":true,"usedBy":["<collectorId>"]}]
```
`installedByRelay=false` when the package was already present before the runner tried → the runner NEVER uninstalls it.

Runner API (all under existing auth):
| Route | Body / result |
|---|---|
| `GET /v1/runner/info` | add `"os": runtime.GOOS, "arch": runtime.GOARCH`, `"managers": ["relay","winget",...]` (only those found on PATH) |
| `GET /v1/packages` | `[{manager,id,installedAt,installedByRelay,usedBy:[...],state:"installed|installing|failed|awaiting_confirm",error}]` |
| `POST /v1/packages/uninstall` | `{manager,id}` → 409 if `usedBy` non-empty; 400 if `installedByRelay=false`; 202 async |
| `POST /v1/collectors/{id}/approve` | unchanged body `{contractHash}`. If manifest has `requires` not yet installed → state `awaiting_confirm`, returns 202 `{state:"awaiting_confirm"}`; installs run after local confirm |
| `GET /local/confirm` (loopback only) | runner-served HTML page listing pending installs; Confirm/Deny buttons POST `/local/confirm/{token}`. **Must refuse any request arriving through the uplink** (mark uplink requests in context in `uplink.dispatch`) and any non-loopback RemoteAddr |

Collector `DELETE /v1/collectors/{id}` removes the id from every `usedBy`; never uninstalls by itself.
`GET /v1/collectors` Info gains `"requiresState": "none|awaiting_confirm|installing|ready|failed"`.

## Hub data model
### `KPIProxyCapability` (existing) — changes
- ADD `List<String> targetDeviceIds` (required for RUNNER), `String combine` (default `"sum"`), `Boolean meaningChanged` (R4: set when a redefine changes unit/sources).
- MOVE OUT (to `KpiDeployment`, then delete): `runnerDeviceId`, `collectorId`, `readingsSince`, `lastPulledOn`, `collectorActivated`.
  Keep `contractHash`/`approvedContractHash` on the capability only as the hash the user saw for the *proposing* device (display); per-device hashes live on deployments.

### `KpiDeployment` — collection `kpi_deployments`, package `habitTracker.KPI.deploy`
```
id, userId, kpiId, capabilityId, capabilityVersion, deviceId, os,
target: ACTIVE | REMOVED,
status: PENDING | INSTALLING | AWAITING_CONFIRM | ACTIVE | FAILED | REMOVING | REMOVED,
collectorId, contractHash (runner-computed, resolved manifest), approved (bool), activated (bool),
readingsSince (String RFC3339), lastPulledOn (LocalDate),
attempts (int), nextAttemptAt (Instant), lastError (one plain sentence),
createdAt, updatedAt
```
Unique index `(kpiId, deviceId, capabilityVersion)`. Repo: `KpiDeploymentRepository`.
Redefine (R3): new version's rows created `target=ACTIVE`; old version's rows flip to `target=REMOVED` **only after** the new row on the same device is `ACTIVE`.

### `KpiDeviceReading` — collection `kpi_device_readings`
```
id, userId, kpiId, deviceId, capabilityVersion, date (LocalDate), value (double), unit, recordedAt (String)
```
Unique `(kpiId, deviceId, date)` — newest `recordedAt` wins.

### `KPIData` (existing) — additive only
`Integer devicesReported`, `Integer devicesExpected` (null for single-device / manual). Partial day = reported < expected.

### Combine (A2) — `DeviceCombiner.combine(String rule, List<Double>) → Double`
sum | max | avg | latest(by recordedAt). Combined value recorded through the existing `ProxyReadingRecorder` path (keeps SHADOW/plausibility/breaker).

## Hub ↔ runner client additions (`relay/RunnerCollectorClient`)
`info(user, device) → {os, arch, managers}`, `packages(user, device)`, `uninstallPackage(user, device, manager, id)`.

## Ownership per agent (avoid overlapping files)
| Agent | Owns (creates/edits) | Must not touch |
|---|---|---|
| ① hub data | `KPI/deploy/*` (model+repo), `KPIProxyCapability`, `CollectorBundle.resolve`, migration `MultiDeviceMigration`, `RunnerCollectorProvider` refactor to iterate deployments (same behaviour, one device) | UI, prompts |
| ② runner ledger | `collectors/manifest.go` (requires + hash), new `collectors/packages.go`, `/v1/packages*`, `runner/info` os/arch/managers; `relay` manager wraps `sensors.go` | installers, uplink |
| ③ linux installer | `runner/install/` (AppImage or .deb, user systemd unit, opens claim page) | collectors |
| ④ reconciler | `KPI/deploy/KpiDeploymentReconciler` (+ uplink-connected event listener, 5-min sweep, backoff) | pull/readings |
| ⑤ aggregation | `KpiDeviceReading*`, `DeviceCombiner`, pull→device reading→combine in `RunnerCollectorProvider` | reconciler |
| ⑥ runner installs | winget/brew/flatpak/pip managers in `packages.go`, local confirm page, uplink context mark | manifest hash |
| ⑦ UI devices | Automate dialog device chips, approval screen "Runs on…", per-KPI deployment status row | U menu |
| ⑧ UI uninstall + dashboard | retire dialog uninstall offer, "Installed by KPIs" on Relay device page, per-device breakdown | Automate dialog |
| ⑨ prompt | `prompts/kpi-collector-generate.md` + `CollectorStaticCheck` (variants rule, requires allowlist) | UI |
| ⑩ redefine | "Redefine" button + lifecycle (old keeps running, rollout, meaningChanged, orphans) | reconciler internals |

## Tests each agent must add (and run green before reporting)
See the plan in chat: variants hash/same-script, reconciler offline/online/idempotent/backoff/remove,
combine rules + partial + backfill, ledger never touches preexisting + refcount + confirm deny,
redefine no-gap, migration, uplink cannot hit `/local/confirm`.

## Technology Notes
- **Local confirm on headless Linux**: nobody sees the page → deployment stays `AWAITING_CONFIRM`; UI tells the user to open `http://127.0.0.1:7777/local/confirm` on that machine. Port 7777 is the runner default (`runner.env`); changing it changes the URL.
- **winget user scope**: some packages ignore `--scope user` and prompt UAC → install fails → `FAILED` with that sentence; never retried with elevation.
- **flatpak --user** needs the flathub remote added for the user; the runner adds it `--user --if-not-exists`.
- **pip** installs into `$RELAY_HOME/venv` only; a collector script still can't import it (sandbox), so pip is for CLI tools used by sources only.
- **Ledger is per runner data dir**: wiping `$RELAY_HOME` forgets what Relay installed → those become un-uninstallable from the UI (the user removes them by hand).
- **Clock/timezone**: readings keyed by the device's local `from` date; two devices in different timezones combine by date string, not instant.
- **Reconciler is single-node, in-process** (@Scheduled + event). Two hub instances would double-send installs; idempotent by `(collectorId, contractHash)` on the runner.

# Sandbox Sidecar Flows

Files: `app.py`, `Dockerfile`, `test_app.py`, `real_run_check.py` (egress: see `/egress-proxy/FLOWS.md`, `real_egress_check.py` there)

Java side: `habitTracker/KPI/HttpSandboxClient.java`, `SandboxCapabilityProvider.java` (see `KPI/FLOWS.md`).

## Run flow

`SandboxCapabilityProvider` → `HttpSandboxClient.run()` → `POST sandbox:8090/run {userId, capabilityId, language, sourceCode, timeoutSeconds, allowedHosts?, secrets?}` (`allowedHosts` = capability's approved hosts; `secrets` = ONLY its declared secrets, decrypted for its owner by `CapabilitySecretService.requestFor()`) → `Handler.do_POST()` → `run_capability()`:
1. `validate()` + `validate_extras()` — python only, non-empty source <= 64KB, timeout clamped to `SANDBOX_MAX_TIMEOUT_SECONDS`; hosts must be plain FQDNs (<=10), secret names `[A-Za-z_][A-Za-z0-9_]*` (<=10, reserved names/prefixes refused), values 4-4096 chars single-line. Bad input → HTTP 400 (Java sees non-2xx → `SIDECAR_ERROR`). Hosts requested but `SANDBOX_EGRESS_SECRET` unset/short → `SIDECAR_ERROR` (fail closed, nothing runs).
2. `_slots.acquire(60s)` — max `SANDBOX_MAX_CONCURRENT` (4) at once; else `SIDECAR_ERROR` "busy".
3. `ensure_image()` — `docker image inspect`, else `docker pull` (own 180s budget, before the script timer).
4. Network + env selection (`run_capability()`): no hosts → `--network none`. Hosts → `--network $SANDBOX_EGRESS_NETWORK` (the `internal: true` net) and `mint_egress_token()` (HMAC of `{hosts, exp = timeout+30s, runId}`) → `HTTP(S)_PROXY=http://<token>:x@egress-proxy:3128`. Secrets + proxy vars go into a 0600 temp file (`write_env_file()`, `docker run --env-file`), deleted in `finally` (also on timeout/error). To change token TTL: `EGRESS_TOKEN_GRACE_SECONDS`.
5. `docker run` via `build_docker_cmd(name, network, env_file)` with script on stdin (`python3 -`) → `subprocess.run(timeout=T)`.
6. Output is run through `redact()` (every secret value + the token → `[REDACTED]`) BEFORE truncation. Timeout → `docker rm -f <name>` → `{outcome: TIMEOUT}`. Exit 0 → `OK`. Exit 125-127 → `SIDECAR_ERROR` (docker failed, not the script). Other → `NONZERO_EXIT`.
7. Response is always HTTP 200 JSON `{outcome, exitCode, stdout, stderr}` (output truncated at 64KB).

To change container limits/flags: `build_docker_cmd()` (hardcoded on purpose), `SANDBOX_MEM`, `SANDBOX_CPUS`. To change concurrency: `SANDBOX_MAX_CONCURRENT`. To change runner image: `SANDBOX_IMAGE` (compose env).

Startup: `cleanup_orphans()` force-removes containers labeled `kpi-sandbox=1`.

## Testing

- `cd sandbox && python3 -m unittest` — docker mocked via injected `run=`.
- `SANDBOX_IMAGE=python:slim python3 real_run_check.py` — real daemon: ok, non-zero, no network, read-only fs, timeout kill, no leftovers.
- `SANDBOX_IMAGE=python:slim python3 ../egress-proxy/real_egress_check.py` — real daemon, throwaway `kpi-egtest-*` proxy + internal network (never touches compose services): allowed host reachable, others denied, direct egress fails, private targets denied, cleanup.

## Technology Notes

- `/var/run/docker.sock` is mounted into this container (see `docker-compose.yml` service `sandbox`): host-root equivalent. The container runs as root inside to be able to use the socket. Mitigations: no published port, hardcoded docker flags, 64KB/timeout/concurrency limits. NOT mitigated: no auth on the HTTP API (compose-network trust only), no gVisor/rootless.
- `docker.sock` path is the Linux default; on Docker Desktop (Windows) the same mount works through the VM but sibling containers then run in that VM; the Windows runner script is untested with this.
- Ephemeral containers are siblings on the host daemon, not children: their memory is NOT inside the sidecar's 64m `mem_limit`. Worst case total = 4 x 128m on top of the stack.
- State is in-memory only (semaphore); restart drops in-flight runs (Java sees `SIDECAR_ERROR`) and orphans are cleaned on boot.
- Runner image is pulled lazily and never refreshed; a missing image with no internet fails runs as `SIDECAR_ERROR`.
- Output/script size and timeout caps are module constants in `app.py`.
- **`SANDBOX_EGRESS_SECRET`** (`.env`, >= 32 chars, shared with `egress-proxy`): unset/short → sidecar refuses any run that has `allowedHosts` (`SIDECAR_ERROR`, fail closed); runs without hosts are unaffected. Rotating it: restart `sandbox` and `egress-proxy` together; only in-flight runs break (tokens live `timeout + 30s`).
- **Secrets are visible to `docker inspect`** (container `Config.Env`) and to anything with Docker access (host root) for the duration of a run; the 0600 env-file keeps them off the `docker run` command line / `ps`, and it is deleted right after. They are also readable by the script itself (that is the point), so a script can print/exfiltrate them to any allowed host: grant hosts and secrets accordingly.
- **Redaction is best-effort:** literal substring replace only. Base64/hex/split/reversed output evades it; the stored `lastResult`/`failureReason` (and LLM repair prompts) are only as safe as that.
- **Runners on the egress network can reach each other** (same `internal` bridge, no per-run network). They hold no secrets on the wire, but a malicious script could listen for a sibling run's connections; per-run networks would need the proxy to join them dynamically (needs Docker socket), so it was not done.
- **Network name:** `SANDBOX_EGRESS_NETWORK` must equal the explicit `name:` of the compose network (`habit_sandbox_egress`); a mismatch makes every networked run `SIDECAR_ERROR` ("network not found"). The sidecar is NOT on that network itself (docker run attaches runners via the daemon).

## Change Index

| What to change | Where |
|---|---|
| Docker flags / hardening | `app.py` `build_docker_cmd()` |
| Memory / CPU per script | env `SANDBOX_MEM`, `SANDBOX_CPUS` |
| Concurrency / queue wait | env `SANDBOX_MAX_CONCURRENT`, `SANDBOX_QUEUE_WAIT_SECONDS` |
| Max script timeout | env `SANDBOX_MAX_TIMEOUT_SECONDS` |
| Runner image / pull budget | env `SANDBOX_IMAGE`, `SANDBOX_PULL_TIMEOUT_SECONDS` |
| Input limits | `app.py` `MAX_SOURCE_BYTES`, `validate()` |
| Port | env `SANDBOX_PORT` (Java: `SANDBOX_URL`) |
| Compose service / socket mount | `docker-compose.yml` `sandbox` |
| Egress network / proxy host / port | env `SANDBOX_EGRESS_NETWORK`, `EGRESS_PROXY_HOST`, `EGRESS_PROXY_PORT` |
| HMAC secret / token TTL | env `SANDBOX_EGRESS_SECRET`; `EGRESS_TOKEN_GRACE_SECONDS`, `mint_egress_token()` |
| Secret/host input rules, reserved env names | `app.py` `validate_extras()`, `RESERVED_ENV_*`, `HOST_RE` (mirror `CapabilityGrants.java`) |
| Redaction | `app.py` `redact()` |

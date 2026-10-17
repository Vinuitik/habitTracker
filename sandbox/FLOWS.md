# Sandbox Sidecar Flows

Files: `app.py`, `Dockerfile`, `test_app.py`, `real_run_check.py`

Java side: `habitTracker/KPI/HttpSandboxClient.java`, `SandboxCapabilityProvider.java` (see `KPI/FLOWS.md`).

## Run flow

`SandboxCapabilityProvider` → `HttpSandboxClient.run()` → `POST sandbox:8090/run {userId, capabilityId, language, sourceCode, timeoutSeconds}` → `Handler.do_POST()` → `run_capability()`:
1. `validate()` — python only, non-empty source <= 64KB, timeout clamped to `SANDBOX_MAX_TIMEOUT_SECONDS`. Bad input → HTTP 400 (Java sees non-2xx → `SIDECAR_ERROR`).
2. `_slots.acquire(60s)` — max `SANDBOX_MAX_CONCURRENT` (4) at once; else `SIDECAR_ERROR` "busy".
3. `ensure_image()` — `docker image inspect`, else `docker pull` (own 180s budget, before the script timer).
4. `docker run` via `build_docker_cmd()` with script on stdin (`python3 -`) → `subprocess.run(timeout=T)`.
5. Timeout → `docker rm -f <name>` → `{outcome: TIMEOUT}`. Exit 0 → `OK`. Exit 125-127 → `SIDECAR_ERROR` (docker failed, not the script). Other → `NONZERO_EXIT`.
6. Response is always HTTP 200 JSON `{outcome, exitCode, stdout, stderr}` (output truncated at 64KB).

To change container limits/flags: `build_docker_cmd()` (hardcoded on purpose), `SANDBOX_MEM`, `SANDBOX_CPUS`. To change concurrency: `SANDBOX_MAX_CONCURRENT`. To change runner image: `SANDBOX_IMAGE` (compose env).

Startup: `cleanup_orphans()` force-removes containers labeled `kpi-sandbox=1`.

## Testing

- `cd sandbox && python3 -m unittest` — docker mocked via injected `run=`.
- `SANDBOX_IMAGE=python:slim python3 real_run_check.py` — real daemon: ok, non-zero, no network, read-only fs, timeout kill, no leftovers.

## Technology Notes

- `/var/run/docker.sock` is mounted into this container (see `docker-compose.yml` service `sandbox`): host-root equivalent. The container runs as root inside to be able to use the socket. Mitigations: no published port, hardcoded docker flags, 64KB/timeout/concurrency limits. NOT mitigated: no auth on the HTTP API (compose-network trust only), no gVisor/rootless.
- `docker.sock` path is the Linux default; on Docker Desktop (Windows) the same mount works through the VM but sibling containers then run in that VM; the Windows runner script is untested with this.
- Ephemeral containers are siblings on the host daemon, not children: their memory is NOT inside the sidecar's 64m `mem_limit`. Worst case total = 4 x 128m on top of the stack.
- State is in-memory only (semaphore); restart drops in-flight runs (Java sees `SIDECAR_ERROR`) and orphans are cleaned on boot.
- Runner image is pulled lazily and never refreshed; a missing image with no internet fails runs as `SIDECAR_ERROR`.
- Output/script size and timeout caps are module constants in `app.py`.

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

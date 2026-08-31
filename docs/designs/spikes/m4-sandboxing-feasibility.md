# M4 Spike — Container-Sandboxing Feasibility

Status: spike complete, 2026-08-31. Answers the M4 open questions in
`docs/designs/kpi-tracking-agent.md`. Not production code — see
`tools/spikes/m4_sandbox_poc.py` for the throwaway PoC referenced below.

## 1. Can a sidecar launch capped, isolated, timed-out ephemeral containers?

Yes, confirmed live on this exact host (see Evidence below). `docker run` from any
process in the `docker` group (no sudo needed — this session's user is already in
`docker`) supports everything the design doc asked for in one invocation:

- `--memory 128m --memory-swap 128m` — hard memory cap, swap disabled beyond it
- `--cpus 0.25` — CPU share cap
- `--network none` — no network at all (the PoC's case); a real allowlist would
  instead point the container at an egress-filtering proxy or a dedicated
  no-route-to-prod bridge network (see integration note below) — Docker itself
  has no per-destination allowlist primitive, so "allowlist" has to be built as
  an extra network hop, not a flag
- `--pids-limit`, `--cap-drop ALL`, `--security-opt no-new-privileges`,
  `--read-only` + `--tmpfs /tmp` — cheap extra hardening, no reason not to use
  all of it for untrusted code
- A hard timeout is **not** a Docker flag — it has to be enforced client-side
  (`subprocess.run(..., timeout=N)`) with an explicit `docker kill <name>`
  follow-up if it fires, because `--rm` alone does not fire on a killed client
  process. Confirmed this path separately: a 30s-sleep container was killed at
  the 3s client timeout and left no trace afterward.

**Does it integrate cleanly with the existing compose network?** Partially yes,
with one design decision to carry forward, not a blocker:

- `docker-compose.yml` defines no custom `networks:` block, so compose
  auto-creates `habittracker_default` (confirmed via `docker network ls` /
  `docker inspect javaapp`) — a plain bridge network. A new sidecar service
  added to this compose file joins it the normal way and can reach `javaapp` by
  container DNS name, no extra wiring.
- The ephemeral *runner* containers themselves should **not** join
  `habittracker_default` — that network is where `javaapp` and `mongodbHabit`
  live, and putting an untrusted script's container on it would make both
  reachable by container DNS from inside the sandbox. Default should stay
  `--network none` (matches most capabilities' actual needs — reading a value
  from an installed app or computing something rarely needs network at all);
  a capability that declares a real external host need should go through a
  small egress proxy or a separate isolated network with no route to
  `habittracker_default`, not get bridged onto the app network directly. This
  is a real design decision for M7/M8, not just an implementation detail — flag
  it there.

## 2. Resource math

Current real load on this box (`docker stats --no-stream`, `free -h`, taken live
during this spike):

| | |
|---|---|
| Host | 8 CPUs, 7.1GiB RAM |
| `free -h` available | 3.0GiB (852MiB free + 2.5GiB reclaimable cache) |
| Swap in use | 1.9GiB / 4.0GiB — already non-zero before adding anything |
| habitTracker containers | mongodbHabit 40MB/512MB, javaapp 162MB/384MB, mongo-backup 97MB/128MB, caddy 13MB/64MB, cloudflared 19MB/64MB |
| Other unrelated projects on this box | ~15 more containers (ObsidianOptimizer, a "communicator" stack, rabbitmq, redis, two more Postgres instances, ollama, nginx, react-ui, etc.) already running and sharing the same 7.1GiB/8-CPU host |

This is **not** a dedicated box for habitTracker — it's a shared personal
server running several other unrelated projects concurrently. The 3.0GiB
"available" figure is available to *all* of them, not reserved for this
feature.

Naive ceiling at 64–128MB / 0.25 cpu per ephemeral container:
- Memory-bound: 3.0GiB / 128MB ≈ 23 concurrent; / 64MB ≈ 47 concurrent
- CPU-bound: 8 cores / 0.25 cpu ≈ 32 concurrent before throttling kicks in

**Those ceiling numbers are not the real answer** — they assume zero safety
margin on a box that already has 1.9GB of swap in use from other workloads,
and they ignore that the other ~15 containers' usage moves independently of
this feature. Two things bring the real number down hard:

1. **The actual workload is not concurrent-by-nature.** This is per-user
   nightly-cron KPI proxies (~20 users max, per the CLAUDE.md ceiling), each
   running a handful of short scripts. Even a naive "run everyone's due
   capabilities at once" design is bounded by *user count*, not by host
   capacity — worst case is dozens of short-lived runs, not hundreds.
2. **The sidecar should self-limit regardless of host headroom.** A
   concurrency semaphore of ~4–8 simultaneous containers, with the rest
   queued, keeps this feature from ever being the thing that starves
   `javaapp`/Mongo or the unrelated projects on the box, independent of what
   the theoretical ceiling says.

**Conclusion:** the box comfortably absorbs this feature's actual expected
load (a handful of concurrent short-lived containers during a nightly batch),
but the sidecar's concurrency cap should be a fixed small number (4–8) chosen
for host-sharing safety, not derived from the theoretical 23–47 ceiling. Worth
re-checking `free -h`'s swap usage under real load once this ships — 1.9GB
swap already in use today, before this feature exists, is a pre-existing
signal worth a separate look, not something this spike explains.

## 3. Recommendation: sidecar-with-docker-socket

**Chosen: a small dedicated sidecar service holding the Docker socket, exactly
as the design doc proposed.** Stated plainly, not hedged:

- This works today, on this exact host, with zero new infrastructure — proven
  by the PoC below. Docker is already the deployment mechanism for the whole
  stack.
- The real cost is not hidden: **holding `/var/run/docker.sock` is
  root-equivalent access to the host.** A container that can talk to the
  Docker daemon can mount `/`, spin up a privileged container, and own the
  box. This is the actual privilege boundary the design doc flagged, and it
  doesn't go away — it gets contained. The mitigation is non-optional, not a
  nice-to-have: the sidecar must be an internal-only service (reachable only
  from `javaapp` on `habittracker_default`, no public port), with the `docker
  run` invocation **hardcoded** (fixed image allowlist, fixed flag set for
  caps/network/security-opts) — it must never accept free-form docker args
  from `javaapp`. `javaapp` sends "run capability X for user Y," not a docker
  command line.
- Alternatives considered and rejected for now, not because they're wrong in
  the abstract but because they're the wrong size for this project:
  - **gVisor/Kata (stronger container isolation):** not installed, adds real
    operational surface, and this is explicitly a staged-safety single-user
    personal prototype per the design doc's risk posture — not justified yet.
  - **Rootless Podman (avoids the root-socket problem structurally):** a
    genuinely better answer to the "socket = root" concern, but means running
    a second container runtime alongside the Docker install this whole stack
    already depends on, plus rootless's own cgroup-v2/subuid setup cost.
    Worth revisiting if the root-socket exposure ever becomes the actual
    blocker — not now.
  - **Kubernetes Jobs / Nomad:** overkill for a single box with a ~20-user
    ceiling.

## Evidence — PoC actually run

Script: `tools/spikes/m4_sandbox_poc.py`. Launches one `python:slim` container
named `m4-poc-<random>`, capped at 128MB / 0.25 cpu / no network / no
capabilities / read-only rootfs, runs a trivial Python payload, captures
stdout, and verifies the container is gone afterward.

Real terminal output from this session:

```
=== M4 sandbox PoC — container name: m4-poc-08506f22 ===

$ docker run --rm --name m4-poc-08506f22 --memory 128m --memory-swap 128m --cpus 0.25 --network none --pids-limit 64 --security-opt no-new-privileges --cap-drop ALL --read-only --tmpfs /tmp:size=16m python:slim python3 -c import sys, time; print('hello from inside the sandbox'); print('sum check:', sum(range(1000))); sys.exit(0)

--- run finished in 0.46s (timed_out=False) ---

exit code: 0
stdout:
hello from inside the sandbox
sum check: 499500

$ docker ps -a --filter name=^m4-poc-08506f22$ --format {{.Names}}
container 'm4-poc-08506f22' still present after run: False

=== PoC PASSED: container ran capped + isolated, and cleaned up after itself ===
```

Separately confirmed the timeout-kill path (not left in the committed script,
run ad hoc this session): a container running `time.sleep(30)` under a 3s
client-side timeout was killed via `docker kill` after the timeout fired, and
`docker ps -a` confirmed no trace remained.

Post-spike host check — no leaked containers from this work:
```
$ docker ps -a --filter "name=m4-poc-" --format "{{.Names}}\t{{.Status}}"
(empty)
```

## Answers to the design doc's open questions

- **Exact mechanism for `javaapp` to launch sandboxed containers without
  holding the socket directly:** sidecar service on `habittracker_default`,
  internal-only, hardcoded `docker run` parameters, `javaapp` calls it over
  the compose network rather than touching Docker at all. Confirmed workable
  on this host.
- **Concrete resource math:** box absorbs the feature's real expected load
  (a handful of concurrent short nightly-cron runs) comfortably; cap sidecar
  concurrency at 4–8 as a fixed safety limit rather than relying on the
  ~23–47 theoretical ceiling, since this host is shared with ~15 unrelated
  containers from other projects.
- **Podman/WASM revisit:** not needed now: flagged above as a future
  revisit if the root-socket exposure becomes the actual blocker, not before.

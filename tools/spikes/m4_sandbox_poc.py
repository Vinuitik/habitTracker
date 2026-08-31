#!/usr/bin/env python3
"""
M4 spike PoC — ephemeral, resource-capped, per-execution Docker container.

Simulates what a small "sandbox sidecar" service would do on behalf of javaapp:
launch a throwaway container from a generic runner image (python:slim), with
memory/cpu caps, no network, and a hard wall-clock timeout, run a trivial
untrusted-ish script inside it, capture stdout, and guarantee the container is
gone afterward (either via --rm or an explicit cleanup fallback).

THROWAWAY SCRIPT — not production code. Safety: only ever touches containers
this script itself starts, all named with the `m4-poc-` prefix. Does not
touch, inspect, or modify any other container on the host.

Usage: python3 m4_sandbox_poc.py
Requires: docker CLI on PATH, current user in the `docker` group (no sudo).
"""
import subprocess
import sys
import time
import uuid

IMAGE = "python:slim"
NAME = f"m4-poc-{uuid.uuid4().hex[:8]}"
MEM_LIMIT = "128m"
CPU_LIMIT = "0.25"
TIMEOUT_SECONDS = 15

# The "untrusted script" a future KPI proxy capability would run. Kept trivial
# and deterministic on purpose — this PoC is about the container mechanics,
# not the payload.
PAYLOAD = (
    "import sys, time; "
    "print('hello from inside the sandbox'); "
    "print('sum check:', sum(range(1000))); "
    "sys.exit(0)"
)


def run(cmd, **kwargs):
    print(f"$ {' '.join(cmd)}")
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def container_exists(name):
    r = run(["docker", "ps", "-a", "--filter", f"name=^{name}$", "--format", "{{.Names}}"])
    return name in r.stdout.split()


def main():
    print(f"=== M4 sandbox PoC — container name: {NAME} ===\n")

    cmd = [
        "docker", "run",
        "--rm",                       # auto-remove on exit — primary cleanup mechanism
        "--name", NAME,
        "--memory", MEM_LIMIT,
        "--memory-swap", MEM_LIMIT,   # disable swap headroom beyond the mem cap
        "--cpus", CPU_LIMIT,
        "--network", "none",          # no network at all for this PoC
        "--pids-limit", "64",
        "--security-opt", "no-new-privileges",
        "--cap-drop", "ALL",
        "--read-only",
        "--tmpfs", "/tmp:size=16m",
        IMAGE,
        "python3", "-c", PAYLOAD,
    ]

    start = time.time()
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=TIMEOUT_SECONDS
        )
        elapsed = time.time() - start
        timed_out = False
    except subprocess.TimeoutExpired as e:
        elapsed = time.time() - start
        timed_out = True
        result = None
        print(f"\n!! TIMEOUT after {TIMEOUT_SECONDS}s — killing container {NAME}")
        # `docker run` without -d blocks; on timeout the client process is killed
        # by subprocess, but the container itself may still be running server-side.
        # This is exactly why the sidecar must always follow up with an explicit
        # `docker kill`, not rely on --rm alone, when a timeout fires.
        kill = run(["docker", "kill", NAME])
        print(kill.stdout, kill.stderr)

    print(f"\n--- run finished in {elapsed:.2f}s (timed_out={timed_out}) ---\n")

    if result is not None:
        print("exit code:", result.returncode)
        print("stdout:")
        print(result.stdout)
        if result.stderr:
            print("stderr:")
            print(result.stderr)

    # Verify cleanup: container should NOT exist anymore (--rm, or our kill above).
    time.sleep(1)  # give the daemon a moment to finish removal
    exists = container_exists(NAME)
    print(f"container '{NAME}' still present after run: {exists}")
    if exists:
        print("!! cleanup failed — forcing removal now")
        run(["docker", "rm", "-f", NAME])
        exists_after_force = container_exists(NAME)
        print(f"container present after forced rm: {exists_after_force}")
        sys.exit(1)

    print("\n=== PoC PASSED: container ran capped + isolated, and cleaned up after itself ===")


if __name__ == "__main__":
    main()

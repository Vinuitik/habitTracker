#!/usr/bin/env python3
"""
Sandbox sidecar (KPI tracking agent, M7). Holds the Docker socket and runs untrusted
capability scripts in ephemeral, capped, network-less containers on behalf of javaapp.

API (internal docker network only, no published port):
  GET  /health
  POST /run  {"userId","capabilityId","language","sourceCode","timeoutSeconds"}
       -> 200 {"outcome": OK|NONZERO_EXIT|TIMEOUT|SIDECAR_ERROR, "exitCode", "stdout", "stderr"}
       -> 400 on invalid input

The docker command line is HARDCODED here (see build_docker_cmd). Callers send data, never
docker args. Stdlib only.
"""
import json
import os
import subprocess
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RUNNER_IMAGE = os.environ.get("SANDBOX_IMAGE", "python:3.12-slim")
MEM_LIMIT = os.environ.get("SANDBOX_MEM", "128m")
CPUS = os.environ.get("SANDBOX_CPUS", "0.25")
MAX_CONCURRENT = int(os.environ.get("SANDBOX_MAX_CONCURRENT", "4"))
QUEUE_WAIT_SECONDS = int(os.environ.get("SANDBOX_QUEUE_WAIT_SECONDS", "60"))
MAX_TIMEOUT_SECONDS = int(os.environ.get("SANDBOX_MAX_TIMEOUT_SECONDS", "60"))
PULL_TIMEOUT_SECONDS = int(os.environ.get("SANDBOX_PULL_TIMEOUT_SECONDS", "180"))
MAX_SOURCE_BYTES = 64 * 1024
MAX_OUTPUT_CHARS = 64 * 1024
LABEL = "kpi-sandbox=1"
PORT = int(os.environ.get("SANDBOX_PORT", "8090"))

_slots = threading.BoundedSemaphore(MAX_CONCURRENT)


def build_docker_cmd(name):
    """The one and only way containers get launched. Script arrives on stdin (`python3 -`)."""
    return [
        "docker", "run", "--rm", "-i",
        "--name", name,
        "--label", LABEL,
        "--memory", MEM_LIMIT, "--memory-swap", MEM_LIMIT,
        "--cpus", CPUS,
        "--network", "none",
        "--pids-limit", "64",
        "--security-opt", "no-new-privileges",
        "--cap-drop", "ALL",
        "--read-only", "--tmpfs", "/tmp:size=16m",
        "--user", "65534:65534",
        "-e", "PYTHONDONTWRITEBYTECODE=1",
        RUNNER_IMAGE,
        "python3", "-",
    ]


def _truncate(s):
    s = s or ""
    return s if len(s) <= MAX_OUTPUT_CHARS else s[:MAX_OUTPUT_CHARS] + "...[truncated]"


def _result(outcome, exit_code=None, stdout="", stderr=""):
    return {"outcome": outcome, "exitCode": exit_code,
            "stdout": _truncate(stdout), "stderr": _truncate(stderr)}


def validate(payload):
    """Returns (source, timeout) or raises ValueError."""
    if not isinstance(payload, dict):
        raise ValueError("body must be a JSON object")
    if payload.get("language") != "python":
        raise ValueError("only language=python is supported")
    src = payload.get("sourceCode")
    if not isinstance(src, str) or not src.strip():
        raise ValueError("sourceCode required")
    if len(src.encode()) > MAX_SOURCE_BYTES:
        raise ValueError("sourceCode too large")
    t = payload.get("timeoutSeconds", 30)
    if not isinstance(t, int) or isinstance(t, bool) or t < 1:
        raise ValueError("timeoutSeconds must be a positive integer")
    return src, min(t, MAX_TIMEOUT_SECONDS)


def ensure_image(run=subprocess.run):
    """Pull runner image if absent. Done BEFORE the script timeout starts so a cold pull
    doesn't get a good script killed. Raises on failure."""
    if run(["docker", "image", "inspect", RUNNER_IMAGE], capture_output=True).returncode == 0:
        return
    r = run(["docker", "pull", RUNNER_IMAGE], capture_output=True, text=True, timeout=PULL_TIMEOUT_SECONDS)
    if r.returncode != 0:
        raise RuntimeError("image pull failed: " + (r.stderr or "")[:200])


def run_capability(payload, run=subprocess.run):
    try:
        src, timeout = validate(payload)
    except ValueError as e:
        return 400, {"error": str(e)}

    if not _slots.acquire(timeout=QUEUE_WAIT_SECONDS):
        return 200, _result("SIDECAR_ERROR", stderr="sandbox busy: queue wait exceeded")
    try:
        try:
            ensure_image(run)
        except Exception as e:  # noqa: BLE001
            return 200, _result("SIDECAR_ERROR", stderr=str(e))

        name = "kpi-sbx-" + uuid.uuid4().hex[:12]
        try:
            r = run(build_docker_cmd(name), input=src, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            # --rm does not fire when the client is killed; kill the container explicitly.
            run(["docker", "rm", "-f", name], capture_output=True)
            return 200, _result("TIMEOUT", stderr="killed after %ds" % timeout)
        except Exception as e:  # noqa: BLE001
            run(["docker", "rm", "-f", name], capture_output=True)
            return 200, _result("SIDECAR_ERROR", stderr=str(e))

        # 125-127 = docker itself failed to start/exec the container, not the script.
        if r.returncode in (125, 126, 127):
            return 200, _result("SIDECAR_ERROR", r.returncode, r.stdout, r.stderr)
        # 137 = SIGKILL; with a memory cap that is almost always the OOM killer.
        outcome = "OK" if r.returncode == 0 else "NONZERO_EXIT"
        return 200, _result(outcome, r.returncode, r.stdout, r.stderr)
    finally:
        _slots.release()


def cleanup_orphans(run=subprocess.run):
    """Remove leftover sandbox containers (e.g. sidecar restarted mid-run)."""
    try:
        ids = run(["docker", "ps", "-aq", "--filter", "label=" + LABEL],
                  capture_output=True, text=True).stdout.split()
        if ids:
            run(["docker", "rm", "-f", *ids], capture_output=True)
    except Exception:  # noqa: BLE001
        pass


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "maxConcurrent": MAX_CONCURRENT})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/run":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 2 * MAX_SOURCE_BYTES:
                return self._send(400, {"error": "body too large"})
            payload = json.loads(self.rfile.read(n) or b"{}")
        except Exception:  # noqa: BLE001
            return self._send(400, {"error": "invalid JSON"})
        code, obj = run_capability(payload)
        self._send(code, obj)

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


def main():
    cleanup_orphans()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("sandbox sidecar listening on :%d (image=%s, max_concurrent=%d)" % (PORT, RUNNER_IMAGE, MAX_CONCURRENT), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

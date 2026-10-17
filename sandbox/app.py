#!/usr/bin/env python3
"""
Sandbox sidecar (KPI tracking agent, M7). Holds the Docker socket and runs untrusted
capability scripts in ephemeral, capped containers on behalf of javaapp.

API (internal docker network only, no published port):
  GET  /health
  POST /run  {"userId","capabilityId","language","sourceCode","timeoutSeconds",
              "allowedHosts": [hostnames]?, "secrets": {NAME: value}?}
       -> 200 {"outcome": OK|NONZERO_EXIT|TIMEOUT|SIDECAR_ERROR, "exitCode", "stdout", "stderr"}
       -> 400 on invalid input

The docker command line is HARDCODED here (see build_docker_cmd). Callers send data, never
docker args. Stdlib only.

Egress: no allowedHosts -> `--network none`. With allowedHosts the runner joins the `internal` docker
network that only the egress-proxy shares, with HTTP(S)_PROXY carrying an HMAC token that lists exactly
those hosts (see /egress-proxy). Secrets go to the runner as env vars via a 0600 --env-file deleted after
the run, and are redacted from the returned stdout/stderr.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import subprocess
import tempfile
import threading
import time
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

# Egress (see egress-proxy/). The network name is the real docker name (compose sets an explicit `name:`).
EGRESS_NETWORK = os.environ.get("SANDBOX_EGRESS_NETWORK", "habit_sandbox_egress")
EGRESS_PROXY_HOST = os.environ.get("EGRESS_PROXY_HOST", "egress-proxy")
EGRESS_PROXY_PORT = int(os.environ.get("EGRESS_PROXY_PORT", "3128"))
EGRESS_TOKEN_GRACE_SECONDS = 30
EGRESS_MIN_SECRET_LEN = 32
MAX_HOSTS = 10
MAX_SECRETS = 10
MAX_SECRET_VALUE_CHARS = 4096
MIN_SECRET_VALUE_CHARS = 4
HOST_RE = re.compile(r"^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
SECRET_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
# Names a secret must never shadow: proxy wiring, interpreter/loader behaviour.
RESERVED_ENV_PREFIXES = ("HTTP", "NO_PROXY", "ALL_PROXY", "PYTHON", "LD_", "SSL_", "REQUESTS_", "CURL_")
RESERVED_ENV_NAMES = {"PATH", "HOME", "USER", "TZ", "LANG", "TARGET_DATE"}

_slots = threading.BoundedSemaphore(MAX_CONCURRENT)


def get_egress_secret():
    s = os.environ.get("SANDBOX_EGRESS_SECRET", "")
    return s.encode() if len(s) >= EGRESS_MIN_SECRET_LEN else None


def _b64e(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def mint_egress_token(secret, hosts, ttl_seconds, run_id, now=None):
    """b64url(json{h,exp,rid}) + "." + b64url(HMAC-SHA256). Verified statelessly by egress-proxy."""
    exp = int((time.time() if now is None else now) + ttl_seconds)
    payload = _b64e(json.dumps({"h": sorted(hosts), "exp": exp, "rid": run_id}, separators=(",", ":")).encode())
    return payload + "." + _b64e(hmac.new(secret, payload.encode(), hashlib.sha256).digest())


def proxy_env(token):
    # user:password form is required: urllib only sends Proxy-Authorization when BOTH are present.
    url = "http://%s:x@%s:%d" % (token, EGRESS_PROXY_HOST, EGRESS_PROXY_PORT)
    return {k: url for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")}


def build_docker_cmd(name, network="none", env_file=None):
    """The one and only way containers get launched. Script arrives on stdin (`python3 -`).
    network is "none" or the fixed internal egress network; env_file holds secrets + proxy vars."""
    return [
        "docker", "run", "--rm", "-i",
        "--name", name,
        "--label", LABEL,
        "--memory", MEM_LIMIT, "--memory-swap", MEM_LIMIT,
        "--cpus", CPUS,
        "--network", network,
        "--pids-limit", "64",
        "--security-opt", "no-new-privileges",
        "--cap-drop", "ALL",
        "--read-only", "--tmpfs", "/tmp:size=16m",
        "--user", "65534:65534",
        "-e", "PYTHONDONTWRITEBYTECODE=1",
        *(["--env-file", env_file] if env_file else []),
        RUNNER_IMAGE,
        "python3", "-",
    ]


def redact(text, values):
    """Best-effort: replaces literal occurrences only (encoded/split output evades it)."""
    text = text or ""
    for v in sorted({v for v in values if v}, key=len, reverse=True):
        text = text.replace(v, "[REDACTED]")
    return text


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


def validate_extras(payload):
    """Returns (hosts list, secrets dict) or raises ValueError. Defence in depth: javaapp validates too."""
    hosts = payload.get("allowedHosts") or []
    if not isinstance(hosts, list) or len(hosts) > MAX_HOSTS:
        raise ValueError("allowedHosts must be a list of at most %d hostnames" % MAX_HOSTS)
    for h in hosts:
        if not isinstance(h, str) or not HOST_RE.match(h):
            raise ValueError("invalid host in allowedHosts")
    secrets = payload.get("secrets") or {}
    if not isinstance(secrets, dict) or len(secrets) > MAX_SECRETS:
        raise ValueError("secrets must be an object with at most %d entries" % MAX_SECRETS)
    for k, v in secrets.items():
        if not isinstance(k, str) or not SECRET_NAME_RE.match(k) or k in RESERVED_ENV_NAMES \
                or k.upper().startswith(RESERVED_ENV_PREFIXES):
            raise ValueError("invalid or reserved secret name")
        if not isinstance(v, str) or not (MIN_SECRET_VALUE_CHARS <= len(v) <= MAX_SECRET_VALUE_CHARS) \
                or any(c in v for c in "\r\n\x00"):
            raise ValueError("invalid secret value for " + k)
    return sorted(set(hosts)), secrets


def write_env_file(env):
    """0600 temp file (mkstemp default) holding NAME=value lines for `docker run --env-file`."""
    fd, path = tempfile.mkstemp(prefix="kpi-env-")
    try:
        with os.fdopen(fd, "w") as f:
            for k, v in env.items():
                f.write("%s=%s\n" % (k, v))
    except Exception:
        os.unlink(path)
        raise
    return path


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
        hosts, secrets = validate_extras(payload)
    except ValueError as e:
        return 400, {"error": str(e)}

    egress_secret = get_egress_secret()
    if hosts and egress_secret is None:  # fail closed: never silently run without the allowlist
        return 200, _result("SIDECAR_ERROR", stderr="egress not configured: SANDBOX_EGRESS_SECRET unset or too short")

    if not _slots.acquire(timeout=QUEUE_WAIT_SECONDS):
        return 200, _result("SIDECAR_ERROR", stderr="sandbox busy: queue wait exceeded")
    env_path = None
    try:
        try:
            ensure_image(run)
        except Exception as e:  # noqa: BLE001
            return 200, _result("SIDECAR_ERROR", stderr=str(e))

        run_id = uuid.uuid4().hex[:12]
        name = "kpi-sbx-" + run_id
        env = dict(secrets)
        network = "none"
        sensitive = list(secrets.values())
        if hosts:
            network = EGRESS_NETWORK
            token = mint_egress_token(egress_secret, hosts, timeout + EGRESS_TOKEN_GRACE_SECONDS, run_id)
            env.update(proxy_env(token))
            sensitive.append(token)
        try:
            if env:
                env_path = write_env_file(env)
            r = run(build_docker_cmd(name, network, env_path), input=src, capture_output=True, text=True,
                    timeout=timeout)
        except subprocess.TimeoutExpired:
            # --rm does not fire when the client is killed; kill the container explicitly.
            run(["docker", "rm", "-f", name], capture_output=True)
            return 200, _result("TIMEOUT", stderr="killed after %ds" % timeout)
        except Exception as e:  # noqa: BLE001
            run(["docker", "rm", "-f", name], capture_output=True)
            return 200, _result("SIDECAR_ERROR", stderr=redact(str(e), sensitive))

        out, err = redact(r.stdout, sensitive), redact(r.stderr, sensitive)
        # 125-127 = docker itself failed to start/exec the container, not the script.
        if r.returncode in (125, 126, 127):
            return 200, _result("SIDECAR_ERROR", r.returncode, out, err)
        # 137 = SIGKILL; with a memory cap that is almost always the OOM killer.
        outcome = "OK" if r.returncode == 0 else "NONZERO_EXIT"
        return 200, _result(outcome, r.returncode, out, err)
    finally:
        if env_path:
            try:
                os.unlink(env_path)
            except OSError:
                pass
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
            self._send(200, {"status": "ok", "maxConcurrent": MAX_CONCURRENT,
                             "egressConfigured": get_egress_secret() is not None})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/run":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 2 * MAX_SOURCE_BYTES + 128 * 1024:
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
    print("sandbox sidecar listening on :%d (image=%s, max_concurrent=%d, egress=%s)"
          % (PORT, RUNNER_IMAGE, MAX_CONCURRENT, "on" if get_egress_secret() else "OFF (SANDBOX_EGRESS_SECRET unset)"),
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

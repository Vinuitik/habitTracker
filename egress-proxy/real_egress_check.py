#!/usr/bin/env python3
"""Manual check against the REAL docker daemon (not part of unittest discovery).

Starts a THROWAWAY proxy container + internal network with unique test names (kpi-egtest-<id>-*) and
drives sandbox/app.py's real run_capability() against them. Never touches compose services, never
publishes ports, never runs `docker compose`. Cleans up everything it created (also on failure).

Usage: SANDBOX_IMAGE=python:slim python3 real_egress_check.py
Needs internet (api.github.com). Proves: allowed host reachable via proxy, disallowed host denied,
direct egress fails, private/loopback targets denied (IP literal + name resolving to loopback),
secrets injected + redacted, no leftovers.
"""
import importlib.util
import os
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
RUN_ID = uuid.uuid4().hex[:8]
NET = "kpi-egtest-%s-net" % RUN_ID
PROXY = "kpi-egtest-%s-proxy" % RUN_ID
PORT = "3199"
SECRET = "real-check-" + uuid.uuid4().hex + uuid.uuid4().hex
IMAGE = os.environ.get("SANDBOX_IMAGE", "python:slim")

# Configure the sidecar module for the throwaway stack BEFORE importing it.
os.environ.update({
    "SANDBOX_IMAGE": IMAGE,
    "SANDBOX_EGRESS_SECRET": SECRET,
    "SANDBOX_EGRESS_NETWORK": NET,
    "EGRESS_PROXY_HOST": PROXY,
    "EGRESS_PROXY_PORT": PORT,
})
spec = importlib.util.spec_from_file_location("sandbox_app", os.path.join(HERE, "..", "sandbox", "app.py"))
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)


def sh(*cmd, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError("%s -> %s" % (" ".join(cmd), r.stderr.strip()))
    return r.stdout.strip()


def runners():
    return set(sh("docker", "ps", "-aq", "--filter", "label=" + app.LABEL, check=False).split())


def go(label, src, hosts=None, secrets=None, t=25):
    p = {"language": "python", "sourceCode": src, "timeoutSeconds": t}
    if hosts:
        p["allowedHosts"] = hosts
    if secrets:
        p["secrets"] = secrets
    code, res = app.run_capability(p)
    print("%-52s -> %s exit=%s out=%r err=%r" % (label, res.get("outcome"), res.get("exitCode"),
                                                  res.get("stdout", "").strip()[-100:],
                                                  res.get("stderr", "").strip()[-100:]))
    return res


FETCH = """
import urllib.request, urllib.error
try:
    r = urllib.request.urlopen(%r, timeout=15)
    print("STATUS", r.status)
except urllib.error.HTTPError as e:
    print("HTTPERR", e.code)
except Exception as e:
    print("ERR", type(e).__name__, str(e)[:80])
"""


def main():
    before = runners()
    try:
        sh("docker", "image", "inspect", IMAGE)
        sh("docker", "network", "create", "--internal", NET)
        sh("docker", "run", "-d", "--name", PROXY, "--memory", "64m", "--read-only", "--user", "65534:65534",
           "-e", "SANDBOX_EGRESS_SECRET=" + SECRET, "-e", "EGRESS_PROXY_PORT=" + PORT,
           "-v", os.path.join(HERE, "proxy.py") + ":/app/proxy.py:ro", IMAGE, "python3", "-u", "/app/proxy.py")
        sh("docker", "network", "connect", NET, PROXY)
        time.sleep(1.5)
        bridge_ip = sh("docker", "inspect", "-f", "{{.NetworkSettings.Networks.bridge.IPAddress}}", PROXY)
        print("proxy up: bridge ip", bridge_ip, "| internal net", NET)

        r = go("allowed host via proxy (https)", FETCH % "https://api.github.com/", ["api.github.com"])
        assert "STATUS 200" in r["stdout"], r

        r = go("disallowed host denied (https)", FETCH % "https://example.com/", ["api.github.com"])
        assert "STATUS" not in r["stdout"] and "403" in r["stdout"] + r["stderr"], r

        r = go("disallowed host denied (plain http)", FETCH % "http://example.com/", ["api.github.com"])
        assert "STATUS" not in r["stdout"], r

        r = go("private IP literal denied (172.17.0.1)", FETCH % "http://172.17.0.1/", ["api.github.com"])
        assert "STATUS" not in r["stdout"] and "403" in r["stdout"], r

        r = go("allowed NAME resolving to loopback denied", FETCH % "https://127.0.0.1.nip.io/", ["127.0.0.1.nip.io"])
        assert "STATUS" not in r["stdout"] and "403" in r["stdout"], r
        r = go("allowed NAME resolving to 10.x denied", FETCH % "http://10.0.0.5.nip.io/", ["10.0.0.5.nip.io"])
        assert "STATUS" not in r["stdout"] and "403" in r["stdout"], r

        direct = ("import socket,sys\n"
                  "for h,p in [('1.1.1.1',443),('%s',%s),('%s',80)]:\n"
                  "    try:\n        socket.create_connection((h,p),3); print('REACHED',h)\n"
                  "    except Exception as e:\n        print('BLOCKED',h,type(e).__name__)\n") % (
                      bridge_ip, PORT, "172.17.0.1")
        r = go("direct egress (no proxy) + proxy's bridge IP", direct, ["api.github.com"])
        assert "REACHED" not in r["stdout"] and r["stdout"].count("BLOCKED") == 3, r

        r = go("no hosts -> network none, no proxy env",
               "import os,socket\nprint('PROXY' if os.environ.get('HTTPS_PROXY') else 'NOPROXY')\n"
               "try:\n    socket.create_connection(('1.1.1.1',443),3); print('REACHED')\n"
               "except Exception: print('BLOCKED')\n")
        assert r["stdout"].split() == ["NOPROXY", "BLOCKED"], r

        secret_val = "sk-live-" + uuid.uuid4().hex
        r = go("secret injected as env + redacted from output",
               "import os\nv=os.environ['MY_TOKEN']\nprint('len', len(v))\nprint(v)\nprint(os.environ['HTTPS_PROXY'])\n",
               ["api.github.com"], {"MY_TOKEN": secret_val})
        assert "len %d" % len(secret_val) in r["stdout"] and secret_val not in r["stdout"], r
        assert "[REDACTED]" in r["stdout"] and "eyJ" not in r["stdout"], r

        logs = sh("docker", "logs", PROXY, check=False)
        err = subprocess.run(["docker", "logs", PROXY], capture_output=True, text=True).stderr
        both = logs + err
        print("--- proxy log ---\n" + both.strip() + "\n-----------------")
        assert "ALLOW CONNECT api.github.com:443" in both
        assert "non-public" in both
        assert "eyJ" not in both, "token must not appear in proxy logs"
    finally:
        subprocess.run(["docker", "rm", "-f", PROXY], capture_output=True)
        subprocess.run(["docker", "network", "rm", NET], capture_output=True)
    left_c = sh("docker", "ps", "-aq", "--filter", "name=kpi-egtest-" + RUN_ID, check=False)
    left_n = sh("docker", "network", "ls", "-q", "--filter", "name=kpi-egtest-" + RUN_ID, check=False)
    new_runners = runners() - before
    leftovers_env = [f for f in os.listdir("/tmp") if f.startswith("kpi-env-")]
    print("leftover containers:", left_c or "none", "| networks:", left_n or "none",
          "| runners:", new_runners or "none", "| env files:", leftovers_env or "none")
    assert not left_c and not left_n and not new_runners
    print("REAL EGRESS OK")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAILED:", e)
        sys.exit(1)

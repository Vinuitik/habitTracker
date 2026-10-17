#!/usr/bin/env python3
"""Manual smoke check against the REAL docker daemon (not part of unittest discovery).
Usage: SANDBOX_IMAGE=python:slim python3 real_run_check.py
"""
import subprocess
import time

import app


def go(src, t=10):
    start = time.time()
    code, res = app.run_capability({"language": "python", "sourceCode": src, "timeoutSeconds": t})
    print("%-45s -> %s %s exit=%s stdout=%r stderr=%r (%.1fs)" % (
        src[:45].replace("\n", ";"), code, res["outcome"], res["exitCode"], res["stdout"].strip(),
        res["stderr"].strip()[-80:], time.time() - start))
    return res


assert go("print('noise')\nprint(41+1)")["stdout"].split()[-1] == "42"
assert go("import sys; sys.exit(3)")["outcome"] == "NONZERO_EXIT"
assert go("import socket; socket.create_connection(('1.1.1.1',53),2)")["outcome"] == "NONZERO_EXIT"  # no network
assert go("open('/x','w')")["outcome"] == "NONZERO_EXIT"  # read-only fs
assert go("import time; time.sleep(60)", 3)["outcome"] == "TIMEOUT"
left = subprocess.run(["docker", "ps", "-aq", "--filter", "label=" + app.LABEL],
                      capture_output=True, text=True).stdout.split()
print("leftover sandbox containers:", left)
assert not left
print("REAL RUN OK")

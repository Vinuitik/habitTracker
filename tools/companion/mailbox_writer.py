#!/usr/bin/env python3
"""
HabitTracker companion — M9 scoped mailbox-write wrapper.

Push capabilities (coding_hours_capability.py, and any future ones) import ONLY this module's
write_kpi_value() to get a value into the user's Drive mailbox. They must never import pair.py
directly: pair.py owns config loading, which includes this device's raw Drive refresh token
(read from CONFIG_FILE on disk), plus the encryption/upload machinery. This module is the sole
narrow seam between "capability code that produces a number" and "code that can spend the raw
OAuth token" — a capability that only imports this module never sees CONFIG_FILE, the refresh
token string, or pair.load_config() itself.

Design doc reference: docs/designs/kpi-tracking-agent.md, "Client-side execution (push
proxies)" — "Minimal wrapper enforces a timeout and injects only the declared secret(s) ...
keeping the capability from touching the companion's own Drive refresh token directly."

Risk-posture note (see design doc "Risk posture" section): this is an IMPORT-LEVEL boundary
within the same OS process, not real process/OS-level isolation — there is no subprocess
sandbox and no separate credential-holding daemon here. Nothing at the Python level stops a
capability script from `import pair` directly if it chose to; this module only makes the
*sanctioned* path narrow, auditable, and structurally testable (see
test_mailbox_writer.py::CapabilityAccessBoundaryTest). Tightening this to real process
isolation (e.g. the capability runs as a subprocess with only a scoped, time-limited token
injected via env var, and this module lives in the parent process only) is on the design doc's
staged safety roadmap, not required for this milestone (single-user prototype, explicit risk
acceptance).
"""
import concurrent.futures
import json
import time
import uuid

import pair

DEFAULT_WRITE_TIMEOUT_SECONDS = 20


def write_kpi_value(kpi_name, date, value, timeout_seconds=DEFAULT_WRITE_TIMEOUT_SECONDS):
    """The one function a push capability may call to land a KPI value in the mailbox.

    Internally: loads this device's local pairing config, refreshes a Drive access token,
    encrypts, and uploads — all via pair.py's existing (not duplicated) machinery. Enforces
    timeout_seconds as a hard wall-clock cap on the whole operation so a hung network call can
    never wedge the calling capability indefinitely.

    Args:
        kpi_name: exact KPI name as configured in the web app (must already exist for this
            user — see pair.py's write-test-kpi docs for the same caveat).
        date: "YYYY-MM-DD" string.
        value: numeric value to record.
        timeout_seconds: hard cap on the whole load-config -> refresh-token -> encrypt ->
            upload sequence.

    Returns:
        The Drive file id of the uploaded mailbox request.

    Raises:
        RuntimeError: this device isn't paired yet, or the underlying write fails.
        TimeoutError: the write didn't complete within timeout_seconds. Note: the underlying
            worker thread is NOT forcibly killed when this fires (Python has no safe way to do
            that) — it keeps running in the background and its result/exception is discarded.
            For a single mailbox POST this is an acceptable risk-posture tradeoff for this
            milestone; a stuck thread does not block the caller.
    """
    def _do_write():
        config = pair.load_config()
        if not config:
            raise RuntimeError("Not paired yet — run `python pair.py pair` first.")
        access_token = pair.refresh_access_token(
            config["clientId"], config["clientSecret"], config["refreshToken"])
        payload = {"kpiName": kpi_name, "date": date, "value": value}
        batch = pair.build_mailbox_batch(config["deviceId"], "kpi-value", payload)
        wire = pair.encrypt_for_mailbox(config["encryptionKey"], json.dumps(batch).encode("utf-8"))
        filename = f"{config['deviceId']}-{int(time.time() * 1000)}-{uuid.uuid4()}.enc"
        return pair.upload_mailbox_file(access_token, config["mailboxFolderId"], wire, filename)

    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(_do_write)
        try:
            return future.result(timeout=timeout_seconds)
        except concurrent.futures.TimeoutError:
            raise TimeoutError(f"mailbox write did not complete within {timeout_seconds}s")

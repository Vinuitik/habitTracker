import subprocess
import unittest
from types import SimpleNamespace
from unittest import mock

import app


def fake_run_factory(script_result=None, timeout=False, image_present=True, calls=None):
    """Fake subprocess.run: records calls, simulates docker behaviours."""
    calls = calls if calls is not None else []

    def fake(cmd, **kw):
        calls.append(cmd)
        if cmd[:3] == ["docker", "image", "inspect"]:
            return SimpleNamespace(returncode=0 if image_present else 1, stdout="", stderr="")
        if cmd[:2] == ["docker", "pull"]:
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        if cmd[:2] == ["docker", "rm"]:
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        if cmd[:2] == ["docker", "run"]:
            if timeout:
                raise subprocess.TimeoutExpired(cmd, kw.get("timeout"))
            return script_result
        raise AssertionError("unexpected command " + repr(cmd))

    return fake, calls


def payload(**over):
    p = {"language": "python", "sourceCode": "print(1)", "timeoutSeconds": 5}
    p.update(over)
    return p


class ValidateTest(unittest.TestCase):
    def test_rejects_bad_input(self):
        for bad in [None, {}, payload(language="bash"), payload(sourceCode=""),
                    payload(sourceCode="x" * (app.MAX_SOURCE_BYTES + 1)), payload(timeoutSeconds=0),
                    payload(timeoutSeconds="5")]:
            with self.assertRaises(ValueError):
                app.validate(bad)

    def test_timeout_is_clamped(self):
        self.assertEqual(app.validate(payload(timeoutSeconds=9999))[1], app.MAX_TIMEOUT_SECONDS)


class DockerCmdTest(unittest.TestCase):
    def test_hardcoded_hardening_flags(self):
        cmd = app.build_docker_cmd("n")
        joined = " ".join(cmd)
        for needle in ["--network none", "--memory " + app.MEM_LIMIT, "--cap-drop ALL", "--read-only",
                       "no-new-privileges", "--pids-limit 64", "--cpus " + app.CPUS]:
            self.assertIn(needle, joined)
        self.assertNotIn("docker.sock", joined)
        self.assertEqual(cmd[-2:], ["python3", "-"])


class RunCapabilityTest(unittest.TestCase):
    def test_ok(self):
        fake, calls = fake_run_factory(SimpleNamespace(returncode=0, stdout="42\n", stderr=""))
        code, res = app.run_capability(payload(), run=fake)
        self.assertEqual((code, res["outcome"], res["stdout"]), (200, "OK", "42\n"))

    def test_nonzero_exit(self):
        fake, _ = fake_run_factory(SimpleNamespace(returncode=1, stdout="", stderr="boom"))
        _, res = app.run_capability(payload(), run=fake)
        self.assertEqual((res["outcome"], res["exitCode"], res["stderr"]), ("NONZERO_EXIT", 1, "boom"))

    def test_docker_failure_is_sidecar_error(self):
        fake, _ = fake_run_factory(SimpleNamespace(returncode=125, stdout="", stderr="daemon error"))
        _, res = app.run_capability(payload(), run=fake)
        self.assertEqual(res["outcome"], "SIDECAR_ERROR")

    def test_timeout_kills_container(self):
        fake, calls = fake_run_factory(timeout=True)
        _, res = app.run_capability(payload(), run=fake)
        self.assertEqual(res["outcome"], "TIMEOUT")
        self.assertTrue(any(c[:3] == ["docker", "rm", "-f"] for c in calls), "container must be force-removed")

    def test_pulls_missing_image(self):
        fake, calls = fake_run_factory(SimpleNamespace(returncode=0, stdout="1", stderr=""), image_present=False)
        app.run_capability(payload(), run=fake)
        self.assertTrue(any(c[:2] == ["docker", "pull"] for c in calls))

    def test_invalid_payload_is_400(self):
        code, _ = app.run_capability(payload(language="ruby"), run=mock.Mock())
        self.assertEqual(code, 400)

    def test_busy_when_queue_wait_exceeded(self):
        with mock.patch.object(app, "_slots", mock.Mock(acquire=mock.Mock(return_value=False))):
            _, res = app.run_capability(payload(), run=mock.Mock())
        self.assertEqual(res["outcome"], "SIDECAR_ERROR")

    def test_slot_released_after_failure(self):
        fake, _ = fake_run_factory(timeout=True)
        for _ in range(app.MAX_CONCURRENT + 2):  # would deadlock/busy if slots leaked
            _, res = app.run_capability(payload(), run=fake)
            self.assertEqual(res["outcome"], "TIMEOUT")


if __name__ == "__main__":
    unittest.main()

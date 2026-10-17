import base64
import json
import os
import pathlib
import stat
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




SECRET = "e" * 40


def capturing_run(result, seen):
    """fake docker whose `run` captures the env-file contents/perms at call time."""
    def fake(cmd, **kw):
        if cmd[:3] == ["docker", "image", "inspect"] or cmd[:2] in (["docker", "rm"], ["docker", "pull"]):
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        if cmd[:2] == ["docker", "run"]:
            seen["cmd"] = cmd
            if "--env-file" in cmd:
                p = cmd[cmd.index("--env-file") + 1]
                seen["path"] = p
                seen["mode"] = stat.S_IMODE(os.stat(p).st_mode)
                seen["content"] = pathlib.Path(p).read_text()
            return result
        raise AssertionError(cmd)
    return fake


class EgressTokenTest(unittest.TestCase):
    def test_token_shape_and_signature(self):
        t = app.mint_egress_token(SECRET.encode(), ["b.com", "a.com"], 60, "rid", now=1000)
        payload, sig = t.split(".")
        data = json.loads(base64.urlsafe_b64decode(payload + "=="))
        self.assertEqual(data, {"h": ["a.com", "b.com"], "exp": 1060, "rid": "rid"})
        import hashlib, hmac
        exp = base64.urlsafe_b64encode(hmac.new(SECRET.encode(), payload.encode(), hashlib.sha256).digest()).decode().rstrip("=")
        self.assertEqual(sig, exp)

    def test_proxy_env_has_userinfo_with_password_for_urllib(self):
        env = app.proxy_env("TOK")
        self.assertEqual(env["HTTPS_PROXY"], "http://TOK:x@%s:%d" % (app.EGRESS_PROXY_HOST, app.EGRESS_PROXY_PORT))
        self.assertEqual(env["https_proxy"], env["HTTP_PROXY"])


class ExtrasValidationTest(unittest.TestCase):
    def test_accepts_good(self):
        self.assertEqual(app.validate_extras(payload(allowedHosts=["b.com", "a.com", "a.com"],
                                                     secrets={"API_KEY": "abcd1234"})),
                         (["a.com", "b.com"], {"API_KEY": "abcd1234"}))

    def test_rejects_bad_hosts(self):
        for h in ["localhost", "10.0.0.1", "a.com:80", "*.a.com", "javaapp", "A.com", "x.123", "a b.com", ""]:
            with self.assertRaises(ValueError, msg=h):
                app.validate_extras(payload(allowedHosts=[h]))

    def test_rejects_bad_secrets(self):
        for s in [{"1BAD": "abcd1234"}, {"HTTPS_PROXY": "abcd1234"}, {"PATH": "abcd1234"}, {"PYTHONPATH": "abcd"},
                  {"OK": "line\nbreak"}, {"OK": "abc"}, {"OK": "x" * 5000}, {"OK": 5}, {"A=B": "abcd1234"}]:
            with self.assertRaises(ValueError, msg=str(s)):
                app.validate_extras(payload(secrets=s))


class NetworkSelectionTest(unittest.TestCase):
    def setUp(self):
        os.environ["SANDBOX_EGRESS_SECRET"] = SECRET

    def tearDown(self):
        os.environ.pop("SANDBOX_EGRESS_SECRET", None)

    def run_it(self, **over):
        seen = {}
        res = SimpleNamespace(returncode=0, stdout="42\n", stderr="")
        code, out = app.run_capability(payload(**over), run=capturing_run(res, seen))
        return seen, out

    def test_no_hosts_means_network_none_and_no_env_file(self):
        seen, out = self.run_it()
        self.assertEqual(out["outcome"], "OK")
        self.assertEqual(seen["cmd"][seen["cmd"].index("--network") + 1], "none")
        self.assertNotIn("--env-file", seen["cmd"])

    def test_hosts_mean_internal_network_and_proxy_env_in_file_not_argv(self):
        seen, out = self.run_it(allowedHosts=["api.github.com"])
        self.assertEqual(seen["cmd"][seen["cmd"].index("--network") + 1], app.EGRESS_NETWORK)
        self.assertIn("HTTPS_PROXY=http://", seen["content"])
        self.assertFalse(any("@egress-proxy" in a for a in seen["cmd"]), "token must not be on the command line")
        self.assertNotIn("ports", " ".join(seen["cmd"]))

    def test_secrets_only_keep_network_none(self):
        seen, _ = self.run_it(secrets={"API_KEY": "s3cr3t-value"})
        self.assertEqual(seen["cmd"][seen["cmd"].index("--network") + 1], "none")
        self.assertIn("API_KEY=s3cr3t-value\n", seen["content"])
        self.assertFalse(any("s3cr3t-value" in a for a in seen["cmd"]))

    def test_env_file_is_0600_and_deleted_after_run(self):
        seen, _ = self.run_it(secrets={"API_KEY": "s3cr3t-value"}, allowedHosts=["a.com"])
        self.assertEqual(seen["mode"], 0o600)
        self.assertFalse(os.path.exists(seen["path"]))

    def test_env_file_deleted_after_timeout(self):
        seen = {}

        def fake(cmd, **kw):
            if cmd[:2] == ["docker", "run"]:
                seen["path"] = cmd[cmd.index("--env-file") + 1]
                raise subprocess.TimeoutExpired(cmd, 1)
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        _, out = app.run_capability(payload(secrets={"API_KEY": "s3cr3t-value"}), run=fake)
        self.assertEqual(out["outcome"], "TIMEOUT")
        self.assertFalse(os.path.exists(seen["path"]))

    def test_fails_closed_without_egress_secret(self):
        os.environ.pop("SANDBOX_EGRESS_SECRET")
        run = mock.Mock()
        _, out = app.run_capability(payload(allowedHosts=["a.com"]), run=run)
        self.assertEqual(out["outcome"], "SIDECAR_ERROR")
        run.assert_not_called()
        os.environ["SANDBOX_EGRESS_SECRET"] = "too-short"
        _, out = app.run_capability(payload(allowedHosts=["a.com"]), run=run)
        self.assertEqual(out["outcome"], "SIDECAR_ERROR")

    def test_no_hosts_still_runs_without_egress_secret(self):
        os.environ.pop("SANDBOX_EGRESS_SECRET")
        _, out = self.run_it()
        self.assertEqual(out["outcome"], "OK")

    def test_invalid_extras_are_400(self):
        code, _ = app.run_capability(payload(allowedHosts=["127.0.0.1"]), run=mock.Mock())
        self.assertEqual(code, 400)


class RedactionTest(unittest.TestCase):
    def test_secret_values_and_token_redacted_in_stdout_and_stderr(self):
        os.environ["SANDBOX_EGRESS_SECRET"] = SECRET
        try:
            seen = {}
            res = SimpleNamespace(returncode=1, stdout="key=s3cr3t-value\n7", stderr="bad s3cr3t-value and longer-secret-xyz")
            _, out = app.run_capability(
                payload(secrets={"A_KEY": "s3cr3t-value", "B_KEY": "longer-secret-xyz"}), run=capturing_run(res, seen))
        finally:
            os.environ.pop("SANDBOX_EGRESS_SECRET")
        self.assertNotIn("s3cr3t-value", out["stdout"] + out["stderr"])
        self.assertNotIn("longer-secret-xyz", out["stderr"])
        self.assertIn("[REDACTED]", out["stdout"])
        self.assertTrue(out["stdout"].endswith("7"))

    def test_longest_first(self):
        self.assertEqual(app.redact("abcdef abc", ["abc", "abcdef"]), "[REDACTED] [REDACTED]")


if __name__ == "__main__":
    unittest.main()

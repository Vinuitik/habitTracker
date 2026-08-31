"""
Tests for M6's capability-deploy channel (server -> device), the reverse direction of the M5
mailbox mechanism covered by test_pair.py.

Covers:
  1. evaluate_capability_version() — the pure version-comparison decision function: skip
     already-current, accept strictly newer (including first-seen), reject older, reject
     malformed payloads.
  2. poll_capability_deploy() end to end against a LOCAL mock standing in for the Drive v3 REST
     API (list + download) — no real Google credentials exist in this environment, same
     constraint as test_pair.py. This test plays the SERVER side by hand: it builds
     {capabilityId, version, sourceCode} payloads and encrypts them exactly the way
     CapabilityDeployService/VaultEncryptionService do ([12B IV][ciphertext+16B GCM tag],
     AES-256-GCM) and seeds them into the mock folder — mirroring what
     CapabilityDeployService.deployCapability() produces on the real server — then exercises the
     companion-side polling function against that mock, proving it correctly updates its local
     cache.

Run with:  python3 -m unittest tools/companion/test_capability_deploy.py -v
"""
import base64
import http.server
import json
import os
import threading
import unittest
import urllib.parse

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import pair


def _start_server(handler_cls):
    server = http.server.HTTPServer(("127.0.0.1", 0), handler_cls)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def _encrypt_like_vault_encryption_service(key_b64, payload_dict):
    """Produces the exact wire format VaultEncryptionService.encrypt() / CapabilityDeployService
    write — used here to seed the mock Drive folder as if the real server had deployed it."""
    key = base64.b64decode(key_b64)
    iv = os.urandom(12)
    plaintext = json.dumps(payload_dict).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(iv, plaintext, None)
    return iv + ciphertext


class _MockCapabilityDeployDriveServer(http.server.BaseHTTPRequestHandler):
    """Stands in for the Drive v3 REST API's list (`GET /files?q=...`) and download
    (`GET /files/{id}?alt=media`) endpoints, scoped to one "_capability_deploy" folder's worth of
    pre-seeded encrypted files. FILES is set per-test via the class before the server is used."""

    FILES = {}  # file_id -> raw wire bytes

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/files":
            body = json.dumps({
                "files": [{"id": fid, "name": fid} for fid in self.__class__.FILES]
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if parsed.path.startswith("/files/"):
            file_id = parsed.path[len("/files/"):]
            content = self.__class__.FILES.get(file_id)
            if content is None:
                self.send_response(404)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, *args):
        pass


class EvaluateCapabilityVersionTest(unittest.TestCase):
    """Pure function, no I/O — the "version-comparison logic" unit test target."""

    def test_first_time_seen_is_accepted(self):
        decision, _ = pair.evaluate_capability_version(
            None, {"capabilityId": "cap-a", "version": 1, "sourceCode": "x"})
        self.assertEqual(decision, "accept")

    def test_strictly_newer_version_is_accepted(self):
        decision, _ = pair.evaluate_capability_version(
            3, {"capabilityId": "cap-a", "version": 4, "sourceCode": "x"})
        self.assertEqual(decision, "accept")

    def test_same_version_is_skipped_not_an_error(self):
        decision, _ = pair.evaluate_capability_version(
            3, {"capabilityId": "cap-a", "version": 3, "sourceCode": "x"})
        self.assertEqual(decision, "skip")

    def test_older_version_is_rejected_no_downgrade(self):
        decision, _ = pair.evaluate_capability_version(
            5, {"capabilityId": "cap-a", "version": 2, "sourceCode": "x"})
        self.assertEqual(decision, "reject")

    def test_missing_capability_id_is_rejected(self):
        decision, _ = pair.evaluate_capability_version(
            None, {"version": 1, "sourceCode": "x"})
        self.assertEqual(decision, "reject")

    def test_missing_source_code_is_rejected(self):
        decision, _ = pair.evaluate_capability_version(
            None, {"capabilityId": "cap-a", "version": 1})
        self.assertEqual(decision, "reject")

    def test_non_integer_version_is_rejected(self):
        decision, _ = pair.evaluate_capability_version(
            None, {"capabilityId": "cap-a", "version": "1", "sourceCode": "x"})
        self.assertEqual(decision, "reject")

    def test_boolean_version_is_rejected(self):
        # bool is a subclass of int in Python; must not slip through as a valid version.
        decision, _ = pair.evaluate_capability_version(
            None, {"capabilityId": "cap-a", "version": True, "sourceCode": "x"})
        self.assertEqual(decision, "reject")

    def test_zero_or_negative_version_is_rejected(self):
        decision, _ = pair.evaluate_capability_version(
            None, {"capabilityId": "cap-a", "version": 0, "sourceCode": "x"})
        self.assertEqual(decision, "reject")

    def test_non_dict_payload_is_rejected(self):
        decision, _ = pair.evaluate_capability_version(None, "not-a-dict")
        self.assertEqual(decision, "reject")


class PollCapabilityDeployIntegrationTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.server, cls.thread = _start_server(_MockCapabilityDeployDriveServer)
        cls._orig_files_url = pair.DRIVE_FILES_URL
        pair.DRIVE_FILES_URL = f"http://127.0.0.1:{cls.server.server_address[1]}/files"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        pair.DRIVE_FILES_URL = cls._orig_files_url

    def setUp(self):
        self.key_b64 = base64.b64encode(os.urandom(32)).decode("ascii")
        _MockCapabilityDeployDriveServer.FILES = {}

    def _seed(self, file_id, payload_dict):
        _MockCapabilityDeployDriveServer.FILES[file_id] = _encrypt_like_vault_encryption_service(
            self.key_b64, payload_dict)

    def test_accepts_first_version_and_caches_it(self):
        self._seed("f1", {"capabilityId": "cap-a", "version": 1, "sourceCode": "print('v1')"})

        cache = {}
        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        self.assertEqual(results, [("f1", "accept", "first version seen for this capability")])
        self.assertEqual(cache["cap-a"]["version"], 1)
        self.assertEqual(cache["cap-a"]["sourceCode"], "print('v1')")
        self.assertIn("updatedAt", cache["cap-a"])

    def test_skips_already_current_version(self):
        self._seed("f1", {"capabilityId": "cap-a", "version": 2, "sourceCode": "print('v2')"})
        cache = {"cap-a": {"version": 2, "sourceCode": "print('v2')", "updatedAt": 0}}

        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        self.assertEqual(results, [("f1", "skip", "already current")])
        self.assertEqual(cache["cap-a"]["version"], 2)  # untouched

    def test_accepts_strictly_newer_and_overwrites_cache(self):
        self._seed("f1", {"capabilityId": "cap-a", "version": 5, "sourceCode": "print('v5')"})
        cache = {"cap-a": {"version": 3, "sourceCode": "print('v3')", "updatedAt": 0}}

        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        self.assertEqual(results[0][1], "accept")
        self.assertEqual(cache["cap-a"]["version"], 5)
        self.assertEqual(cache["cap-a"]["sourceCode"], "print('v5')")

    def test_rejects_older_version_and_leaves_cache_untouched(self):
        self._seed("f1", {"capabilityId": "cap-a", "version": 1, "sourceCode": "print('old')"})
        cache = {"cap-a": {"version": 4, "sourceCode": "print('v4')", "updatedAt": 0}}

        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        self.assertEqual(results[0][1], "reject")
        self.assertEqual(cache["cap-a"]["version"], 4)
        self.assertEqual(cache["cap-a"]["sourceCode"], "print('v4')")

    def test_malformed_payload_is_rejected_without_raising(self):
        self._seed("f1", {"capabilityId": "cap-a", "sourceCode": "print('no version field')"})

        cache = {}
        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        self.assertEqual(results[0][1], "reject")
        self.assertEqual(cache, {})

    def test_undecryptable_file_is_rejected_without_raising(self):
        _MockCapabilityDeployDriveServer.FILES["f1"] = b"not-real-encrypted-bytes-too-short"

        cache = {}
        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        self.assertEqual(results[0][1], "reject")
        self.assertEqual(cache, {})

    def test_multiple_files_mixed_outcomes_processed_independently(self):
        self._seed("f-new", {"capabilityId": "cap-a", "version": 2, "sourceCode": "print('v2')"})
        self._seed("f-same", {"capabilityId": "cap-b", "version": 1, "sourceCode": "print('v1')"})
        self._seed("f-bad", {"capabilityId": "cap-c"})  # malformed: missing version/sourceCode
        cache = {
            "cap-a": {"version": 1, "sourceCode": "print('v1')", "updatedAt": 0},
            "cap-b": {"version": 1, "sourceCode": "print('v1')", "updatedAt": 0},
        }

        results = pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64, cache=cache)

        by_file = {name: decision for name, decision, _ in results}
        self.assertEqual(by_file["f-new"], "accept")
        self.assertEqual(by_file["f-same"], "skip")
        self.assertEqual(by_file["f-bad"], "reject")
        self.assertEqual(cache["cap-a"]["version"], 2)
        self.assertEqual(cache["cap-b"]["version"], 1)
        self.assertNotIn("cap-c", cache)

    def test_persists_to_disk_when_no_explicit_cache_passed(self):
        home = os.environ.get("HABITTRACKER_COMPANION_HOME")
        try:
            import tempfile
            with tempfile.TemporaryDirectory() as tmp:
                os.environ["HABITTRACKER_COMPANION_HOME"] = tmp
                original_dir, original_file = pair.CONFIG_DIR, pair.CAPABILITIES_CACHE_FILE
                from pathlib import Path
                pair.CONFIG_DIR = Path(tmp)
                pair.CAPABILITIES_CACHE_FILE = Path(tmp) / "capabilities.json"
                try:
                    self._seed("f1", {"capabilityId": "cap-a", "version": 1, "sourceCode": "print('v1')"})
                    pair.poll_capability_deploy("fake-token", "folder-1", self.key_b64)

                    on_disk = json.loads(pair.CAPABILITIES_CACHE_FILE.read_text())
                    self.assertEqual(on_disk["cap-a"]["version"], 1)
                finally:
                    pair.CONFIG_DIR, pair.CAPABILITIES_CACHE_FILE = original_dir, original_file
        finally:
            if home is None:
                os.environ.pop("HABITTRACKER_COMPANION_HOME", None)
            else:
                os.environ["HABITTRACKER_COMPANION_HOME"] = home


if __name__ == "__main__":
    unittest.main()

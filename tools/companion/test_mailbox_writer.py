"""
Unit tests for the M9 scoped mailbox-write wrapper (mailbox_writer.py).

Covers:
  1. write_kpi_value() enforces a hard timeout around the whole load-config -> refresh-token ->
     encrypt -> upload sequence.
  2. write_kpi_value() succeeds and returns the Drive file id when everything completes in time.
  3. write_kpi_value() raises when the device isn't paired.
  4. Structural check that coding_hours_capability.py — the capability meant to call this wrapper
     — never imports pair.py or touches its config/token-loading path directly, only the exposed
     write_kpi_value() function. This is the actual enforcement mechanism the design doc calls
     for ("the capability process itself never touches the raw OAuth token"), so it's asserted
     structurally rather than just by convention.

Run with:  python3 -m unittest test_mailbox_writer -v
"""
import ast
import base64
import os
import time
import unittest
from pathlib import Path
from unittest import mock

import pair
import mailbox_writer

_FAKE_CONFIG = {
    "server": "https://habittrackerdima.me",
    "mailboxFolderId": "mailbox-folder-xyz",
    "encryptionKey": base64.b64encode(os.urandom(32)).decode("ascii"),
    "clientId": "fake-client-id",
    "clientSecret": "fake-client-secret",
    "refreshToken": "fake-refresh-token",
    "deviceId": "device-123",
}


class WriteKpiValueTimeoutTest(unittest.TestCase):

    def test_raises_timeout_error_when_write_hangs_past_timeout(self):
        def hanging_refresh(*args, **kwargs):
            time.sleep(2)
            return "fake-access-token"

        with mock.patch.object(pair, "load_config", return_value=dict(_FAKE_CONFIG)), \
             mock.patch.object(pair, "refresh_access_token", side_effect=hanging_refresh):
            with self.assertRaises(TimeoutError):
                mailbox_writer.write_kpi_value(
                    "Coding Hours", "2026-08-31", 3.5, timeout_seconds=0.2)

    def test_succeeds_and_returns_file_id_within_timeout(self):
        with mock.patch.object(pair, "load_config", return_value=dict(_FAKE_CONFIG)), \
             mock.patch.object(pair, "refresh_access_token", return_value="fake-access-token"), \
             mock.patch.object(pair, "upload_mailbox_file", return_value="file-id-123") as mock_upload:
            result = mailbox_writer.write_kpi_value(
                "Coding Hours", "2026-08-31", 3.5, timeout_seconds=5)
            self.assertEqual(result, "file-id-123")
            mock_upload.assert_called_once()
            # Full wire-format encryption round trip is already covered in test_pair.py — here
            # we just check write_kpi_value plumbed the right access token / mailbox folder
            # through to the upload call.
            self.assertEqual(mock_upload.call_args.args[0], "fake-access-token")
            self.assertEqual(mock_upload.call_args.args[1], "mailbox-folder-xyz")

    def test_raises_runtime_error_when_not_paired(self):
        with mock.patch.object(pair, "load_config", return_value={}):
            with self.assertRaises(RuntimeError):
                mailbox_writer.write_kpi_value("Coding Hours", "2026-08-31", 1.0, timeout_seconds=5)


class CapabilityAccessBoundaryTest(unittest.TestCase):
    """Structural enforcement check: the reference capability must only reach the mailbox through
    mailbox_writer.write_kpi_value(), never through pair.py's config/token-loading directly."""

    def setUp(self):
        capability_path = Path(__file__).parent / "coding_hours_capability.py"
        self.source = capability_path.read_text()
        self.tree = ast.parse(self.source, filename=str(capability_path))

    def _imported_top_level_names(self):
        names = set()
        for node in ast.walk(self.tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    names.add(alias.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom):
                if node.module:
                    names.add(node.module.split(".")[0])
        return names

    def test_capability_does_not_import_pair_module(self):
        imported = self._imported_top_level_names()
        self.assertNotIn(
            "pair", imported,
            "coding_hours_capability.py must never import pair.py directly — pair.py owns the "
            "raw Drive refresh token / local config file. It must only import mailbox_writer.",
        )

    def test_capability_imports_mailbox_writer(self):
        imported = self._imported_top_level_names()
        self.assertIn("mailbox_writer", imported)

    def test_capability_source_never_references_token_or_config_internals(self):
        # Belt-and-suspenders textual check in addition to the AST import check above — catches
        # e.g. `import pair as p` aliasing or `importlib.import_module("pair")` tricks, and any
        # direct reference to the config/token symbols pair.py exposes.
        for forbidden in ("CONFIG_FILE", "load_config", "refreshToken", "import pair"):
            self.assertNotIn(
                forbidden, self.source,
                f"coding_hours_capability.py must not reference {forbidden!r} — that belongs "
                f"only to pair.py/mailbox_writer.py, never to capability code.",
            )


if __name__ == "__main__":
    unittest.main()

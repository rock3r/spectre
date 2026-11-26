#!/usr/bin/env python3
"""Structural contracts for the packaged CLI/public crop verification cell."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/gnome-hosted-smoke.yml").read_text()
SESSION = (ROOT / ".github/scripts/gnome-hosted-smoke-session.sh").read_text()
DRIVER = (ROOT / ".github/scripts/gnome-cli-recording-live.py").read_text()
CROP = (ROOT / ".github/scripts/gnome-public-window-crop.py").read_text()


class CliRecordingLiveContractTest(unittest.TestCase):
    def test_cell_is_separate_read_only_and_pinned(self):
        self.assertIn("public-wayland-cli-recording-live:", WORKFLOW)
        self.assertIn("permissions:\n  contents: read", WORKFLOW)
        self.assertIn("sha256sum -c -", WORKFLOW)
        self.assertIn("d38754b614d0a0561c9921d95abe5cb4fb0a5f96", WORKFLOW)
        self.assertNotIn("git push", WORKFLOW)

    def test_packaged_cli_requests_once_then_checks_same_live_session(self):
        self.assertIn(":cli:shadowDistZip", SESSION)
        self.assertNotIn(":cli:installDist", SESSION)
        self.assertIn("SPECTRE_CLI_ZIP", SESSION)
        self.assertEqual(1, DRIVER.count('\"permissions\", \"request\", \"--json\"'))
        self.assertEqual(1, DRIVER.count('\"permissions\", \"check\", \"--json\"'))
        self.assertIn('\"version\", \"--json\"', DRIVER)
        self.assertIn("portal.accept_dialog", DRIVER)
        self.assertIn("socket.is_socket()", DRIVER)
        self.assertIn("tokenPath", DRIVER)
        self.assertNotIn('\"--session\"', DRIVER)

    def test_exact_native_proven_crop_probe_is_called_with_inherited_environment(self):
        self.assertIn('with_name("gnome-public-window-crop.py")', DRIVER)
        self.assertIn("env = os.environ.copy()", DRIVER)
        self.assertIn('env["SPECTRE_WAYLAND_CONSENT_PROMPT"] = "never"', DRIVER)
        self.assertIn("env=env", DRIVER)
        self.assertIn("crop-proof.stdout", DRIVER)
        self.assertIn("crop-proof.stderr", DRIVER)
        self.assertNotIn("BlueDecoy", DRIVER)
        self.assertNotIn("runPublicWaylandCliRecordingLive", DRIVER)
        for fact in ("runPublicWaylandCropProof", "red-decorated", "green-undecorated",
                     "decodedFrameCount", "public-window-crop-evidence.json"):
            self.assertIn(fact, CROP)

    def test_observer_records_only_real_samples_and_tolerates_removed_nodes(self):
        self.assertIn('\"--observe\"', DRIVER)
        self.assertNotIn("threading", DRIVER)
        self.assertIn("def expected_disappearance", DRIVER)
        self.assertIn("portal.semantics.profile", DRIVER)
        self.assertIn('\"kind\": \"sample\"', DRIVER)
        self.assertIn('\"kind\": \"transient-removed-node\"', DRIVER)
        self.assertNotIn('\"pickerCount\": None', DRIVER)
        self.assertIn("minimum_observation_seconds", DRIVER)
        self.assertIn("mean_sampling_interval_seconds", DRIVER)
        self.assertIn("subsequent_picker_samples", DRIVER)
        self.assertIn("nonzero_runs == 1", DRIVER)

    def test_cli_evidence_is_written_before_crop_and_crop_failure_is_retained(self):
        evidence_write = DRIVER.index('\"cli-recording-live-evidence.json\"')
        crop_run = DRIVER.index('with_name("gnome-public-window-crop.py")')
        self.assertLess(evidence_write, crop_run)
        self.assertIn("cropExitCode", DRIVER)
        self.assertIn("portalObservations", DRIVER)
        self.assertIn("pickerCountAfterGrant", DRIVER)
        self.assertIn("packagedCliVersion", DRIVER)
        self.assertIn("documentedUnsupportedLinuxApi", DRIVER)
        self.assertIn("doesNotAssertRawPeerDenial", DRIVER)


if __name__ == "__main__":
    unittest.main()

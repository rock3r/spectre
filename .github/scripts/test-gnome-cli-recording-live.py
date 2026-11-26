#!/usr/bin/env python3
"""Structural fail-closed contracts for the separate CLI/recording live cell."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/gnome-hosted-smoke.yml").read_text()
SESSION = (ROOT / ".github/scripts/gnome-hosted-smoke-session.sh").read_text()
DRIVER = (ROOT / ".github/scripts/gnome-cli-recording-live.py").read_text()
FIXTURE = (ROOT / ".github/verification/wayland-cli-recording-live/PublicWaylandCliRecordingLive.kt").read_text()


class CliRecordingLiveContractTest(unittest.TestCase):
    def test_cell_is_separate_read_only_and_pinned(self):
        self.assertIn("public-wayland-cli-recording-live:", WORKFLOW)
        self.assertIn("permissions:\n  contents: read", WORKFLOW)
        self.assertIn("sha256sum -c SHA256SUMS", WORKFLOW)
        self.assertIn("832a2a3a87b3d99025e5b31cb8e3761b4bc3c580", WORKFLOW)
        self.assertNotIn("git push", WORKFLOW)

    def test_installed_cli_and_real_portal_are_used_once(self):
        self.assertIn(":cli:installDist", SESSION)
        self.assertIn('"permissions", "request", "--json"', DRIVER)
        self.assertIn('"permissions", "check", "--json"', DRIVER)
        self.assertIn("portal.accept_dialog", DRIVER)
        self.assertIn('"pickerAppearances": nonzero_runs', DRIVER)
        self.assertIn("nonzero_runs != 1", DRIVER)
        for forbidden in ("PermissionStore", "SetPermission", "xdotool click"):
            self.assertNotIn(forbidden, DRIVER + SESSION)

    def test_public_api_color_movie_focus_and_pid_contracts(self):
        for contract in (
            "AutoScreenshotter", "AutoRecorder", "asTitledWindow", "mismatchedPidRejected",
            "requireActivePid", "green-refocused", "Color.RED", "Color.GREEN",
        ):
            self.assertIn(contract, FIXTURE)
        for contract in (
            "ffprobe", "ffmpeg", "(.1, .5, .9)", 'coverage["blue"] >= .01',
            "helperAliveAfterCaptures", "grantMarkerExists", "sessionSocketExists",
        ):
            self.assertIn(contract, DRIVER)


if __name__ == "__main__":
    unittest.main()

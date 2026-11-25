#!/usr/bin/env python3
"""Fail-closed structural contracts for the temporary GNOME hosted smoke."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/gnome-hosted-smoke.yml"
DRIVER = ROOT / ".github/scripts/gnome-hosted-smoke.sh"
SESSION = ROOT / ".github/scripts/gnome-hosted-smoke-session.sh"
BRANCH = "release-0.10/gnome-hosted-smoke"
CANDIDATE = "7ececf7a7ac361d87417bb07108cc475f7cf890e"
ATSPI_SHA = "ebda65d1c23604dbea9dccc987293259afe3c0ef"
FIXTURE_SHA = "96cd6cb20e44d5d26a4cfb93d8a3f8e1b6375aa8"
IDENTITY = ROOT / ".github/scripts/gnome-window-identity-probe.py"
MUTTER_CONTROL = ROOT / ".github/scripts/gnome-mutter-control-probe.py"
VERIFICATION_EXTENSION = ROOT / ".github/gnome-shell-extensions/spectre-mutter-control-verification@sebastiano.dev/extension.js"
WARMUP = ROOT / ".github/scripts/gnome-portal-dialog-warmup.py"
ATSPI_OWNERSHIP = ROOT / ".github/scripts/gnome-atspi-ownership-probe.py"


class GnomeHostedSmokeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        cls.driver = DRIVER.read_text()
        cls.session = SESSION.read_text()

    def test_workflow_is_read_only_and_branch_scoped(self):
        self.assertRegex(self.workflow, r"(?m)^on:\s*\n\s+workflow_dispatch:")
        self.assertIn("contents: read", self.workflow)
        self.assertNotIn("contents: write", self.workflow)
        self.assertIn(f"refs/heads/{BRANCH}", self.workflow)
        self.assertIn(f"branches: [{BRANCH}]", self.workflow)
        self.assertIn("github.ref", self.workflow)
        self.assertIn("runs-on: ubuntu-24.04", self.workflow)

    def test_candidate_and_atspi_patch_are_immutable(self):
        self.assertIn(f"default: {CANDIDATE}", self.workflow)
        self.assertIn("ref: ${{ inputs.candidate_sha ||", self.workflow)
        self.assertIn(ATSPI_SHA, self.workflow)
        self.assertIn(FIXTURE_SHA, self.workflow)
        self.assertNotRegex(self.workflow, r"refs/pull/716|pull/716/(head|merge)")
        self.assertIn("path: atspi-reader", self.workflow)
        self.assertIn("path: fixture", self.workflow)
        self.assertIn("tests/accessibility_xvfb.sh", self.session)
        self.assertIn("candidate_sha:", self.workflow)
        self.assertIn("CANDIDATE_SHA must be a full lowercase SHA", self.driver)

    def test_atspi_ownership_is_independent_bounded_hosted_evidence(self):
        probe = ATSPI_OWNERSHIP.read_text()
        self.assertIn("atspi-ownership", self.workflow)
        self.assertIn('SMOKE_MODE" == atspi-ownership', self.session)
        self.assertEqual(
            self.workflow.count("matrix.mode == 'accessibility' || matrix.mode == 'atspi-ownership'"),
            3,
        )
        for contract in (
            "REPETITIONS = 8",
            "QUERY_TIMEOUT_MS = 5000",
            '"timeoutMs": timeout_ms',
            '"processId": fixture_pids[0]',
            '"processId": fixture_pids[1]',
            '"processId": verification_pid',
            '"ambiguous-title"',
            '"deadline-1ms"',
            'for desired_status in (False, True)',
            '"settingsBefore"',
            '"settingsAfter"',
            '"elapsedMs"',
            '"fixturePids"',
            '"reply"',
            '"summary.json"',
            '"junit.xml"',
            "write_bytes(run.stdout)",
            '"sha": "f4044ce86653ba2f60153e2759d208e7e759e3ae"',
            '"observationRun": 10',
            '"typedTimeoutOccurrences"',
            'if "DISPLAY" in os.environ',
            "ready PID",
            'error.get("code") == "target-not-found"',
            'error.get("code") == "timeout"',
        ):
            self.assertIn(contract, probe)
        self.assertNotIn("pkill", probe)
        self.assertNotIn("killall", probe)

    def test_driver_requires_real_systemd_kvm_and_logind(self):
        for contract in (
            "/proc/1/comm",
            "/dev/kvm",
            "loginctl",
            "PAMName=login",
            "gnome-shell",
            "pipewire",
            "wireplumber",
            "at-spi2-core",
        ):
            self.assertIn(contract, self.driver)
        self.assertNotIn("systemctl start systemd-logind || true", self.driver)

    def test_session_runs_real_smoke_with_virtual_output_and_extension(self):
        for contract in (
            "--virtual-monitor",
            "gnome-extensions enable",
            ":recording:runWaylandMutterWindowSmoke",
            "SPECTRE_CAPTURE_BACKEND=wayland",
            "WAYLAND_DISPLAY",
            "XDG_SESSION_TYPE=wayland",
        ):
            self.assertIn(contract, self.session)
        self.assertNotRegex(self.session, r"\|\|\s*true.*runWaylandMutterWindowSmoke")

    def test_diagnostics_are_always_uploaded(self):
        self.assertIn("if: always()", self.workflow)
        self.assertIn("actions/upload-artifact@", self.workflow)
        self.assertIn("if-no-files-found: error", self.workflow)
        for diagnostic in ("loginctl", "journalctl", "pw-dump", "gnome-shell.log"):
            self.assertIn(diagnostic, self.driver + self.session)

    def test_identity_is_independent_public_api_evidence(self):
        identity = IDENTITY.read_text()
        self.assertIn("mode: [portal, capture, accessibility, atspi-ownership, identity, mutter-control]", self.workflow)
        self.assertIn("SMOKE_MODE\" == identity", self.session)
        for contract in ("LookupWindow", "RecordWindow", "PipeWireStreamAdded", "gst-launch-1.0",
                         "focusSamples", "focusObservedXids", "interiorColorCounts", "pipeWireNode"):
            self.assertIn(contract, identity)
        self.assertNotIn("runWaylandMutterWindowSmoke", identity)

    def test_mutter_control_uses_separate_caller_owned_verification_service(self):
        probe = MUTTER_CONTROL.read_text()
        extension = VERIFICATION_EXTENSION.read_text()
        self.assertIn("mode: [portal, capture, accessibility, atspi-ownership, identity, mutter-control]", self.workflow)
        self.assertIn('SMOKE_MODE" == mutter-control', self.session)
        for contract in (
            "GetConnectionUnixProcessID", "get_client_type()", "Meta.WindowClientType.X11",
            "get_description()", "/^0x[0-9a-fA-F]+$/", "get_id()", "new GLib.Variant('(t)'",
        ):
            self.assertIn(contract, extension)
        for contract in (
            "LookupOwnedX11Window", 'GLib.Variant(option_type, window_id',
            "wrong-type-blue-control", "prove_cross_pid_denied", "blue_is_focused",
            "RecordWindow", "interiorColorCounts",
        ):
            self.assertIn(contract, probe)
        self.assertNotIn("get_xwindow", extension)
        self.assertNotIn("owner_pid", extension)
        self.assertIn("../gnome-shell-extensions/$extension_uuid", self.session)

    def test_portal_warmup_drives_public_api_then_candidate_helper(self):
        warmup = WARMUP.read_text()
        for contract in (
            "org.freedesktop.portal.RemoteDesktop",
            "CreateSession",
            "SelectDevices",
            "org.freedesktop.portal.ScreenCast",
            "SelectSources",
            'TITLE = "Remote Desktop"',
            'MONITOR = 1',
            'KEYBOARD_POINTER = 3',
            "xdg-desktop-portal-gnome",
            "portal-atspi-tree.json",
            "helper-atspi-tree.json",
            '"--session"',
        ):
            self.assertIn(contract, warmup)
        self.assertIn("SMOKE_MODE\" == portal", self.session)
        self.assertIn("--mode portal", self.session)
        self.assertIn("--mode helper", self.session)
        self.assertIn("SPECTRE_WAYLAND_CONSENT_PROMPT=never", self.session)
        self.assertLess(self.session.index("--mode helper"),
                        self.session.index(":recording:runWaylandMutterWindowSmoke"))

    def test_portal_verification_has_no_bypass(self):
        warmup = WARMUP.read_text()
        forbidden = ("PermissionStore", "permission-store", "SetPermission", "xdotool")
        for token in forbidden:
            self.assertNotIn(token, warmup)
        self.assertNotRegex(self.session, r"portal-dialog-warmup.py.*\|\|\s*true")
        self.assertIn("gir1.2-gtk-4.0", self.driver)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Structural and focused behavioral contracts for the CLI/recording live cell."""
import ast
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/gnome-hosted-smoke.yml").read_text()
SESSION = (ROOT / ".github/scripts/gnome-hosted-smoke-session.sh").read_text()
DRIVER = (ROOT / ".github/scripts/gnome-cli-recording-live.py").read_text()
FIXTURE = (ROOT / ".github/verification/wayland-cli-recording-live/PublicWaylandCliRecordingLive.kt").read_text()


def load_decoy_selector(artifacts):
    module = ast.parse(DRIVER)
    function = next(node for node in module.body
                    if isinstance(node, ast.FunctionDef) and node.name == "decoy_xid")
    namespace = {"json": json, "Path": Path, "re": re, "subprocess": subprocess}
    filename = ROOT / ".github/scripts/gnome-cli-recording-live.py"
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(filename), "exec"), namespace)
    return namespace["decoy_xid"]


class CliRecordingLiveContractTest(unittest.TestCase):
    def test_decoy_selector_deduplicates_numeric_xids_but_rejects_distinct_matches(self):
        props = ('_NET_WM_PID(CARDINAL) = 8005\n'
                 'WM_CLASS(STRING) = "verification-BlueDecoy", "verification-BlueDecoy"\n')
        with tempfile.TemporaryDirectory() as directory:
            selector = load_decoy_selector(Path(directory))
            with mock.patch.object(subprocess, "run", side_effect=[
                subprocess.CompletedProcess([], 0, "6291463\n6291463\n", "alias warning"),
                subprocess.CompletedProcess([], 0, props, ""),
            ]) as run:
                self.assertEqual("6291463", selector(8005, "target", Path(directory)))
            self.assertEqual(2, run.call_count)
            evidence = json.loads((Path(directory) / "decoy-x11-selection.json").read_text())
            self.assertEqual(["6291463", "6291463"], evidence["rawSearchResults"])
            self.assertEqual([6291463], evidence["distinctNumericXids"])

        with tempfile.TemporaryDirectory() as directory:
            selector = load_decoy_selector(Path(directory))
            with mock.patch.object(subprocess, "run", side_effect=[
                subprocess.CompletedProcess([], 0, "6291463\n6291464\n", ""),
                subprocess.CompletedProcess([], 0, props, ""),
                subprocess.CompletedProcess([], 0, props, ""),
            ]):
                with self.assertRaisesRegex(RuntimeError, "exactly one"):
                    selector(8005, "target", Path(directory))

    def test_cell_is_separate_read_only_and_pinned(self):
        self.assertIn("public-wayland-cli-recording-live:", WORKFLOW)
        self.assertIn("permissions:\n  contents: read", WORKFLOW)
        self.assertIn("sha256sum -c SHA256SUMS", WORKFLOW)
        self.assertIn("7ececf7a7ac361d87417bb07108cc475f7cf890e", WORKFLOW)
        self.assertNotIn("git push", WORKFLOW)

    def test_installed_cli_and_real_portal_are_used_once(self):
        self.assertIn(":cli:shadowDistZip", SESSION)
        self.assertNotIn(":cli:installDist", SESSION)
        self.assertIn("SPECTRE_CLI_ZIP", SESSION)
        self.assertIn("extract_release", DRIVER)
        self.assertIn('runtime/bin/java', DRIVER)
        self.assertIn('"permissions", "request", "--json"', DRIVER)
        self.assertIn('"permissions", "check", "--json"', DRIVER)
        self.assertIn("portal.accept_dialog", DRIVER)
        self.assertIn('"pickerAppearances": nonzero_runs', DRIVER)
        self.assertIn("nonzero_runs != 1", DRIVER)
        for forbidden in ("PermissionStore", "SetPermission", "xdotool click"):
            self.assertNotIn(forbidden, DRIVER + SESSION)

    def test_public_api_color_movie_focus_and_pid_contracts(self):
        for contract in (
            "AutoScreenshotter", "AutoRecorder", "asTitledWindow", "captureWindowByTitle",
            "crossProcessByTitleUnsupported", "focusDecoy", "Color.RED", "Color.GREEN",
        ):
            self.assertIn(contract, FIXTURE)
        for contract in (
            "ffprobe", "ffmpeg", "(.1, .5, .9)", 'coverage["blue"] >= .01',
            "helperAliveAfterCaptures", "grantMarkerExists", "sessionSocketExists",
            "packagedJavaVersion", "portal-observations.jsonl", "tokenStored",
        ):
            self.assertIn(contract, DRIVER)
        self.assertIn('"--all", "--onlyvisible", "--pid", str(pid)', DRIVER)
        self.assertIn('"--name", pattern', DRIVER)
        self.assertIn('"xprop", "-id", str(xid), "_NET_WM_PID"', DRIVER)
        self.assertIn('env["DECOY_XID"] = xid', DRIVER)
        self.assertIn('System.getenv("DECOY_XID")', FIXTURE)
        self.assertNotIn('ProcessBuilder("xdotool", "search"', FIXTURE)

    def test_observer_is_process_isolated_and_outputs_are_sanitized(self):
        self.assertIn('"--observe"', DRIVER)
        self.assertNotIn("threading", DRIVER)
        self.assertIn("def sanitized", DRIVER)
        self.assertIn("<redacted>", DRIVER)
        self.assertIn("except GLib.GError as error", DRIVER)
        self.assertIn('"event": "application-disappeared"', DRIVER)
        self.assertIn('"dialogSamples": len(samples)', DRIVER)
        self.assertIn("samples[-1][\"monotonic\"] < observer_after_green", DRIVER)
        self.assertNotIn("object : TitledWindow", FIXTURE)
        self.assertIn("awtOwnerArgumentIdentityChecked", FIXTURE)


if __name__ == "__main__":
    unittest.main()

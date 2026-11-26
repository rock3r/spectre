#!/usr/bin/env python3
"""Static fail-closed contracts for the temporary #724 hosted QA cell."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LocateWindow724ContractTest(unittest.TestCase):
    def test_single_scoped_noble_job_and_exact_candidate(self):
        workflow = (ROOT / ".github/workflows/qa-724-locate-window.yml").read_text()
        for value in (
            "runs-on: ubuntu-24.04",
            "release-0.10/gnome-hosted-smoke",
            "e963afeb01991600891e8fb7bed5d0df6d860dbf",
            "1a727b978ba049f25a65d917ae64795921a34d9f",
            "recording/native/linux/src/main.rs",
            "contents: read",
            "persist-credentials: false",
        ):
            self.assertIn(value, workflow)
        self.assertEqual(workflow.count("runs-on:"), 1)

    def test_probe_owns_window_and_child_and_redacts_success_token(self):
        probe = (ROOT / ".github/scripts/gnome-locate-window-724.py").read_text()
        for value in (
            "_NET_WM_PID",
            "_NET_WM_WINDOW_TYPE_NORMAL",
            '["xwininfo", "-id", hex(xid)]',
            '["xprop", "-id", hex(xid)',
            '"owner_pid": os.getpid()',
            "subprocess.Popen([helper]",
            "child.communicate(payload",
            'len(parsed) != 1',
            'event.get("kind") != "Helper"',
            'parsed[0].pop("binding_token", None)',
            '"bindingTokenLength": len(token)',
        ):
            self.assertIn(value, probe)
        self.assertNotIn('"binding_token": token', probe)

    def test_session_uses_packaged_helpers_then_restores_public_flow(self):
        session = (ROOT / ".github/scripts/gnome-hosted-smoke-session.sh").read_text()
        start = session.index('SMOKE_MODE" == locate-window-724')
        block = session[start:session.index('SMOKE_MODE" == cli-recording-live', start)]
        for value in (
            ":recording-linux:jar",
            'jar" xf',
            "chmod 0755",
            "sha256sum",
            "gnome-locate-window-724.py",
            "gnome-extensions list --enabled",
            "gdbus wait --session dev.sebastiano.Spectre.WindowIdentity",
            ":cli:shadowDistZip",
            "gnome-cli-recording-live.py",
        ):
            self.assertIn(value, block)
        self.assertLess(block.index("gnome-locate-window-724.py"),
                        block.index("gnome-cli-recording-live.py"))


if __name__ == "__main__":
    unittest.main()

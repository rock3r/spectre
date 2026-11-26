#!/usr/bin/env python3
"""Contract smoke for the final genuine Linux-seat report gate."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from smoke_lib import REQUIRED_SCENARIO_IDS  # noqa: E402

SHA = "0123456789abcdef0123456789abcdef01234567"
NA = {
    "macos-tcc": "Linux does not use macOS Screen Recording / Accessibility TCC",
    "a11y-accesskit-macos": "requires a native Darwin desktop",
    "a11y-accesskit-windows": "requires a native Windows desktop",
}

def report():
    return {"schemaVersion": 1, "version": "0.10.0", "base": "v0.9.0", "sha": SHA,
            "dirty": False, "startedAt": "2026-11-26T10:00:00+00:00",
            "finishedAt": "2026-11-26T10:30:00+00:00",
            "environment": {"os": "Linux", "arch": "x86_64", "displayMode": "seat-forced"},
            "scenarios": [{"id": sid, "name": sid,
                           "result": "n/a" if sid in NA else "pass",
                           "reason": NA.get(sid, ""), "hard": True}
                          for sid in REQUIRED_SCENARIO_IDS]}

class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)
        self.console = self.path / "release-smoke-console.log"
        self.consent = self.path / "consent-summary.json"
        self.console.write_text("displayMode: seat-forced\n")
        self.consent.write_text(json.dumps({"count": 1, "grants": [{
            "profile": "gnome-46",
            "observedAt": "2026-11-26T10:01:00+00:00",
            "acceptedAt": "2026-11-26T10:01:02+00:00",
            "remoteInteractionApproved": True,
            "rememberApproved": True}]}))

    def run_gate(self, value, sha=SHA):
        candidate = self.path / "report.json"
        candidate.write_text(json.dumps(value))
        return subprocess.run([
            sys.executable, str(ROOT / ".github/scripts/verify-final-release-report.py"),
            str(candidate), "--repo", str(ROOT), "--sha", sha,
            "--console", str(self.console), "--consent-summary", str(self.consent)],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).returncode

    def test_representative_clean_linux_report(self):
        value = report()
        self.assertEqual(26, len(value["scenarios"]))
        self.assertEqual(0, self.run_gate(value))

    def test_missing(self):
        value = report()
        value["scenarios"].pop()
        self.assertNotEqual(0, self.run_gate(value))

    def test_coordinator_sha_may_change(self):
        value = report()
        changed = "fedcba9876543210fedcba9876543210fedcba98"
        value["sha"] = changed
        self.assertEqual(0, self.run_gate(value, changed))

    def test_improper_na(self):
        value = report()
        value["scenarios"][1]["reason"] = "unsupported"
        self.assertNotEqual(0, self.run_gate(value))

    def test_wrong_seat(self):
        value = report()
        value["environment"]["displayMode"] = "xvfb-forced"
        self.assertNotEqual(0, self.run_gate(value))

    def test_failed_input(self):
        value = report()
        next(row for row in value["scenarios"]
             if row["id"] == "input-coord-contention")["result"] = "fail"
        self.assertNotEqual(0, self.run_gate(value))

if __name__ == "__main__":
    unittest.main()

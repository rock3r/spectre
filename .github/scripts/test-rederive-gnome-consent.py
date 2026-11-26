#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/rederive-gnome-consent.py"


class HistoricalConsentTest(unittest.TestCase):
    def run_case(self, nodes):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            tree = path / "consent-1-atspi-tree.json"
            original = path / "consent-summary.json"
            tree.write_text(json.dumps({"backend": "org.freedesktop.impl.portal.desktop.gnome",
                "executable": "xdg-desktop-portal-gnome", "matchedProfile": "gnome-46",
                "matchingDialogCount": 1, "pid": 8438, "nodes": nodes}))
            original.write_text(json.dumps({"count": 1, "grants": [{"ordinal": 1,
                "portalBackendPid": 8438, "observedAt": "2026-11-26T14:08:07+00:00",
                "acceptedAt": "2026-11-26T14:08:08+00:00", "profile": "gnome-46",
                "selectedRole": "toggle button", "remoteInteractionApproved": True,
                "rememberAvailable": False, "rememberApproved": False}]}))
            result = subprocess.run([sys.executable, str(SCRIPT), "--tree", str(tree),
                "--original", str(original), "--derived", str(path / "derived.json"),
                "--differences", str(path / "differences.json")], check=False)
            derived = json.loads((path / "derived.json").read_text()) if result.returncode == 0 else None
            return result.returncode, derived

    def test_authentic_mnemonic_checkbox_is_rederived(self):
        code, value = self.run_case([{"index": 10, "name": "_Remember This Selection",
            "role": "check box", "states": ["checkable", "checked", "focusable", "sensitive",
                                                     "showing", "visible"], "actions": []},
            {"index": 12, "name": "Remember This Selection", "role": "label",
             "states": ["showing", "visible"], "actions": []}])
        self.assertEqual(0, code)
        self.assertTrue(value["grants"][0]["rememberApproved"])

    def test_missing_unchecked_duplicate_and_label_only_fail(self):
        checkbox = {"index": 10, "name": "_Remember This Selection", "role": "check box",
                    "states": ["checked", "showing"], "actions": []}
        cases = [[], [dict(checkbox, states=["showing"])], [checkbox, dict(checkbox, index=11)],
                 [dict(checkbox, role="label")]]
        for nodes in cases:
            with self.subTest(nodes=nodes):
                self.assertNotEqual(0, self.run_case(nodes)[0])


if __name__ == "__main__":
    unittest.main()

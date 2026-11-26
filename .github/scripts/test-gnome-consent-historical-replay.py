#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/gnome-consent-historical-replay.yml"


class HistoricalReplayWorkflowTest(unittest.TestCase):
    def test_is_read_only_branch_path_and_commit_scoped(self):
        text = WORKFLOW.read_text()
        self.assertIn("branches: [release-0.10/gnome-hosted-smoke]", text)
        self.assertIn("paths: [.github/workflows/gnome-consent-historical-replay.yml]", text)
        self.assertIn("Verify Jammy QA replays GNOME consent", text)
        self.assertIn("actions: read", text)
        self.assertIn("contents: read", text)
        self.assertNotIn("contents: write", text)
        self.assertIn("persist-credentials: false", text)

    def test_pins_history_and_preserves_inputs(self):
        text = WORKFLOW.read_text()
        for value in ("15126133382", "45835317389", "139962588421",
                      "05f4e18edde2db8ae2d42a36b65deb5c379299ec",
                      "cmp \"$RUNNER_TEMP/replay/release-smoke.original.json\"",
                      "original-inputs.sha256", "derived-consent-summary.json",
                      "consent-differences.json", "verify-final-release-report.py"):
            self.assertIn(value, text)
        self.assertIn('find "$RUNNER_TEMP/download" -type f -name "$1" -print0', text)
        self.assertIn('test "${#matches[@]}" = 1', text)


if __name__ == "__main__":
    unittest.main()

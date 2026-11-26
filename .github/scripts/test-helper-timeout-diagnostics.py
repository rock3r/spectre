#!/usr/bin/env python3
"""Contract tests for the disposable Windows helper-timeout diagnostic harness."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/helper-timeout-diagnostics.yml"
HELPER = ROOT / ".github/scripts/helper-timeout-diagnostics.ps1"
PINNED_COMMIT = "6ba287814779aaaecd3501edc288bba7f3e31b1f"


class DiagnosticHarnessContractTest(unittest.TestCase):
    def test_workflow_is_qa_branch_only_read_only_and_bounded(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(text, r"(?m)^\s+branches:\s*\n\s+- release-0\.10/verify-helper-windows$")
        self.assertNotRegex(text, r"(?m)^\s+- main$")
        self.assertIn("contents: read", text)
        self.assertIn("runs-on: windows-latest", text)
        self.assertIn("timeout-minutes: 35", text)
        self.assertRegex(text, r"lane:\s*\[1, 2\]")

    def test_product_tests_are_pinned_and_checkout_is_proved_clean(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(PINNED_COMMIT, text)
        self.assertIn("git diff --exit-code", text)
        self.assertIn("git status --porcelain", text)
        self.assertIn("git rev-parse HEAD", text)
        self.assertIn("--max-workers=4", text)

    def test_helper_keeps_every_fresh_xml_and_diagnostic(self) -> None:
        text = HELPER.read_text(encoding="utf-8")
        self.assertIn("Iterations = 16", text)
        self.assertIn('"--rerun"', text)
        self.assertIn("--no-build-cache", text)
        self.assertIn("TEST-*.xml", text)
        self.assertIn("missingXml", text)
        self.assertIn("suppressed", text.lower())
        self.assertIn("remainingMs", text)
        self.assertIn("processes", text)
        self.assertNotIn("taskkill", text.lower())
        self.assertNotRegex(text, re.compile(r"Get-Process\s+java.*Stop-Process", re.I | re.S))


if __name__ == "__main__":
    unittest.main()

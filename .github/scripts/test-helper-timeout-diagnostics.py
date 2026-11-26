#!/usr/bin/env python3
"""Scope and safety contracts for the disposable hosted-Windows QA carrier."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/helper-timeout-diagnostics.yml"
HARNESS = ROOT / ".github/scripts/helper-timeout-diagnostics.ps1"
PIN = "fd462c8dd790471a7da58865761ae540af66b3ac"


class DiagnosticHarnessContractTest(unittest.TestCase):
    def test_workflow_is_branch_only_read_only_secretless_and_two_jobs(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(text, r"(?m)^\s+branches:\s*\n\s+- release-0\.10/verify-helper-windows$")
        self.assertNotRegex(text, r"(?m)^\s+- (main|release-0\.10)$")
        self.assertIn("permissions:\n  contents: read", text)
        self.assertNotIn("secrets.", text)
        self.assertEqual(len(re.findall(r"(?m)^  [a-z][a-z-]+:\n    name:", text)), 2)
        self.assertEqual(text.count("runs-on: windows-latest"), 2)

    def test_product_is_pinned_clean_and_matches_real_windows_jdk(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertGreaterEqual(text.count(PIN), 3)
        self.assertEqual(text.count('java-version: "25"'), 2)
        self.assertGreaterEqual(text.count("git diff --exit-code"), 4)
        self.assertGreaterEqual(text.count("git status --porcelain"), 4)

    def test_full_check_and_three_complete_class_runs_are_requested(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        harness = HARNESS.read_text(encoding="utf-8")
        self.assertIn("-Mode FullCheck", workflow)
        self.assertIn("-Mode RepeatClass -Iterations 3", workflow)
        self.assertIn('@("check")', harness)
        self.assertIn('":agent:test", "--tests", $testPattern, "--rerun"', harness)
        self.assertNotIn("--max-workers", workflow + harness)
        self.assertNotIn("--no-daemon", workflow + harness)
        self.assertIn("targetTestRan", harness)

    def test_evidence_is_narrow_bounded_and_retains_failures(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        harness = HARNESS.read_text(encoding="utf-8")
        for name in ("gradle.log", "stop.log", "warmup.log", "build.pid", "fixture.ready", "fixture.release"):
            self.assertIn(name, harness)
        self.assertIn('Get-ChildItem $env:TEMP -Directory -Filter "junit*"', harness)
        self.assertIn("failed-owned-fixture-processes.json", harness)
        self.assertIn('exceptionFormat "full"', harness)
        self.assertEqual(workflow.count("if: always()"), 6)
        self.assertNotRegex(harness, re.compile(r"Get-Process\s+java.*Stop-Process", re.I | re.S))
        self.assertNotIn("taskkill", harness.lower())
        self.assertIn("${{ runner.temp }}/launch-marker-", workflow)


if __name__ == "__main__":
    unittest.main()

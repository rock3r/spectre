#!/usr/bin/env python3
"""Contract tests for release-smoke orchestration, report schema, and MCP stdio."""
from __future__ import annotations

import importlib.util
import json
import os
import platform
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MCP_SMOKE = ROOT / "scripts" / "mcp-stdio-smoke.py"
RELEASE_SMOKE = ROOT / "scripts" / "release-smoke.py"
SMOKE_LIB = ROOT / "scripts" / "smoke_lib.py"


def load_smoke_lib():
    spec = importlib.util.spec_from_file_location("smoke_lib", SMOKE_LIB)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    # Python 3.9 dataclasses need the module present in sys.modules before exec.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


smoke_lib = load_smoke_lib()


class McpStdioSmokeTest(unittest.TestCase):
    def fake_server(self, *, banner: bool = False, version: str = "0.5.0") -> Path:
        directory = Path(tempfile.mkdtemp())
        script = directory / "fake.py"
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\n"
            f"banner={banner!r}; version={version!r}\n"
            "if banner: print('not-json', flush=True)\n"
            "for line in sys.stdin:\n"
            " r=json.loads(line); method=r.get('method')\n"
            " if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'spectre','version':version}}\n"
            " elif method=='tools/list': result={'tools':[{'name':'list_processes'},{'name':'detach'}]}\n"
            " elif method=='tools/call':\n"
            "  name=(r.get('params') or {}).get('name')\n"
            "  if name=='detach': result={'isError':True,'content':[{'type':'text','text':'session not found'}]}\n"
            "  else: result={'content':[{'type':'text','text':'ok'}]}\n"
            " else: continue\n"
            " print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':result}), flush=True)\n"
        )
        script.chmod(0o755)
        return script

    def run_smoke(self, server: Path):
        # Launch the fake server via the same interpreter: Windows cannot exec a
        # shebang-only .py (WinError 193). Production smoke still passes a real
        # packaged spectre binary as the command.
        return subprocess.run(
            [
                sys.executable,
                str(MCP_SMOKE),
                "--expected-version",
                "0.5.0",
                "--",
                sys.executable,
                str(server),
            ],
            text=True,
            capture_output=True,
            timeout=10,
        )

    def test_clean_server_passes(self):
        result = self.run_smoke(self.fake_server())
        self.assertEqual(0, result.returncode, result.stderr)

    def test_stdout_pollution_fails(self):
        result = self.run_smoke(self.fake_server(banner=True))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("non-JSON stdout", result.stderr)

    def test_wrong_version_fails(self):
        result = self.run_smoke(self.fake_server(version="0.1.0"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("server version", result.stderr)


class SmokeLibSchemaTest(unittest.TestCase):
    def test_schema_version_constant(self):
        self.assertEqual(1, smoke_lib.SCHEMA_VERSION)

    def test_required_scenario_ids_are_stable(self):
        expected = {
            "preflight",
            "check",
            "junit-live",
            "agent-attach-core",
            "agent-contract-corpus",
            "agent-inject",
            "agent-launch-and-attach",
            "cli-packaged",
            "cli-native-helper-layout",
            "cli-user-flow",
            "mcp-sdk-flow",
            "host-native-recording",
            "maven-local-consumer",
        }
        self.assertEqual(expected, set(smoke_lib.REQUIRED_SCENARIO_IDS))

    def test_hard_na_without_reason_becomes_fail(self):
        result = smoke_lib.scenario_result(
            "host-native-recording",
            name="Host native recording",
            result="n/a",
            reason="",
            hard=True,
        )
        self.assertEqual("fail", result.result)
        self.assertIn("without N/A reason", result.detail)

    def test_hard_na_with_reason_allowed(self):
        result = smoke_lib.scenario_result(
            "host-native-recording",
            name="Host native recording",
            result="n/a",
            reason="Linux Wayland portal requires real desktop session",
            hard=True,
        )
        self.assertEqual("n/a", result.result)
        self.assertTrue(result.reason)

    def test_soft_na_without_reason_allowed(self):
        result = smoke_lib.scenario_result(
            "soft-cell",
            name="Soft",
            result="n/a",
            reason="",
            hard=False,
        )
        self.assertEqual("n/a", result.result)

    def test_validate_report_requires_schema_fields(self):
        errors = smoke_lib.validate_report({})
        self.assertTrue(any("schemaVersion" in e for e in errors))
        self.assertTrue(any("scenarios" in e for e in errors))

    def test_validate_report_accepts_minimal_good_report(self):
        preflight = smoke_lib.collect_preflight(ROOT, version="0.5.0", base="v0.4.1")
        scenarios = [
            smoke_lib.scenario_result(
                sid,
                name=sid,
                result="pass",
            )
            for sid in smoke_lib.REQUIRED_SCENARIO_IDS
        ]
        report = smoke_lib.build_report(
            preflight,
            scenarios,
            started_at=smoke_lib.utc_now_iso(),
        )
        errors = smoke_lib.validate_report(
            report, required_ids=smoke_lib.REQUIRED_SCENARIO_IDS
        )
        self.assertEqual([], errors, errors)
        self.assertEqual(1, report["schemaVersion"])
        self.assertEqual(preflight.sha, report["sha"])
        self.assertIn("displayMode", report["environment"])
        self.assertIn("dirty", report)

    def test_validate_report_rejects_hard_na_without_reason(self):
        preflight = smoke_lib.collect_preflight(ROOT, version="0.5.0")
        bad = smoke_lib.ScenarioResult(
            id="check",
            name="check",
            result="n/a",
            reason="",
            hard=True,
        )
        report = smoke_lib.build_report(
            preflight, [bad], started_at=smoke_lib.utc_now_iso()
        )
        # Bypass scenario_result coercion to simulate a hand-written report.
        report["scenarios"][0]["result"] = "n/a"
        report["scenarios"][0]["reason"] = ""
        errors = smoke_lib.validate_report(report)
        self.assertTrue(any("hard n/a requires" in e for e in errors), errors)

    def test_preflight_records_sha_and_dirty_flag(self):
        preflight = smoke_lib.collect_preflight(ROOT, version="0.5.0", base="v0.4.1")
        self.assertEqual(40, len(preflight.sha))
        self.assertTrue(preflight.sha_short)
        self.assertIsInstance(preflight.dirty, bool)
        self.assertEqual("0.5.0", preflight.version)
        self.assertEqual("v0.4.1", preflight.base)
        self.assertTrue(preflight.environment.display_mode)

    def test_markdown_table_includes_scenario_ids(self):
        rows = [
            smoke_lib.scenario_result("check", name="check gate", result="pass", seconds=3),
            smoke_lib.scenario_result(
                "junit-live",
                name="live junit",
                result="n/a",
                reason="no display",
            ),
        ]
        table = smoke_lib.markdown_table(rows)
        self.assertIn("| check |", table)
        self.assertIn("| junit-live |", table)
        self.assertIn("no display", table)

    def test_write_json_and_markdown_roundtrip(self):
        preflight = smoke_lib.collect_preflight(ROOT, version="0.5.0")
        scenarios = [
            smoke_lib.scenario_result("preflight", name="preflight", result="pass"),
            smoke_lib.scenario_result("check", name="check", result="pass"),
        ]
        report = smoke_lib.build_report(
            preflight, scenarios, started_at=smoke_lib.utc_now_iso()
        )
        with tempfile.TemporaryDirectory() as tmp:
            json_path = Path(tmp) / "release-smoke.json"
            md_path = Path(tmp) / "release-smoke.md"
            smoke_lib.write_json_report(json_path, report)
            smoke_lib.write_markdown_report(md_path, report)
            loaded = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual(1, loaded["schemaVersion"])
            self.assertEqual(preflight.sha, loaded["sha"])
            md = md_path.read_text(encoding="utf-8")
            self.assertIn("schemaVersion", md)
            self.assertIn("| preflight |", md)

    def test_hard_failures_lists_failed_ids(self):
        rows = [
            smoke_lib.scenario_result("check", name="c", result="pass"),
            smoke_lib.scenario_result("junit-live", name="j", result="fail", detail="exit 1"),
            smoke_lib.scenario_result(
                "soft", name="s", result="fail", hard=False, detail="ignored"
            ),
        ]
        self.assertEqual(["junit-live"], smoke_lib.hard_failures(rows))

    def test_run_command_timeout_kills_and_returns_124(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "sleep.log"
            code, detail, _ = smoke_lib.run_command(
                [sys.executable, "-c", "import time; time.sleep(30)"],
                cwd=ROOT,
                timeout=1,
                log_path=log,
            )
            self.assertEqual(124, code)
            self.assertIn("timeout", detail)
            self.assertTrue(log.is_file())

    def test_detect_display_mode_linux_xvfb(self):
        # Force Linux path without mutating real platform for other tests.
        old_display = os.environ.pop("DISPLAY", None)
        try:
            mode = smoke_lib.detect_display_mode("Linux")
            self.assertIn(mode, {"xvfb-auto", "no-display"})
        finally:
            if old_display is not None:
                os.environ["DISPLAY"] = old_display

    def test_gradle_ui_force_args_disable_cache_only_pass(self):
        args = smoke_lib.gradle_ui_force_args()
        self.assertIn("--rerun-tasks", args)
        self.assertIn("--no-build-cache", args)

    def test_release_smoke_script_wires_full_required_matrix(self):
        self.assertTrue(RELEASE_SMOKE.is_file())
        text = RELEASE_SMOKE.read_text(encoding="utf-8")
        self.assertIn("smoke_lib", text)
        self.assertIn("--version", text)
        self.assertIn("REQUIRED_SCENARIO_IDS", text)
        # Structural: every required stable ID is referenced by the Unix runner.
        for scenario_id in smoke_lib.REQUIRED_SCENARIO_IDS:
            self.assertIn(scenario_id, text, f"missing wired id {scenario_id}")
        # Force-UI flags and host recording tasks must stay wired.
        self.assertIn("gradle_ui_force_args", text)
        self.assertIn("runMacOsSckRegionSmoke", text)
        self.assertIn("runLinuxX11RecordingSmoke", text)
        self.assertIn("verifyMavenLocalPublication", text)
        self.assertIn("LaunchAndAttachIntegration", text)


class ReleaseSmokeHelpTest(unittest.TestCase):
    def test_help_exits_zero(self):
        result = subprocess.run(
            [sys.executable, str(RELEASE_SMOKE), "--help"],
            text=True,
            capture_output=True,
            timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--version", result.stdout)
        self.assertIn("--skip-maven-local", result.stdout)
        self.assertIn("--skip-recording", result.stdout)


class ReleaseSmokeHelperLogicTest(unittest.TestCase):
    """Drive real helper functions shipped in release-smoke.py (not reimplemented)."""

    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("release_smoke", RELEASE_SMOKE)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        # release-smoke inserts scripts/ on sys.path for smoke_lib.
        spec.loader.exec_module(module)
        cls.rs = module

    def test_maven_local_version_is_release_shaped_smoke_coord(self):
        self.assertEqual("0.5.0-rc.smoke", self.rs._maven_local_version("0.5.0"))

    def test_host_recording_task_per_os(self):
        self.assertEqual(
            ":recording:runMacOsSckRegionSmoke", self.rs._host_recording_task("Darwin")
        )
        self.assertEqual(
            ":recording:runLinuxX11RecordingSmoke", self.rs._host_recording_task("Linux")
        )
        self.assertIsNone(self.rs._host_recording_task("Windows"))

    def test_native_helper_layout_check_requires_executable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(RuntimeError) as ctx:
                self.rs._native_helper_layout_check(root, platform.system())
            self.assertTrue(
                "missing" in str(ctx.exception).lower()
                or "neither" in str(ctx.exception).lower(),
                ctx.exception,
            )

    def test_fresh_consumer_check_fails_when_jar_absent(self):
        with self.assertRaises(RuntimeError) as ctx:
            self.rs._fresh_consumer_check(ROOT, "0.0.0-does-not-exist-smoke")
        self.assertIn("missing", str(ctx.exception).lower())


if __name__ == "__main__":
    unittest.main()

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
import unittest.mock
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
    def fake_server(
        self,
        *,
        banner: bool = False,
        version: str = "0.5.0",
        tools: list[str] | None = None,
        lifecycle: bool = False,
    ) -> Path:
        directory = Path(tempfile.mkdtemp())
        script = directory / "fake.py"
        # Minimal tool surface for hard-pass without --attach-pid: list + detach + attach + tree.
        default_tools = ["list_processes", "detach", "attach", "tree"]
        tool_names = tools if tools is not None else default_tools
        tools_json = json.dumps([{"name": n} for n in tool_names])
        # When lifecycle=True, attach returns a session; tree succeeds once; detach ok then gone.
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\n"
            f"banner={banner!r}; version={version!r}\n"
            f"tools={tools_json}\n"
            f"lifecycle={lifecycle!r}\n"
            "session=None\n"
            "detached=False\n"
            "if banner: print('not-json', flush=True)\n"
            "for line in sys.stdin:\n"
            " r=json.loads(line); method=r.get('method')\n"
            " if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'spectre','version':version}}\n"
            " elif method=='tools/list': result={'tools':tools}\n"
            " elif method=='tools/call':\n"
            "  name=(r.get('params') or {}).get('name')\n"
            "  args=(r.get('params') or {}).get('arguments') or {}\n"
            "  if name=='list_processes': result={'content':[{'type':'text','text':'[]'}]}\n"
            "  elif name=='attach' and lifecycle:\n"
            "   session='sess-1'; detached=False\n"
            "   result={'content':[{'type':'text','text':json.dumps({'sessionId':session})}]}\n"
            "  elif name=='tree' and lifecycle:\n"
            "   if session and not detached: result={'content':[{'type':'text','text':'tree-ok'}]}\n"
            "   else: result={'isError':True,'content':[{'type':'text','text':'session not found'}]}\n"
            "  elif name=='detach':\n"
            "   sid=args.get('session_id')\n"
            "   if lifecycle and sid==session and not detached:\n"
            "    detached=True\n"
            "    result={'content':[{'type':'text','text':json.dumps({'sessionId':sid,'captureCount':0,'captureBytes':0,'capturePaths':[]})}]}\n"
            "   else: result={'isError':True,'content':[{'type':'text','text':'session not found'}]}\n"
            "  else: result={'content':[{'type':'text','text':'ok'}]}\n"
            " else: continue\n"
            " print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':result}), flush=True)\n"
        )
        script.chmod(0o755)
        return script

    def run_smoke(self, server: Path, *extra_args: str):
        # Launch the fake server via the same interpreter: Windows cannot exec a
        # shebang-only .py (WinError 193). Production smoke still passes a real
        # packaged spectre binary as the command.
        return subprocess.run(
            [
                sys.executable,
                str(MCP_SMOKE),
                "--expected-version",
                "0.5.0",
                *extra_args,
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
        self.assertIn("MCP SMOKE PASS", result.stdout)
        self.assertIn("tools+unknown-detach", result.stdout)

    def test_stdout_pollution_fails(self):
        result = self.run_smoke(self.fake_server(banner=True))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("non-JSON stdout", result.stderr)

    def test_wrong_version_fails(self):
        result = self.run_smoke(self.fake_server(version="0.1.0"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("server version", result.stderr)

    def test_missing_detach_fails(self):
        result = self.run_smoke(
            self.fake_server(tools=["list_processes", "attach", "tree"])
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("detach", result.stderr)

    def test_unknown_detach_must_be_is_error(self):
        directory = Path(tempfile.mkdtemp())
        script = directory / "silent-detach.py"
        # detach of unknown session silently succeeds — must fail closed.
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\n"
            "for line in sys.stdin:\n"
            " r=json.loads(line); method=r.get('method')\n"
            " if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'spectre','version':'0.5.0'}}\n"
            " elif method=='tools/list': result={'tools':[{'name':'list_processes'},{'name':'detach'},{'name':'attach'},{'name':'tree'}]}\n"
            " elif method=='tools/call':\n"
            "  result={'content':[{'type':'text','text':'ok'}]}\n"
            " else: continue\n"
            " print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':result}), flush=True)\n"
        )
        script.chmod(0o755)
        result = self.run_smoke(script)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("isError", result.stderr)

    def test_attach_pid_lifecycle_passes(self):
        result = self.run_smoke(
            self.fake_server(lifecycle=True),
            "--attach-pid",
            "12345",
            "--daemon-user",
            "mcp-smoke-test-user",
        )
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("attach/tree/detach/session-gone", result.stdout)


class SmokeLibSchemaTest(unittest.TestCase):
    def test_schema_version_constant(self):
        self.assertEqual(1, smoke_lib.SCHEMA_VERSION)

    def test_linux_toolchain_path_prepends_cargo_bin(self):
        with tempfile.TemporaryDirectory() as tmp:
            cargo_home = Path(tmp) / "cargo-home"
            cargo_bin = cargo_home / "bin"
            cargo_bin.mkdir(parents=True)
            environ = {"PATH": "/usr/bin", "CARGO_HOME": str(cargo_home)}
            result = smoke_lib.linux_toolchain_path(environ)
            parts = result.split(os.pathsep)
            self.assertEqual(str(cargo_bin), parts[0])
            self.assertIn("/usr/bin", parts)

    def test_linux_toolchain_path_does_not_duplicate_cargo_bin(self):
        with tempfile.TemporaryDirectory() as tmp:
            cargo_home = Path(tmp) / "cargo-home"
            cargo_bin = cargo_home / "bin"
            cargo_bin.mkdir(parents=True)
            already = os.pathsep.join([str(cargo_bin), "/usr/bin"])
            environ = {"PATH": already, "CARGO_HOME": str(cargo_home)}
            result = smoke_lib.linux_toolchain_path(environ)
            self.assertEqual(1, result.split(os.pathsep).count(str(cargo_bin)))

    def test_linux_toolchain_path_inherits_os_path_when_overlay_omits_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            cargo_home = Path(tmp) / "cargo-home"
            cargo_bin = cargo_home / "bin"
            cargo_bin.mkdir(parents=True)
            old_path = os.environ.get("PATH")
            # os.pathsep, not ":" — on Windows the hardcoded colon left PATH as a single
            # unsplittable entry and this assertion failed for the separator, not the behaviour.
            os.environ["PATH"] = os.pathsep.join(["/usr/bin", "/bin"])
            try:
                result = smoke_lib.linux_toolchain_path({"CARGO_HOME": str(cargo_home)})
            finally:
                if old_path is None:
                    del os.environ["PATH"]
                else:
                    os.environ["PATH"] = old_path
            self.assertIn("/usr/bin", result.split(os.pathsep))
            self.assertEqual(str(cargo_bin), result.split(os.pathsep)[0])

    def test_apply_linux_toolchain_path_mutates_environ(self):
        with tempfile.TemporaryDirectory() as tmp:
            cargo_home = Path(tmp) / "cargo-home"
            (cargo_home / "bin").mkdir(parents=True)
            environ = {"PATH": "/bin", "CARGO_HOME": str(cargo_home)}
            smoke_lib.apply_linux_toolchain_path(environ)
            self.assertTrue(
                environ["PATH"].startswith(str(cargo_home / "bin") + os.pathsep)
            )

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
            "portal-token-warmup",
            "pointer-move",
            "input-coord-contention",
            "input-coord-cancellation",
            "input-coord-quarantine",
            "input-coord-revoke",
            "input-coord-forced-recovery",
            "input-coord-junit-pertest",
        }
        self.assertEqual(expected, set(smoke_lib.REQUIRED_SCENARIO_IDS))

    def test_pointer_move_api_skip_reason_when_verbs_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            automator = (
                root
                / "core/src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt"
            )
            automator.parent.mkdir(parents=True)
            automator.write_text("class ComposeAutomator {\n    fun click(node: Any) {}\n}\n")
            reason = smoke_lib.pointer_move_api_skip_reason(root)
            self.assertIsNotNone(reason)
            self.assertIn("#433", reason)

    def test_pointer_move_api_skip_reason_when_verbs_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            automator = (
                root
                / "core/src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt"
            )
            automator.parent.mkdir(parents=True)
            automator.write_text(
                "class ComposeAutomator {\n"
                "    public suspend fun moveTo(node: Any) {}\n"
                "    public suspend fun moveBy(deltaX: Int, deltaY: Int) {}\n"
                "}\n"
            )
            self.assertIsNone(smoke_lib.pointer_move_api_skip_reason(root))

    def test_pointer_move_api_skip_reason_matches_this_checkout(self):
        automator = (
            ROOT / "core/src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt"
        )
        text = automator.read_text(encoding="utf-8")
        shipped = (
            smoke_lib._MOVE_TO_FUN.search(text) is not None
            and smoke_lib._MOVE_BY_FUN.search(text) is not None
        )
        reason = smoke_lib.pointer_move_api_skip_reason(ROOT)
        if shipped:
            self.assertIsNone(reason)
        else:
            self.assertIsNotNone(reason)
            self.assertIn("#433", reason)

    def test_assert_pointer_move_live_executed_rejects_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = (
                root / "sample-desktop" / "build" / "test-results" / "validationTest"
            )
            results.mkdir(parents=True)
            (results / "TEST-pointer.xml").write_text(
                '<?xml version="1.0"?>\n'
                '<testsuite name="PointerMoveLive" tests="1" failures="0" '
                'errors="0" skipped="1">\n'
                '  <testcase name="moveTo node hovers" classname="x">'
                "<skipped/></testcase>\n"
                "</testsuite>\n"
            )
            with self.assertRaises(RuntimeError) as raised:
                smoke_lib.assert_pointer_move_live_executed(root)
            self.assertIn("skipped", str(raised.exception))

    def test_input_coordination_smoke_skip_reason_present_on_this_checkout(self):
        # The experimental coordinator + isolation test surface exists on this tree, so the cells
        # are hard-runnable (no display needed) and must not be N/A.
        self.assertIsNone(smoke_lib.input_coordination_smoke_skip_reason(ROOT))

    def test_input_coordination_smoke_skip_reason_when_surface_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            reason = smoke_lib.input_coordination_smoke_skip_reason(Path(tmp))
            self.assertIsNotNone(reason)
            self.assertIn("re-scope the release gate", reason)

    def test_assert_junit_testcases_passed_rejects_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp)
            (results / "TEST-coord.xml").write_text(
                '<?xml version="1.0"?>\n'
                '<testsuite name="LocalCoordinatorServerTest" tests="1" failures="0" '
                'errors="0" skipped="1">\n'
                '  <testcase name="explicit force advances FIFO and reports unsafe takeover" '
                'classname="x"><skipped/></testcase>\n'
                "</testsuite>\n",
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError) as ctx:
                smoke_lib.assert_junit_testcases_passed(
                    results,
                    needles=["explicit force advances FIFO and reports unsafe takeover"],
                    cell="input-coord-forced-recovery",
                )
            self.assertIn("skipped", str(ctx.exception).lower())

    def test_assert_junit_testcases_passed_requires_every_needle(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp)
            (results / "TEST-coord.xml").write_text(
                '<?xml version="1.0"?>\n'
                '<testsuite name="LocalCoordinatorServerTest" tests="1" failures="0" '
                'errors="0" skipped="0">\n'
                '  <testcase name="two independent clients receive one desktop lease in FIFO '
                'order" classname="x" time="1.0"/>\n'
                "</testsuite>\n",
                encoding="utf-8",
            )
            # The FIFO needle is present, but the forked-coordinator needle is not: fail closed.
            with self.assertRaises(RuntimeError) as ctx:
                smoke_lib.assert_junit_testcases_passed(
                    results,
                    needles=[
                        "two independent clients receive one desktop lease in FIFO order",
                        "forked coordinator accepts a real client lease",
                    ],
                    cell="input-coord-contention",
                )
            self.assertIn("not found", str(ctx.exception).lower())

    def test_assert_junit_testcases_passed_accepts_executed_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp)
            (results / "TEST-coord.xml").write_text(
                '<?xml version="1.0"?>\n'
                '<testsuite name="LocalCoordinatorServerTest" tests="1" failures="0" '
                'errors="0" skipped="0">\n'
                '  <testcase name="exact-id revoke rejects stale observation and fences the '
                'actual holder" classname="x" time="1.0"/>\n'
                "</testsuite>\n",
                encoding="utf-8",
            )
            smoke_lib.assert_junit_testcases_passed(
                results,
                needles=["exact-id revoke rejects stale observation and fences the actual holder"],
                cell="input-coord-revoke",
            )

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
        self.assertIn("runWaylandPortalSmoke", text)
        self.assertIn("portal-token-warmup", text)
        self.assertIn("verifyMavenLocalPublication", text)
        self.assertIn("LaunchAndAttachIntegration", text)
        # #414: hard pass requires fixture e2e lifecycle gate, not tools/list alone.
        self.assertIn("assert_mcp_fixture_e2e_executed", text)
        self.assertIn("attach/op/detach", text)
        # Package must bake --version so MCP serverInfo matches strict stdio (not SNAPSHOT).
        self.assertIn("-PVERSION_NAME=", text)
        # #433: live pointer-move cell must stay fail-closed once moveTo/moveBy ship.
        self.assertIn("pointer_move_api_skip_reason", text)
        self.assertIn("*PointerMoveLive*", text)
        # #459: experimental input-coordination delta hard cells must drive the coordinator's own
        # deterministic + forked-process + JUnit-isolation tests, fail-closed on the JUnit XML.
        self.assertIn("input_coordination_smoke_skip_reason", text)
        self.assertIn("assert_junit_testcases_passed", text)
        self.assertIn(":input-coordinator-server:test", text)
        self.assertIn(":testing:test", text)
        self.assertIn("LocalCoordinatorServerTest", text)
        self.assertIn("CoordinatorProcessLauncherTest", text)
        self.assertIn("InputIsolationLifecycleTest", text)
        self.assertIn("explicit force advances FIFO and reports unsafe takeover", text)
        # Non-login SSH / xvfb-run must still see rustup cargo for helper rebuilds.
        self.assertIn("apply_linux_toolchain_path", text)
        # Nested buildSrc test must not start a daemon that --stops parent ./gradlew check.
        root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('"--no-daemon"', root_build)
        self.assertIn("buildSrc", root_build)

    def test_assert_mcp_fixture_e2e_executed_rejects_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = root / "cli" / "build" / "test-results" / "test"
            results.mkdir(parents=True)
            (results / "TEST-mcp.xml").write_text(
                '<?xml version="1.0"?>\n'
                '<testsuite name="DaemonFixture" tests="1" failures="0" errors="0" skipped="1">\n'
                '  <testcase name="MCP stdio drives a Compose fixture" classname="x">'
                "<skipped/></testcase>\n"
                "</testsuite>\n",
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError) as ctx:
                smoke_lib.assert_mcp_fixture_e2e_executed(root)
            self.assertIn("skipped", str(ctx.exception).lower())

    def test_assert_mcp_fixture_e2e_executed_accepts_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = root / "cli" / "build" / "test-results" / "test"
            results.mkdir(parents=True)
            (results / "TEST-mcp.xml").write_text(
                '<?xml version="1.0"?>\n'
                '<testsuite name="DaemonFixture" tests="1" failures="0" errors="0" skipped="0">\n'
                '  <testcase name="MCP stdio drives a Compose fixture" classname="x" time="1.0"/>\n'
                "</testsuite>\n",
                encoding="utf-8",
            )
            smoke_lib.assert_mcp_fixture_e2e_executed(root)


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
        self.assertIn("--preflight-only", result.stdout)

    @unittest.skipIf(
        platform.system() == "Windows",
        "Unix release-smoke entrypoint intentionally rejects Windows",
    )
    def test_preflight_only_emits_full_required_matrix_with_na_reason(self):
        """Drive the real entrypoint: --preflight-only must not invent PASS for hard cells."""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            result = subprocess.run(
                [
                    sys.executable,
                    str(RELEASE_SMOKE),
                    "--version",
                    "0.5.0",
                    "--base",
                    "v0.4.1",
                    "--preflight-only",
                    "--out-dir",
                    str(out),
                ],
                text=True,
                capture_output=True,
                timeout=30,
            )
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertIn("PREFLIGHT-ONLY", result.stdout)
            report_path = out / "release-smoke.json"
            self.assertTrue(report_path.is_file(), result.stdout)
            report = json.loads(report_path.read_text(encoding="utf-8"))
            errors = smoke_lib.validate_report(
                report, required_ids=smoke_lib.REQUIRED_SCENARIO_IDS
            )
            self.assertEqual([], errors, errors)
            by_id = {row["id"]: row for row in report["scenarios"]}
            self.assertEqual("pass", by_id["preflight"]["result"])
            for sid in smoke_lib.REQUIRED_SCENARIO_IDS:
                if sid == "preflight":
                    continue
                self.assertEqual("n/a", by_id[sid]["result"], sid)
                self.assertIn("preflight-only", by_id[sid]["reason"])
            # Must not claim a full hard GO.
            self.assertNotIn("ALL HARD SCENARIOS PASSED", result.stdout)

    def test_preflight_only_does_not_stop_gradle_daemon_on_linux(self):
        """--preflight-only is invoked from :check; gradlew --stop kills that daemon."""
        spec = importlib.util.spec_from_file_location(
            "release_smoke_preflight_stop", RELEASE_SMOKE
        )
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        stop_cmds: list[list[str]] = []
        real_run = module.subprocess.run

        def wrapped(*args, **kwargs):
            cmd = args[0] if args else kwargs.get("args")
            if isinstance(cmd, (list, tuple)) and "--stop" in cmd:
                stop_cmds.append(list(cmd))
                return subprocess.CompletedProcess(cmd, 0)
            return real_run(*args, **kwargs)

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            with (
                unittest.mock.patch.object(module.platform, "system", return_value="Linux"),
                unittest.mock.patch.object(module.subprocess, "run", side_effect=wrapped),
            ):
                code = module.main(
                    [
                        "--version",
                        "0.5.0",
                        "--base",
                        "v0.4.1",
                        "--preflight-only",
                        "--out-dir",
                        str(out),
                    ]
                )
        self.assertEqual(0, code)
        self.assertEqual(
            [],
            stop_cmds,
            "release-smoke --preflight-only must not invoke gradlew --stop "
            "(verifyReleaseSmokeScripts runs this under ./gradlew check)",
        )


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
        self.assertEqual(
            ":recording:runWaylandPortalSmoke",
            self.rs._host_recording_task("Linux", wayland_portal=True),
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

    def test_linux_portal_session_is_detected_from_wayland_socket(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime = Path(tmp) / "runtime"
            runtime.mkdir()
            (runtime / "wayland-0").touch()
            self.assertTrue(
                smoke_lib.is_linux_wayland_portal_session(
                    {
                        "WAYLAND_DISPLAY": "wayland-0",
                        "XDG_RUNTIME_DIR": str(runtime),
                    }
                )
            )
            self.assertFalse(
                smoke_lib.is_linux_wayland_portal_session(
                    {
                        "DISPLAY": ":99",
                        "XDG_SESSION_TYPE": "x11",
                    }
                )
            )
            self.assertFalse(
                smoke_lib.is_linux_wayland_portal_session(
                    {
                        "DISPLAY": ":99",
                        "XDG_SESSION_TYPE": "wayland",
                        "WAYLAND_DISPLAY": "wayland-0",
                        "XDG_RUNTIME_DIR": str(runtime),
                    },
                    display_is_pure_x11=lambda _display: True,
                )
            )
            self.assertTrue(
                smoke_lib.is_linux_wayland_portal_session(
                    {
                        "DISPLAY": ":0",
                        "XDG_SESSION_TYPE": "wayland",
                        "WAYLAND_DISPLAY": "wayland-0",
                        "XDG_RUNTIME_DIR": str(runtime),
                    },
                    display_is_pure_x11=lambda _display: False,
                )
            )
            self.assertFalse(
                smoke_lib.is_linux_wayland_portal_session(
                    {
                        "SPECTRE_CAPTURE_BACKEND": "x11",
                        "WAYLAND_DISPLAY": "wayland-0",
                        "XDG_RUNTIME_DIR": str(runtime),
                    }
                )
            )

    def test_prepare_linux_portal_token_env_pins_helper_and_token_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            helper = smoke_lib.linux_wayland_helper_candidates(root)[0]
            helper.parent.mkdir(parents=True)
            helper.write_text("#!/bin/sh\n", encoding="utf-8")
            helper.chmod(0o755)
            out_dir = root / "build" / "smoke"
            env = smoke_lib.prepare_linux_portal_token_env(root, out_dir)
            token_dir = Path(env["SPECTRE_WAYLAND_RESTORE_TOKEN_DIR"])
            self.assertEqual(out_dir / "wayland-restore-tokens", token_dir)
            self.assertTrue(token_dir.is_dir())
            self.assertEqual(str(helper), env["SPECTRE_WAYLAND_HELPER"])
            if os.name == "nt":
                self.assertTrue(token_dir.is_dir())
            else:
                self.assertEqual("0700", oct(token_dir.stat().st_mode)[-4:])

    def test_prepare_linux_portal_token_env_without_helper_omits_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_dir = root / "build" / "smoke"
            env = smoke_lib.prepare_linux_portal_token_env(root, out_dir)
            self.assertEqual(
                str(out_dir / "wayland-restore-tokens"),
                env["SPECTRE_WAYLAND_RESTORE_TOKEN_DIR"],
            )
            self.assertNotIn("SPECTRE_WAYLAND_HELPER", env)

    def test_prepare_linux_portal_token_env_clears_path_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_dir = root / "build" / "smoke"
            env = smoke_lib.prepare_linux_portal_token_env(root, out_dir)
            self.assertEqual("", env.get("SPECTRE_WAYLAND_RESTORE_TOKEN_PATH"))

    def test_packaged_cli_env_drops_helper_override(self):
        env = self.rs._packaged_cli_portal_env(
            {
                "SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": "/tmp/tokens",
                "SPECTRE_WAYLAND_HELPER": "/tmp/helper",
            }
        )
        self.assertEqual("/tmp/tokens", env["SPECTRE_WAYLAND_RESTORE_TOKEN_DIR"])
        self.assertEqual("", env["SPECTRE_WAYLAND_HELPER"])

    def test_wayland_portal_ui_prefix_is_empty_only_for_seat_bound_cells(self):
        self.assertEqual([], self.rs._ui_prefix("Linux", wayland_portal=True))
        self.assertIsInstance(self.rs._ui_prefix("Linux", wayland_portal=False), list)

    def test_missing_xvfb_run_fails_robot_cells_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            previous_path = os.environ.get("PATH", "")
            os.environ["PATH"] = tmp
            try:
                self.assertEqual([], smoke_lib.robot_xvfb_prefix("Linux"))
                reason = smoke_lib.robot_xvfb_unavailable_reason("Linux")
                self.assertIsNotNone(reason)
                self.assertIn("xvfb-run", reason or "")
            finally:
                os.environ["PATH"] = previous_path

    def test_robot_cells_keep_xvfb_after_wayland_portal_warmup(self):
        # Helper restore tokens do not cover JBR Robot / Remote Desktop. After
        # portal warmup, check/attach/corpus must still use xvfb-run or each JVM
        # pops a new compositor dialog on the seat. Ignore an inherited DISPLAY.
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "xvfb-run"
            fake.write_text("#!/bin/sh\n", encoding="utf-8")
            fake.chmod(0o755)
            previous_path = os.environ.get("PATH", "")
            previous_display = os.environ.get("DISPLAY")
            os.environ["PATH"] = f"{tmp}{os.pathsep}{previous_path}"
            os.environ["DISPLAY"] = ":0"
            try:
                self.assertEqual(
                    ["xvfb-run", "-a"],
                    self.rs._robot_ui_prefix("Linux", wayland_portal=True),
                )
                self.assertEqual(
                    ["xvfb-run", "-a"],
                    self.rs._robot_ui_prefix("Linux", wayland_portal=False),
                )
                self.assertEqual([], smoke_lib.xvfb_prefix("Linux"))
                robot_env = self.rs._robot_env(
                    {
                        "SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": "/tmp/tokens",
                        "WAYLAND_DISPLAY": "wayland-0",
                        "XDG_SESSION_TYPE": "wayland",
                    }
                )
                self.assertEqual("x11", robot_env["SPECTRE_CAPTURE_BACKEND"])
                self.assertEqual("", robot_env["WAYLAND_DISPLAY"])
                self.assertEqual("x11", robot_env["XDG_SESSION_TYPE"])
                self.assertEqual(
                    "/tmp/tokens", robot_env["SPECTRE_WAYLAND_RESTORE_TOKEN_DIR"]
                )
            finally:
                os.environ["PATH"] = previous_path
                if previous_display is None:
                    os.environ.pop("DISPLAY", None)
                else:
                    os.environ["DISPLAY"] = previous_display

    def test_assert_linux_portal_tokens_captured_requires_restore_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            token_dir = Path(tmp) / "tokens"
            token_dir.mkdir()
            stale = token_dir / "wayland-screencast-restore-token-window-hidden"
            stale.write_text("old\n", encoding="utf-8")
            with self.assertRaises(RuntimeError) as ctx:
                smoke_lib.assert_linux_portal_tokens_captured(
                    {"SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": str(token_dir)}
                )
            self.assertIn("monitor-embedded", str(ctx.exception).lower())
            target = token_dir / "wayland-screencast-restore-token-monitor-embedded"
            target.write_text("token-abc\n", encoding="utf-8")
            before = target.stat().st_mtime_ns
            with self.assertRaises(RuntimeError):
                smoke_lib.assert_linux_portal_tokens_captured(
                    {"SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": str(token_dir)},
                    expected_mtime_ns=before + 1,
                )
            smoke_lib.assert_linux_portal_tokens_captured(
                {"SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": str(token_dir)},
                expected_mtime_ns=before,
            )

    def test_portal_token_warmup_is_na_on_non_wayland(self):
        result = smoke_lib.portal_token_warmup_skip_reason(
            {"DISPLAY": ":99", "XDG_SESSION_TYPE": "x11"},
            system="Linux",
        )
        self.assertIsNotNone(result)
        self.assertIn("Wayland", result or "")
        darwin = smoke_lib.portal_token_warmup_skip_reason(system="Darwin")
        self.assertIsNotNone(darwin)
        self.assertIn("does not use", darwin or "")


class DocsAndSchemaPolicyTest(unittest.TestCase):
    """Docs must document extension + schemaVersion policy so operators need no chat history."""

    def test_release_smoke_docs_extension_and_schema_policy(self):
        docs = (ROOT / "docs" / "RELEASE-SMOKE.md").read_text(encoding="utf-8")
        self.assertIn("Adding a scenario ID", docs)
        self.assertIn("REQUIRED_SCENARIO_IDS", docs)
        self.assertIn("schemaVersion", docs)
        self.assertIn("bump", docs.lower())
        self.assertIn("--preflight-only", docs)
        self.assertIn("preflight-only", docs)
        # #459: the experimental input-coordination delta cells are reusable scenario IDs, so the
        # stable-ID table / gate must document them (not leave the commands only in chat).
        for coordination_id in (
            "input-coord-contention",
            "input-coord-cancellation",
            "input-coord-quarantine",
            "input-coord-revoke",
            "input-coord-forced-recovery",
            "input-coord-junit-pertest",
        ):
            self.assertIn(coordination_id, docs)

    def test_validate_report_rejects_missing_required_ids(self):
        preflight = smoke_lib.collect_preflight(ROOT, version="0.5.0")
        partial = [
            smoke_lib.scenario_result("preflight", name="p", result="pass"),
            smoke_lib.scenario_result("check", name="c", result="pass"),
        ]
        report = smoke_lib.build_report(
            preflight, partial, started_at=smoke_lib.utc_now_iso()
        )
        errors = smoke_lib.validate_report(
            report, required_ids=smoke_lib.REQUIRED_SCENARIO_IDS
        )
        self.assertTrue(any("missing required scenario ids" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main()

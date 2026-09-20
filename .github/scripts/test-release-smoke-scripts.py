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
import time
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
            "macos-tcc",
            "portal-token-warmup",
            "pointer-move",
            "input-coord-contention",
            "input-coord-cancellation",
            "input-coord-quarantine",
            "input-coord-revoke",
            "input-coord-forced-recovery",
            "input-coord-junit-pertest",
            "input-coord-headed-robot",
        }
        self.assertEqual(expected, set(smoke_lib.REQUIRED_SCENARIO_IDS))
        self.assertLess(
            smoke_lib.REQUIRED_SCENARIO_IDS.index("macos-tcc"),
            smoke_lib.REQUIRED_SCENARIO_IDS.index("check"),
            "macos-tcc must run before ./gradlew check so missing TCC fails in seconds",
        )

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

    def test_coordination_cross_process_and_parallel_proofs_exist(self):
        """The cells must be backed by real multi-JVM / concurrent tests, not just docs claims."""
        contention = (
            ROOT
            / "input-coordinator-server/src/test/kotlin/dev/sebastiano/spectre/input/server"
            / "TwoClientJvmContentionTest.kt"
        )
        probe = contention.with_name("TwoClientJvmContentionProbe.kt")
        parallel = (
            ROOT
            / "testing/src/test/kotlin/dev/sebastiano/spectre/testing"
            / "ParallelPerTestInputIsolationTest.kt"
        )
        for path in (contention, probe, parallel):
            self.assertTrue(path.is_file(), f"missing coordination gate proof: {path}")
        # The contention proof must genuinely fork a second client process.
        self.assertIn("ProcessBuilder", contention.read_text(encoding="utf-8"))
        # The parallel proof must drive concurrent invocations against a real coordinator.
        parallel_text = parallel.read_text(encoding="utf-8")
        self.assertIn("LocalCoordinatorServer", parallel_text)
        self.assertIn("Executors", parallel_text)

    def test_headed_robot_cell_blocks_without_evidence_or_automated_run(self):
        """A reasoned n/a would let the runner print ALL HARD SCENARIOS PASSED; it must fail."""
        missing = smoke_lib.headed_robot_cell(None)
        self.assertEqual("fail", missing.result)
        self.assertIn("was NOT recorded", missing.detail)
        # The actual gate: hard_failures() ignores a reasoned n/a, so this must be a fail row.
        self.assertEqual(
            ["input-coord-headed-robot"], smoke_lib.hard_failures([missing])
        )
        for blank in ("", "   "):
            self.assertEqual("fail", smoke_lib.headed_robot_cell(blank).result)

    def test_headed_robot_cell_passes_on_recorded_evidence(self):
        recorded = smoke_lib.headed_robot_cell("ran on Mattone; run URL ...")
        self.assertEqual("pass", recorded.result)
        self.assertIn("operator evidence: ran on Mattone", recorded.detail)
        self.assertEqual([], smoke_lib.hard_failures([recorded]))

    def test_headed_robot_cell_passes_on_the_automated_proof_alone(self):
        """#491: a host that can run the e2e needs no human signature."""
        automated = smoke_lib.scenario_result(
            smoke_lib.HEADED_ROBOT_SCENARIO_ID,
            name=smoke_lib.HEADED_ROBOT_NAME,
            result="pass",
            seconds=41,
        )
        cell = smoke_lib.headed_robot_cell(None, automated=automated)
        self.assertEqual("pass", cell.result)
        self.assertEqual(41, cell.seconds)
        self.assertIn(smoke_lib.HEADED_ROBOT_GRADLE_TASK, cell.detail)
        self.assertEqual([], smoke_lib.hard_failures([cell]))

    def test_operator_evidence_cannot_paper_over_a_red_automated_run(self):
        """The one precedence that matters: an observed failure outranks a signature."""
        automated = smoke_lib.scenario_result(
            smoke_lib.HEADED_ROBOT_SCENARIO_ID,
            name=smoke_lib.HEADED_ROBOT_NAME,
            result="fail",
            detail="two headed Robot JVMs interleaved their keystrokes",
        )
        cell = smoke_lib.headed_robot_cell("ran on Mattone; looked fine", automated=automated)
        self.assertEqual("fail", cell.result)
        self.assertIn("interleaved", cell.detail)
        self.assertEqual(
            ["input-coord-headed-robot"], smoke_lib.hard_failures([cell])
        )

    def test_a_host_that_cannot_run_the_e2e_still_fails_without_evidence(self):
        """The escape hatch is for the operator, not for the absence of one."""
        reason = "no xvfb-run on this host"
        blocked = smoke_lib.headed_robot_cell(None, unavailable_reason=reason)
        self.assertEqual("fail", blocked.result)
        self.assertIn(reason, blocked.detail)
        self.assertEqual(
            ["input-coord-headed-robot"], smoke_lib.hard_failures([blocked])
        )
        # ...and with evidence it passes, still saying why the automation did not run.
        signed = smoke_lib.headed_robot_cell("ran on Mattone", unavailable_reason=reason)
        self.assertEqual("pass", signed.result)
        self.assertIn(reason, signed.detail)
        self.assertIn("operator evidence: ran on Mattone", signed.detail)

    def test_headed_robot_proof_sources_exist_on_this_checkout(self):
        self.assertIsNone(smoke_lib.headed_robot_missing_surface(ROOT))

    def test_a_deleted_headed_proof_fails_rather_than_falling_back_to_a_signature(self):
        with tempfile.TemporaryDirectory() as tmp:
            detail = smoke_lib.headed_robot_missing_surface(Path(tmp))
            self.assertIsNotNone(detail)
            self.assertIn("failure, not a skip", detail)
            cell = smoke_lib.headed_robot_cell("ran on Mattone", missing_surface=detail)
            self.assertEqual("fail", cell.result)
            self.assertEqual(
                ["input-coord-headed-robot"], smoke_lib.hard_failures([cell])
            )

    def test_headed_robot_gradle_task_and_testcase_match_the_kotlin_proof(self):
        """The XML needle is how the cell proves the e2e ran; a rename must break here."""
        test_source = ROOT / smoke_lib.HEADED_ROBOT_TEST_SOURCE
        text = test_source.read_text(encoding="utf-8")
        self.assertIn(smoke_lib.HEADED_ROBOT_TESTCASE, text)
        # Two forked probe JVMs, released together, each with a Required-policy real Robot.
        probe = (ROOT / smoke_lib.HEADED_ROBOT_PROBE_SOURCE).read_text(encoding="utf-8")
        self.assertIn("RobotDriver(InputLeasePolicy.Required)", probe)
        self.assertIn("ProcessBuilder", text)
        build_script = (ROOT / "sample-desktop" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(
            smoke_lib.HEADED_ROBOT_GRADLE_TASK.rsplit(":", maxsplit=1)[-1], build_script
        )

    def test_input_coordination_surface_present_on_this_checkout(self):
        # The experimental coordinator + isolation proofs exist on this tree, so the cells are
        # hard-runnable (no display needed) and must actually execute.
        self.assertIsNone(smoke_lib.input_coordination_missing_surface(ROOT))

    def test_missing_coordination_surface_fails_rather_than_skips(self):
        """A deleted or renamed proof must not turn six hard cells green by omission."""
        with tempfile.TemporaryDirectory() as tmp:
            detail = smoke_lib.input_coordination_missing_surface(Path(tmp))
            self.assertIsNotNone(detail)
            self.assertIn("failure, not a skip", detail)
            self.assertIn("re-scope the release", detail)
            # The gate that matters: a reasoned n/a is ignored by hard_failures(), so the runner
            # must record a missing proof as a fail row instead.
            failed = smoke_lib.scenario_result(
                "input-coord-contention",
                name="x",
                result=smoke_lib.RESULT_FAIL,
                detail=detail,
            )
            self.assertEqual(["input-coord-contention"], smoke_lib.hard_failures([failed]))
            ignored = smoke_lib.scenario_result(
                "input-coord-contention", name="x", result="n/a", reason=detail
            )
            self.assertEqual(
                [],
                smoke_lib.hard_failures([ignored]),
                "a reasoned n/a is ignored by hard_failures — exactly why a missing proof "
                "must not be recorded as one",
            )

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
        self.assertIn("input_coordination_missing_surface", text)
        self.assertIn("assert_junit_testcases_passed", text)
        self.assertIn(":input-coordinator-server:test", text)
        self.assertIn(":testing:test", text)
        self.assertIn("LocalCoordinatorServerTest", text)
        self.assertIn("CoordinatorProcessLauncherTest", text)
        self.assertIn("InputIsolationLifecycleTest", text)
        self.assertIn("explicit force advances FIFO and reports unsafe takeover", text)
        # The two cells whose gate bullets need cross-process / concurrent proof must drive the
        # tests that actually supply it, not only the single-JVM sequential ones.
        self.assertIn("TwoClientJvmContentionTest", text)
        self.assertIn(
            "two independent client JVMs never hold the desktop lease at the same time", text
        )
        self.assertIn("ParallelPerTestInputIsolationTest", text)
        self.assertIn(
            "concurrent per-test invocations never hold the desktop lease at the same time", text
        )
        # Each half of the per-test bullet is gated by name. Gating only the class would let the
        # evidence-capture method be removed or renamed while the cell still reported pass.
        self.assertIn(
            "the per-test lease is still held while failure evidence is captured", text
        )
        # The headed cell is never satisfiable by the automated coordinator cells above, which
        # pass headless. It is driven by its own Robot e2e where the host can run one, and the
        # operator flag stays as the escape hatch for hosts that cannot (#491).
        self.assertIn("input-coord-headed-robot", text)
        self.assertIn("--headed-robot-evidence", text)
        self.assertIn("headed_robot_cell", text)
        self.assertIn("HEADED_ROBOT_GRADLE_TASK", text)
        self.assertIn("headed_robot_missing_surface", text)
        # Robot cells must leave the compositor seat, so the headed cell uses the same xvfb
        # wrapper and forced-X11 env as junit-live -- not the plain coordination scenario_env.
        headed = text[text.index("--- headed two-JVM Robot contention") :]
        headed = headed[: headed.index("# --- agent attach")]
        self.assertIn("robot_prefix", headed)
        self.assertIn("_robot_env(", headed)
        self.assertIn("robot_xvfb_unavailable_reason", headed)
        self.assertIn("assert_junit_testcases_passed", headed)
        # Non-login SSH / xvfb-run must still see rustup cargo for helper rebuilds.
        self.assertIn("apply_linux_toolchain_path", text)
        # #502: macos-tcc must refresh a stale runtime helper after an unknown probe.
        self.assertIn("refresh_helper", text)
        self.assertIn("refresh=True", text)
        self.assertIn("probe_macos_wrapping_screen_recording", text)
        self.assertIn("wrapping_screen_recording_probe", text)
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

    def test_preflight_only_does_not_stop_gradle_daemon_on_macos(self):
        spec = importlib.util.spec_from_file_location(
            "release_smoke_preflight_stop_macos", RELEASE_SMOKE
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
                unittest.mock.patch.object(module.platform, "system", return_value="Darwin"),
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
        self.assertEqual([], stop_cmds)


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
            self.assertEqual(str(out_dir / "wayland-session"), env["SPECTRE_WAYLAND_SESSION_DIR"])
            self.assertTrue(Path(env["SPECTRE_WAYLAND_SESSION_DIR"]).is_dir())
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
            self.assertIn("rd-monitor-embedded", str(ctx.exception).lower())
            target = token_dir / "wayland-rd-restore-token-rd-monitor-embedded"
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
        self.assertIn("`macos-tcc`", docs)
        self.assertIn("fail-closed", docs.lower())
        self.assertIn("Accessibility", docs)
        self.assertIn("Screen Recording", docs)
        self.assertIn("./gradlew --stop", docs)
        self.assertIn("Application Support", docs)
        self.assertIn("SPECTRE_SCREENCAPTURE_HELPER", docs)
        self.assertIn("stale cached helper", docs)
        self.assertIn("fingerprint", docs)
        self.assertIn("absolute path", docs)
        self.assertIn("IOConsoleLocked", docs)
        self.assertIn("Robot", docs)
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

    def test_release_smoke_docs_say_where_the_headed_cell_is_automated(self):
        """#491: an operator must be able to tell, from this page, whether they still sign."""
        docs = (ROOT / "docs" / "RELEASE-SMOKE.md").read_text(encoding="utf-8")
        self.assertIn("input-coord-headed-robot", docs)
        self.assertIn(smoke_lib.HEADED_ROBOT_GRADLE_TASK, docs)
        # The escape hatch has to stay documented, and so does the one host that still needs it.
        self.assertIn("--headed-robot-evidence", docs)
        self.assertIn("-HeadedRobotEvidence", docs)
        self.assertIn("Windows SSH", docs)
        # ...as does the precedence, which is the part a reader would otherwise guess wrong.
        self.assertIn("outranks an unverifiable note", docs)

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


class MacOsTccPreflightTest(unittest.TestCase):
    """#502: release-smoke must fail closed on missing macOS TCC before ./gradlew check."""

    def test_skip_reason_on_non_darwin(self):
        linux = smoke_lib.macos_tcc_skip_reason(system="Linux")
        self.assertIsNotNone(linux)
        self.assertIn("does not use", linux or "")
        windows = smoke_lib.macos_tcc_skip_reason(system="Windows")
        self.assertIsNotNone(windows)
        self.assertIn("Windows", windows or "")

    def test_skip_reason_none_on_darwin(self):
        self.assertIsNone(smoke_lib.macos_tcc_skip_reason(system="Darwin"))

    def test_evaluate_granted_is_silent(self):
        smoke_lib.evaluate_macos_tcc(
            accessibility=smoke_lib.TCC_GRANTED,
            screen_recording=smoke_lib.TCC_GRANTED,
        )

    def test_evaluate_not_applicable_is_silent(self):
        smoke_lib.evaluate_macos_tcc(
            accessibility=smoke_lib.TCC_NOT_APPLICABLE,
            screen_recording=smoke_lib.TCC_NOT_APPLICABLE,
        )

    def test_evaluate_denied_accessibility_names_grant_and_relaunch(self):
        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.evaluate_macos_tcc(
                accessibility=smoke_lib.TCC_DENIED,
                screen_recording=smoke_lib.TCC_GRANTED,
            )
        message = str(raised.exception)
        self.assertIn("Accessibility", message)
        self.assertIn("Privacy & Security", message)
        self.assertTrue(
            "wrapping" in message or "launching" in message or "parent" in message,
            message,
        )
        self.assertTrue(
            "relaunch" in message.lower() or "quit" in message.lower(),
            message,
        )
        self.assertIn("./gradlew --stop", message)
        self.assertNotIn("Spectre Capture Helper", message)

    def test_evaluate_denied_screen_recording_names_helper_not_wrapping_app(self):
        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.evaluate_macos_tcc(
                accessibility=smoke_lib.TCC_GRANTED,
                screen_recording=smoke_lib.TCC_DENIED,
            )
        message = str(raised.exception)
        self.assertTrue(
            "Screen Recording" in message or "Screen & System Audio Recording" in message,
            message,
        )
        self.assertIn("Privacy & Security", message)
        self.assertIn("Spectre Capture Helper", message)
        self.assertIn("SpectreCaptureHelper.app", message)
        self.assertIn("not the wrapping Terminal/IDE", message)
        self.assertNotIn("Grant System Settings → Privacy & Security → Screen & System Audio Recording to the wrapping app", message)

    def test_evaluate_unknown_is_fail_closed(self):
        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.evaluate_macos_tcc(
                accessibility=smoke_lib.TCC_UNKNOWN,
                screen_recording=smoke_lib.TCC_GRANTED,
            )
        message = str(raised.exception)
        self.assertIn("Accessibility", message)
        self.assertTrue(
            "unknown" in message.lower() or "could not" in message.lower(),
            message,
        )
        self.assertIn("./gradlew --stop", message)

        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.evaluate_macos_tcc(
                accessibility=smoke_lib.TCC_GRANTED,
                screen_recording=smoke_lib.TCC_UNKNOWN,
            )
        screen = str(raised.exception)
        self.assertTrue(
            "Screen Recording" in screen or "Screen & System Audio Recording" in screen
        )
        self.assertIn("Spectre Capture Helper", screen)
        self.assertIn(":recording:assembleScreenCaptureKitHelper", screen)
        self.assertIn("not the wrapping Terminal/IDE", screen)

    def test_evaluate_locked_screen_recording_fails(self):
        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.evaluate_macos_tcc(
                accessibility=smoke_lib.TCC_GRANTED,
                screen_recording=smoke_lib.TCC_LOCKED,
            )
        message = str(raised.exception).lower()
        self.assertIn("locked", message)
        self.assertIn("unlock", message)

    def test_console_lock_status_matches_macos_tcc_guard(self):
        self.assertTrue(smoke_lib.macos_console_lock_status('"IOConsoleLocked" = Yes'))
        self.assertFalse(smoke_lib.macos_console_lock_status('"IOConsoleLocked" = No'))
        self.assertIsNone(smoke_lib.macos_console_lock_status('"IOConsoleUsers" = ()'))

    def test_helper_granted_is_locked_when_console_is_locked(self):
        status = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (0, '{"granted": true}\n'),
            console_locked_probe=lambda: True,
        )
        self.assertEqual(smoke_lib.TCC_LOCKED, status)

    def test_evaluate_denied_wrapping_screen_recording_names_robot_app(self):
        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.evaluate_macos_tcc(
                accessibility=smoke_lib.TCC_GRANTED,
                screen_recording=smoke_lib.TCC_GRANTED,
                wrapping_screen_recording=smoke_lib.TCC_DENIED,
            )
        message = str(raised.exception)
        self.assertIn("wrapping", message.lower())
        self.assertIn("Screen Recording", message)
        self.assertIn("Robot", message)

    def test_wrapping_screen_recording_probe_matches_robot_pixels(self):
        granted = smoke_lib.interpret_wrapping_screen_recording_pixels(
            [0, 0, 0, 0x0000FF], width=2, height=2
        )
        self.assertEqual(smoke_lib.TCC_GRANTED, granted)
        denied = smoke_lib.interpret_wrapping_screen_recording_pixels(
            [0, 0, 0, 0], width=2, height=2
        )
        self.assertEqual(smoke_lib.TCC_DENIED, denied)
        unknown = smoke_lib.interpret_wrapping_screen_recording_pixels(
            [0xFFFFFF], width=1, height=1
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, unknown)

    def test_accessibility_probe_matches_macos_tcc_guard_semantics(self):
        granted = smoke_lib.probe_macos_accessibility(
            runner=lambda: (0, "Finder\n")
        )
        self.assertEqual(smoke_lib.TCC_GRANTED, granted)

        denied = smoke_lib.probe_macos_accessibility(
            runner=lambda: (1, "osascript: not allowed assistive access")
        )
        self.assertEqual(smoke_lib.TCC_DENIED, denied)

        unknown_automation = smoke_lib.probe_macos_accessibility(
            runner=lambda: (1, "AppleEvent handler failed (-1743)")
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, unknown_automation)

        unknown_blank = smoke_lib.probe_macos_accessibility(runner=lambda: (0, "  \n"))
        self.assertEqual(smoke_lib.TCC_UNKNOWN, unknown_blank)

        unknown_missing = smoke_lib.probe_macos_accessibility(runner=lambda: None)
        self.assertEqual(smoke_lib.TCC_UNKNOWN, unknown_missing)

    def test_screen_recording_probe_parses_helper_preflight_json(self):
        granted = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (
                0,
                '{"granted": true, "api": "CGPreflightScreenCaptureAccess"}\n',
            )
        )
        self.assertEqual(smoke_lib.TCC_GRANTED, granted)

        denied = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (
                6,
                '{"granted": false, "guidance": "Grant Screen Recording"}\n',
            )
        )
        self.assertEqual(smoke_lib.TCC_DENIED, denied)

        unknown = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (1, "not-json\n")
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, unknown)

        granted_nonzero = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (1, '{"granted": true}\n')
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, granted_nonzero)

        granted_denied_exit = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (6, '{"granted": true}\n')
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, granted_denied_exit)

        denied_ok_exit = smoke_lib.probe_macos_screen_recording(
            runner=lambda: (0, '{"granted": false}\n')
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, denied_ok_exit)

        warning_then_granted = smoke_lib.interpret_screencapture_preflight(
            0, "helper warning\n{\"granted\": true}\n"
        )
        self.assertEqual(smoke_lib.TCC_UNKNOWN, warning_then_granted)
        leading_blank = smoke_lib.interpret_screencapture_preflight(
            0, "\n  \n{\"granted\": true}\n"
        )
        self.assertEqual(smoke_lib.TCC_GRANTED, leading_blank)

    def test_screen_recording_probe_never_requests_or_reads_tcc_db(self):
        seen: list[list[str]] = []

        def runner(argv: list[str]) -> tuple[int, str]:
            seen.append(argv)
            return 0, '{"granted": true}\n'

        status = smoke_lib.probe_macos_screen_recording(
            helper_path=Path("/tmp/spectre-screencapture"),
            invoke_helper=runner,
        )
        self.assertEqual(smoke_lib.TCC_GRANTED, status)
        self.assertEqual(1, len(seen))
        self.assertIn("--mode", seen[0])
        self.assertIn("preflight", seen[0])
        joined = " ".join(seen[0])
        self.assertNotIn("request", joined)
        self.assertNotIn("guide-permissions", joined)
        self.assertNotIn("TCC.db", joined)

    def test_ensure_assembles_before_accepting_matching_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            runtime.parent.mkdir(parents=True)
            runtime.write_text("#!/bin/sh\nstale\n", encoding="utf-8")
            runtime.chmod(0o755)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\nstale\n", encoding="utf-8")
            staged.chmod(0o755)
            calls: list[int] = []

            def assemble() -> int:
                staged.write_text("#!/bin/sh\ncurrent-sha\n", encoding="utf-8")
                staged.chmod(0o755)
                calls.append(1)
                return 0

            found = smoke_lib.ensure_macos_screencapture_helper(
                root, assemble=assemble, home=home
            )
            self.assertEqual(runtime, found)
            self.assertEqual([1], calls)
            self.assertIn("current-sha", runtime.read_text(encoding="utf-8"))

    def test_ensure_installs_staged_helper_to_runtime_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            calls: list[int] = []

            def assemble() -> int:
                staged.parent.mkdir(parents=True)
                staged.write_text("#!/bin/sh\n", encoding="utf-8")
                staged.chmod(0o755)
                calls.append(1)
                return 0

            found = smoke_lib.ensure_macos_screencapture_helper(
                root, assemble=assemble, home=home
            )
            self.assertEqual(runtime, found)
            self.assertEqual([1], calls)
            self.assertTrue(runtime.is_file())
            self.assertNotEqual(staged, runtime)

    def test_ensure_assembles_even_when_staged_already_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\n", encoding="utf-8")
            staged.chmod(0o755)
            calls: list[int] = []
            found = smoke_lib.ensure_macos_screencapture_helper(
                root,
                assemble=lambda: calls.append(1) or 0,
                home=home,
            )
            self.assertEqual(runtime, found)
            self.assertEqual([1], calls)
            self.assertTrue(runtime.is_file())

    def test_probe_invokes_runtime_helper_not_build_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            invoked: list[str] = []

            def assemble() -> int:
                staged.parent.mkdir(parents=True)
                staged.write_text("#!/bin/sh\n", encoding="utf-8")
                staged.chmod(0o755)
                return 0

            status = smoke_lib.probe_macos_screen_recording(
                root=root,
                ensure_helper=lambda: smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=assemble, home=home
                ),
                invoke_helper=lambda argv: invoked.append(argv[0])
                or (0, '{"granted": true}\n'),
            )
            self.assertEqual(smoke_lib.TCC_GRANTED, status)
            self.assertEqual([str(runtime)], invoked)
            self.assertNotEqual(str(staged), invoked[0])

    def test_invalid_override_fails_closed_without_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\n", encoding="utf-8")
            staged.chmod(0o755)
            invoked: list[list[str]] = []
            env = {"SPECTRE_SCREENCAPTURE_HELPER": str(Path(tmp) / "missing-helper")}
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=lambda: 0, home=home
                )
                status = smoke_lib.probe_macos_screen_recording(
                    root=root,
                    ensure_helper=lambda: found,
                    invoke_helper=lambda argv: invoked.append(argv)
                    or (0, '{"granted": true}\n'),
                )
            self.assertIsNone(found)
            self.assertEqual(smoke_lib.TCC_UNKNOWN, status)
            self.assertEqual([], invoked)

    def test_valid_override_ignores_invalid_helper_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            override = Path(tmp) / "override-helper"
            override.write_text("#!/bin/sh\noverride\n", encoding="utf-8")
            override.chmod(0o755)
            invoked: list[str] = []
            env = {
                "SPECTRE_SCREENCAPTURE_HELPER": str(override),
                "JAVA_TOOL_OPTIONS": (
                    f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}=tools/helper"
                ),
            }
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=lambda: 0, home=home
                )
                status = smoke_lib.probe_macos_screen_recording(
                    root=root,
                    ensure_helper=lambda: found,
                    invoke_helper=lambda argv: invoked.append(argv[0])
                    or (0, '{"granted": true}\n'),
                )
            self.assertEqual(override, found)
            self.assertEqual(smoke_lib.TCC_GRANTED, status)
            self.assertEqual([str(override)], invoked)

    def test_override_bare_child_helper_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            parent = Path(tmp) / "helpers"
            parent.mkdir()
            child = parent / smoke_lib.SCREENCAPTURE_HELPER_NAME
            child.write_text("#!/bin/sh\n", encoding="utf-8")
            child.chmod(0o755)
            env = {"SPECTRE_SCREENCAPTURE_HELPER": str(parent)}
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                with self.assertRaises(smoke_lib.InvalidScreencaptureHelperOverride):
                    smoke_lib.macos_screencapture_override_path()
                found = smoke_lib.ensure_macos_screencapture_helper(
                    Path(tmp), assemble=lambda: 0, home=Path(tmp) / "home"
                )
                self.assertIsNone(found)

    def test_relative_override_fails_closed_without_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\n", encoding="utf-8")
            staged.chmod(0o755)
            relative_app = (
                Path("tools") / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
            )
            exe = (
                root
                / relative_app
                / "Contents"
                / "MacOS"
                / smoke_lib.SCREENCAPTURE_HELPER_NAME
            )
            exe.parent.mkdir(parents=True)
            exe.write_text("#!/bin/sh\nrelative\n", encoding="utf-8")
            exe.chmod(0o755)
            invoked: list[list[str]] = []
            env = {"SPECTRE_SCREENCAPTURE_HELPER": str(relative_app)}
            previous = os.getcwd()
            try:
                os.chdir(root)
                with unittest.mock.patch.dict(os.environ, env, clear=False):
                    with self.assertRaises(
                        smoke_lib.InvalidScreencaptureHelperOverride
                    ) as ctx:
                        smoke_lib.macos_screencapture_override_path()
                    self.assertIn("absolute", str(ctx.exception).lower())
                    found = smoke_lib.ensure_macos_screencapture_helper(
                        root, assemble=lambda: 0, home=home
                    )
                    status = smoke_lib.probe_macos_screen_recording(
                        root=root,
                        ensure_helper=lambda: found,
                        invoke_helper=lambda argv: invoked.append(argv)
                        or (0, '{"granted": true}\n'),
                    )
            finally:
                os.chdir(previous)
            self.assertIsNone(found)
            self.assertEqual(smoke_lib.TCC_UNKNOWN, status)
            self.assertEqual([], invoked)

    def test_override_whitespace_fails_closed_without_strip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            helper = Path(tmp) / smoke_lib.SCREENCAPTURE_HELPER_NAME
            helper.write_text("#!/bin/sh\n", encoding="utf-8")
            helper.chmod(0o755)
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\n", encoding="utf-8")
            staged.chmod(0o755)
            invoked: list[list[str]] = []
            env = {"SPECTRE_SCREENCAPTURE_HELPER": f"  {helper}  "}
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                with self.assertRaises(
                    smoke_lib.InvalidScreencaptureHelperOverride
                ) as ctx:
                    smoke_lib.macos_screencapture_override_path()
                self.assertIn(str(helper), str(ctx.exception))
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=lambda: 0, home=home
                )
                status = smoke_lib.probe_macos_screen_recording(
                    root=root,
                    ensure_helper=lambda: found,
                    invoke_helper=lambda argv: invoked.append(argv)
                    or (0, '{"granted": true}\n'),
                )
            self.assertIsNone(found)
            self.assertEqual(smoke_lib.TCC_UNKNOWN, status)
            self.assertEqual([], invoked)

    def test_helper_dir_property_installs_and_probes_that_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            helper_dir = Path(tmp) / "custom-helper-dir"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            configured = (
                helper_dir
                / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
                / "Contents"
                / "MacOS"
                / smoke_lib.SCREENCAPTURE_HELPER_NAME
            )
            default_runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            invoked: list[str] = []

            def assemble() -> int:
                staged.parent.mkdir(parents=True)
                staged.write_text("#!/bin/sh\n", encoding="utf-8")
                staged.chmod(0o755)
                return 0

            env = {
                "JAVA_TOOL_OPTIONS": (
                    f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}={helper_dir}"
                )
            }
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=assemble, home=home
                )
                status = smoke_lib.probe_macos_screen_recording(
                    root=root,
                    ensure_helper=lambda: found,
                    invoke_helper=lambda argv: invoked.append(argv[0])
                    or (0, '{"granted": true}\n'),
                )
            self.assertEqual(configured, found)
            self.assertEqual(smoke_lib.TCC_GRANTED, status)
            self.assertEqual([str(configured)], invoked)
            self.assertNotEqual(configured, default_runtime)
            self.assertFalse(default_runtime.exists())

    def test_helper_dir_parse_stops_at_next_jvm_option(self):
        helper_dir = Path("/tmp/helper")
        parsed = smoke_lib.parse_screencapture_helper_dir_property(
            f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}={helper_dir} -Xmx2g"
        )
        self.assertTrue(parsed.defined)
        self.assertEqual(helper_dir, parsed.path)
        quoted = smoke_lib.parse_screencapture_helper_dir_property(
            f'-ea -D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}="/tmp/my helper" -Xmx2g'
        )
        self.assertEqual(Path("/tmp/my helper"), quoted.path)
        last_wins = smoke_lib.parse_screencapture_helper_dir_property(
            f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}=/tmp/first -Xmx2g "
            f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}=/tmp/second"
        )
        self.assertEqual(Path("/tmp/second"), last_wins.path)
        blank = smoke_lib.parse_screencapture_helper_dir_property(
            f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}="
        )
        self.assertTrue(blank.defined)
        self.assertIsNone(blank.path)

    def test_unparseable_helper_dir_property_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\n", encoding="utf-8")
            staged.chmod(0o755)
            env = {
                "JAVA_TOOL_OPTIONS": (
                    f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}"
                )
            }
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir):
                    smoke_lib.macos_screencapture_configured_helper_dir()
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=lambda: 0, home=home
                )
                status = smoke_lib.probe_macos_screen_recording(
                    root=root,
                    ensure_helper=lambda: found,
                    invoke_helper=lambda argv: (0, '{"granted": true}\n'),
                )
            self.assertIsNone(found)
            self.assertEqual(smoke_lib.TCC_UNKNOWN, status)

    def test_helper_dir_uses_java_launcher_env_precedence(self):
        prop = smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY
        all_envs = {
            "_JAVA_OPTIONS": f"-D{prop}=/tmp/underscore",
            "JDK_JAVA_OPTIONS": f"-D{prop}=/tmp/jdk",
            "JAVA_TOOL_OPTIONS": f"-D{prop}=/tmp/tool",
            "GRADLE_OPTS": f"-D{prop}=/tmp/gradle",
        }
        self.assertEqual(
            Path("/tmp/underscore"),
            smoke_lib.macos_screencapture_configured_helper_dir(all_envs),
        )
        jdk_over_tool = {
            "JDK_JAVA_OPTIONS": f"-D{prop}=/tmp/jdk",
            "JAVA_TOOL_OPTIONS": f"-D{prop}=/tmp/tool",
        }
        self.assertEqual(
            Path("/tmp/jdk"),
            smoke_lib.macos_screencapture_configured_helper_dir(jdk_over_tool),
        )
        gradle_only = {"GRADLE_OPTS": f"-D{prop}=/tmp/gradle"}
        self.assertIsNone(smoke_lib.macos_screencapture_configured_helper_dir(gradle_only))

    def test_helper_dir_expands_jdk_java_options_argument_file(self):
        prop = smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY
        with tempfile.TemporaryDirectory() as tmp:
            opts = Path(tmp) / "java.opts"
            opts.write_text(
                f"# launcher comment\n-D{prop}=/tmp/from-argfile\n",
                encoding="utf-8",
            )
            self.assertEqual(
                Path("/tmp/from-argfile"),
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"@{opts}"}
                ),
            )
            mixed = Path(tmp) / "mixed.opts"
            mixed.write_text(f"-D{prop}=/tmp/mixed-file\n", encoding="utf-8")
            self.assertEqual(
                Path("/tmp/mixed-file"),
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"-Xmx32m @{mixed}"}
                ),
            )
            with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir):
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"_JAVA_OPTIONS": f"@{opts}"}
                )
            with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir):
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JAVA_TOOL_OPTIONS": f"@{opts}"}
                )
            with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir):
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": "@java.opts"}
                )
            with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir):
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"@{Path(tmp) / 'missing.opts'}"}
                )

    def test_helper_dir_argfile_discards_token_on_unquoted_hash(self):
        """HotSpot drops a mid-token unquoted # argument; do not keep the prefix."""
        prop = smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY
        with tempfile.TemporaryDirectory() as tmp:
            hashed = Path(tmp) / "hashed.opts"
            hashed.write_text(f"-D{prop}=/tmp/helper#note\n", encoding="utf-8")
            self.assertIsNone(
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"@{hashed}"}
                )
            )
            quoted = Path(tmp) / "quoted.opts"
            quoted.write_text(f"-D{prop}='/tmp/helper#note'\n", encoding="utf-8")
            self.assertEqual(
                Path("/tmp/helper#note"),
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"@{quoted}"}
                ),
            )
            later_wins = Path(tmp) / "later.opts"
            later_wins.write_text(
                f"-D{prop}=/tmp/helper#note\n-D{prop}=/tmp/after\n",
                encoding="utf-8",
            )
            self.assertEqual(
                Path("/tmp/after"),
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"@{later_wins}"}
                ),
            )
            earlier_kept = Path(tmp) / "earlier.opts"
            earlier_kept.write_text(
                f"-D{prop}=/tmp/before\n-D{prop}=/tmp/helper#note\n",
                encoding="utf-8",
            )
            self.assertEqual(
                Path("/tmp/before"),
                smoke_lib.macos_screencapture_configured_helper_dir(
                    {"JDK_JAVA_OPTIONS": f"@{earlier_kept}"}
                ),
            )
            root = Path(tmp) / "root"
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            default_runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            hashed_runtime = (
                Path("/tmp/helper")
                / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
                / "Contents"
                / "MacOS"
                / smoke_lib.SCREENCAPTURE_HELPER_NAME
            )

            def assemble() -> int:
                staged.parent.mkdir(parents=True)
                staged.write_text("#!/bin/sh\n", encoding="utf-8")
                staged.chmod(0o755)
                return 0

            env = {
                "JDK_JAVA_OPTIONS": f"@{hashed}",
                "GRADLE_USER_HOME": str(Path(tmp) / "gradle-user-home"),
            }
            Path(env["GRADLE_USER_HOME"]).mkdir()
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=assemble, home=home
                )
            self.assertEqual(default_runtime, found)
            self.assertNotEqual(hashed_runtime, found)

    def test_blank_higher_precedence_helper_dir_uses_default(self):
        prop = smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY
        env = {
            "_JAVA_OPTIONS": f"-D{prop}=",
            "JDK_JAVA_OPTIONS": f"-D{prop}=/tmp/jdk",
        }
        self.assertIsNone(smoke_lib.macos_screencapture_configured_helper_dir(env))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            default_runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            jdk_runtime = (
                Path("/tmp/jdk")
                / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
                / "Contents"
                / "MacOS"
                / smoke_lib.SCREENCAPTURE_HELPER_NAME
            )

            def assemble() -> int:
                staged.parent.mkdir(parents=True)
                staged.write_text("#!/bin/sh\n", encoding="utf-8")
                staged.chmod(0o755)
                return 0

            with unittest.mock.patch.dict(os.environ, env, clear=False):
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=assemble, home=home
                )
            self.assertEqual(default_runtime, found)
            self.assertNotEqual(jdk_runtime, found)

    def test_helper_dir_ignores_gradle_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prop = smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY
            (root / "gradle.properties").write_text(
                f"systemProp.{prop}=/tmp/from-system-prop\n"
                f"org.gradle.jvmargs=-D{prop}=/tmp/from-jvmargs\n",
                encoding="utf-8",
            )
            self.assertIsNone(
                smoke_lib.macos_screencapture_configured_helper_dir({}, root=root)
            )
            env = {"JAVA_TOOL_OPTIONS": f"-D{prop}=/tmp/from-tool"}
            self.assertEqual(
                Path("/tmp/from-tool"),
                smoke_lib.macos_screencapture_configured_helper_dir(env, root=root),
            )

    def test_runtime_helper_follows_jvm_user_home(self):
        tool_home = Path("/tmp/jvm-home")
        env = {"JAVA_TOOL_OPTIONS": f"-Duser.home={tool_home}"}
        expected = (
            tool_home
            / "Library"
            / "Application Support"
            / "spectre"
            / "helpers"
            / smoke_lib.SCREENCAPTURE_HELPER_NAME
            / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
            / "Contents"
            / "MacOS"
            / smoke_lib.SCREENCAPTURE_HELPER_NAME
        )
        self.assertEqual(
            expected,
            smoke_lib.macos_screencapture_runtime_helper(environ=env),
        )
        precedence = {
            "HOME": "/tmp/env-home",
            "_JAVA_OPTIONS": "-Duser.home=/tmp/underscore-home",
            "JAVA_TOOL_OPTIONS": "-Duser.home=/tmp/tool-home",
        }
        self.assertEqual(
            Path("/tmp/underscore-home")
            / "Library"
            / "Application Support"
            / "spectre"
            / "helpers"
            / smoke_lib.SCREENCAPTURE_HELPER_NAME
            / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
            / "Contents"
            / "MacOS"
            / smoke_lib.SCREENCAPTURE_HELPER_NAME,
            smoke_lib.macos_screencapture_runtime_helper(environ=precedence),
        )

    def test_runtime_helper_uses_java_user_home_not_path_home(self):
        account_home = Path("/tmp/jvm-account-home")
        env_home = Path("/tmp/python-home")
        settings = (
            "Property settings:\n"
            f"    user.home = {account_home}\n"
            "    user.name = tester\n"
        )

        def fake_run(argv, **kwargs):
            if isinstance(argv, (list, tuple)) and "-XshowSettings:properties" in argv:
                return subprocess.CompletedProcess(argv, 0, stdout=settings)
            raise AssertionError(argv)

        expected = (
            account_home
            / "Library"
            / "Application Support"
            / "spectre"
            / "helpers"
            / smoke_lib.SCREENCAPTURE_HELPER_NAME
            / smoke_lib.SCREENCAPTURE_HELPER_APP_NAME
            / "Contents"
            / "MacOS"
            / smoke_lib.SCREENCAPTURE_HELPER_NAME
        )
        with (
            unittest.mock.patch.object(smoke_lib.subprocess, "run", side_effect=fake_run),
            unittest.mock.patch.object(Path, "home", return_value=env_home),
        ):
            self.assertEqual(
                expected,
                smoke_lib.macos_screencapture_runtime_helper(
                    environ={"HOME": str(env_home)}
                ),
            )

    def test_runtime_helper_fails_closed_when_java_user_home_unknown(self):
        with (
            unittest.mock.patch.object(
                smoke_lib.subprocess, "run", side_effect=FileNotFoundError("java")
            ),
            unittest.mock.patch.object(Path, "home", return_value=Path("/tmp/python-home")),
        ):
            with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir):
                smoke_lib.macos_screencapture_runtime_helper(environ={})

    def test_relative_helper_dir_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\n", encoding="utf-8")
            staged.chmod(0o755)
            env = {
                "JAVA_TOOL_OPTIONS": (
                    f"-D{smoke_lib.SCREENCAPTURE_HELPER_DIR_PROPERTY}=tools/helper"
                )
            }
            with unittest.mock.patch.dict(os.environ, env, clear=False):
                with self.assertRaises(smoke_lib.InvalidScreencaptureHelperDir) as ctx:
                    smoke_lib.macos_screencapture_configured_helper_dir()
                self.assertIn("absolute", str(ctx.exception).lower())
                found = smoke_lib.ensure_macos_screencapture_helper(
                    root, assemble=lambda: 0, home=home
                )
                status = smoke_lib.probe_macos_screen_recording(
                    root=root,
                    ensure_helper=lambda: found,
                    invoke_helper=lambda argv: (0, '{"granted": true}\n'),
                )
            self.assertIsNone(found)
            self.assertEqual(smoke_lib.TCC_UNKNOWN, status)

    def test_unknown_runtime_helper_is_refreshed_and_reprobed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            runtime.parent.mkdir(parents=True)
            runtime.write_text("#!/bin/sh\nstale\n", encoding="utf-8")
            runtime.chmod(0o755)
            invoked: list[str] = []
            assemble_calls: list[int] = []

            def assemble() -> int:
                staged.parent.mkdir(parents=True, exist_ok=True)
                content = (
                    "#!/bin/sh\nstale\n"
                    if not assemble_calls
                    else "#!/bin/sh\nfresh\n"
                )
                staged.write_text(content, encoding="utf-8")
                staged.chmod(0o755)
                assemble_calls.append(1)
                return 0

            def invoke(argv: list[str]) -> tuple[int, str]:
                invoked.append(Path(argv[0]).read_text(encoding="utf-8"))
                if "stale" in invoked[-1]:
                    return 1, "broken-preflight\n"
                return 0, '{"granted": true}\n'

            status = smoke_lib.probe_macos_screen_recording(
                root=root,
                ensure_helper=lambda: smoke_lib.ensure_macos_screencapture_helper(
                    root,
                    assemble=assemble,
                    home=home,
                ),
                refresh_helper=lambda: smoke_lib.ensure_macos_screencapture_helper(
                    root,
                    assemble=assemble,
                    home=home,
                    refresh=True,
                ),
                invoke_helper=invoke,
            )
            self.assertEqual(smoke_lib.TCC_GRANTED, status)
            self.assertEqual(["#!/bin/sh\nstale\n", "#!/bin/sh\nfresh\n"], invoked)
            self.assertEqual([1, 1], assemble_calls)
            self.assertIn("fresh", runtime.read_text(encoding="utf-8"))

    def test_granted_stale_runtime_helper_is_replaced_from_staged_before_probe(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = Path(tmp) / "home"
            staged = smoke_lib.macos_screencapture_staged_helper(root)
            runtime = smoke_lib.macos_screencapture_runtime_helper(home)
            runtime.parent.mkdir(parents=True)
            runtime.write_text("#!/bin/sh\nstale-granted\n", encoding="utf-8")
            runtime.chmod(0o755)
            staged.parent.mkdir(parents=True)
            staged.write_text("#!/bin/sh\nfresh-granted\n", encoding="utf-8")
            staged.chmod(0o755)
            invoked: list[str] = []
            assemble_calls: list[int] = []

            def invoke(argv: list[str]) -> tuple[int, str]:
                invoked.append(Path(argv[0]).read_text(encoding="utf-8"))
                return 0, '{"granted": true}\n'

            status = smoke_lib.probe_macos_screen_recording(
                root=root,
                ensure_helper=lambda: smoke_lib.ensure_macos_screencapture_helper(
                    root,
                    assemble=lambda: assemble_calls.append(1) or 0,
                    home=home,
                ),
                refresh_helper=lambda: smoke_lib.ensure_macos_screencapture_helper(
                    root,
                    assemble=lambda: assemble_calls.append(1) or 0,
                    home=home,
                    refresh=True,
                ),
                invoke_helper=invoke,
            )
            self.assertEqual(smoke_lib.TCC_GRANTED, status)
            self.assertEqual(["#!/bin/sh\nfresh-granted\n"], invoked)
            self.assertEqual([1], assemble_calls)
            self.assertIn("fresh-granted", runtime.read_text(encoding="utf-8"))

    def test_blocked_remaining_fills_required_ids_with_reason(self):
        existing = [
            smoke_lib.scenario_result("preflight", name="p", result="pass"),
            smoke_lib.scenario_result(
                "macos-tcc",
                name="tcc",
                result="fail",
                detail="Accessibility denied",
            ),
        ]
        filled = smoke_lib.fill_blocked_remaining(
            existing,
            reason="blocked by macos-tcc failure; grant TCC and relaunch",
        )
        ids = [row.id for row in filled]
        self.assertEqual(list(smoke_lib.REQUIRED_SCENARIO_IDS), ids)
        by_id = {row.id: row for row in filled}
        self.assertEqual("fail", by_id["macos-tcc"].result)
        self.assertEqual("pass", by_id["preflight"].result)
        self.assertEqual("n/a", by_id["check"].result)
        self.assertIn("macos-tcc", by_id["check"].reason)
        self.assertEqual("n/a", by_id["junit-live"].result)

    def test_require_macos_tcc_recheck_fails_closed_on_unknown(self):
        with self.assertRaises(RuntimeError) as raised:
            smoke_lib.require_macos_tcc(
                accessibility_probe=lambda: smoke_lib.TCC_UNKNOWN,
                screen_recording_probe=lambda: smoke_lib.TCC_GRANTED,
                system="Darwin",
            )
        self.assertIn("Accessibility", str(raised.exception))

    def test_assemble_task_matches_process_resources_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            user_home = Path(tmp) / "gradle-user-home"
            user_home.mkdir()
            isolated = {"GRADLE_USER_HOME": str(user_home)}
            self.assertEqual(
                smoke_lib.ASSEMBLE_SCREENCAPTURE_HELPER_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )
            (root / "gradle.properties").write_text(
                "universalHelper=\n", encoding="utf-8"
            )
            self.assertEqual(
                smoke_lib.ASSEMBLE_SCREENCAPTURE_HELPER_UNIVERSAL_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )
            (root / "gradle.properties").write_text(
                "notarizeScreenCaptureKitHelper=true\n", encoding="utf-8"
            )
            self.assertEqual(
                smoke_lib.ASSEMBLE_SCREENCAPTURE_HELPER_UNIVERSAL_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )
            (root / "gradle.properties").write_text(
                "prebuiltMacHelperPath=/tmp/prebuilt.app\n"
                "universalHelper=\n",
                encoding="utf-8",
            )
            self.assertEqual(
                smoke_lib.STAGE_PREBUILT_MAC_HELPER_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )
            (root / "gradle.properties").write_text(
                "stubMacHelperForTesting=\n"
                "prebuiltMacHelperPath=/tmp/prebuilt.app\n",
                encoding="utf-8",
            )
            self.assertEqual(
                smoke_lib.STAGE_STUB_MAC_HELPER_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )
            env = {
                **isolated,
                "ORG_GRADLE_PROJECT_prebuiltMacHelperPath": "/tmp/from-env.app",
            }
            (root / "gradle.properties").write_text("", encoding="utf-8")
            self.assertEqual(
                smoke_lib.STAGE_PREBUILT_MAC_HELPER_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=env),
            )
            (user_home / "gradle.properties").write_text(
                "universalHelper=true\n", encoding="utf-8"
            )
            self.assertEqual(
                smoke_lib.ASSEMBLE_SCREENCAPTURE_HELPER_UNIVERSAL_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )
            (root / "gradle.properties").write_text(
                "# universalHelper=\nVERSION_NAME=1.0\n", encoding="utf-8"
            )
            (user_home / "gradle.properties").write_text("", encoding="utf-8")
            self.assertEqual(
                smoke_lib.ASSEMBLE_SCREENCAPTURE_HELPER_TASK,
                smoke_lib.macos_screencapture_assemble_task(root, environ=isolated),
            )

    def test_assemble_invokes_selected_staging_task(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            gradlew = root / "gradlew"
            gradlew.write_text("#!/bin/sh\n", encoding="utf-8")
            gradlew.chmod(0o755)
            (root / "gradle.properties").write_text(
                "universalHelper=\n", encoding="utf-8"
            )
            captured: list[list[str]] = []

            def fake_run(cmd, **kwargs):
                captured.append(list(cmd))
                return subprocess.CompletedProcess(cmd, 0)

            env = {"GRADLE_USER_HOME": str(Path(tmp) / "gradle-user-home")}
            Path(env["GRADLE_USER_HOME"]).mkdir()
            with (
                unittest.mock.patch.object(
                    smoke_lib.subprocess, "run", side_effect=fake_run
                ),
                unittest.mock.patch.dict(os.environ, env, clear=False),
            ):
                code = smoke_lib._assemble_screencapture_helper(root)
            self.assertEqual(0, code)
            self.assertEqual(
                [
                    str(gradlew),
                    smoke_lib.ASSEMBLE_SCREENCAPTURE_HELPER_UNIVERSAL_TASK,
                    "--console=plain",
                ],
                captured[0],
            )


class ReleaseSmokeMacOsTccWiringTest(unittest.TestCase):
    """Drive the Unix entrypoint so a denied TCC probe never reaches ./gradlew check."""

    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "release_smoke_macos_tcc", RELEASE_SMOKE
        )
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        cls.rs = module

    @unittest.skipIf(
        platform.system() == "Windows",
        "Unix release-smoke entrypoint intentionally rejects Windows",
    )
    def _stop_and_tcc_denied(self, *, run_command_side_effect, out: Path) -> int:
        real_run = self.rs.subprocess.run

        def wrapped(*args, **kwargs):
            cmd = args[0] if args else kwargs.get("args")
            if isinstance(cmd, (list, tuple)) and any(
                str(part).endswith("gradlew") for part in cmd
            ):
                return subprocess.CompletedProcess(cmd, 0)
            return real_run(*args, **kwargs)

        with (
            unittest.mock.patch.object(
                self.rs.platform, "system", return_value="Darwin"
            ),
            unittest.mock.patch.object(
                self.rs, "macos_tcc_skip_reason", return_value=None
            ),
            unittest.mock.patch.object(
                self.rs,
                "probe_macos_accessibility",
                return_value=smoke_lib.TCC_DENIED,
            ),
            unittest.mock.patch.object(
                self.rs,
                "probe_macos_screen_recording",
                return_value=smoke_lib.TCC_GRANTED,
            ),
            unittest.mock.patch.object(
                self.rs,
                "probe_macos_wrapping_screen_recording",
                return_value=smoke_lib.TCC_GRANTED,
            ),
            unittest.mock.patch.object(self.rs.subprocess, "run", side_effect=wrapped),
            unittest.mock.patch.object(
                self.rs, "run_command", side_effect=run_command_side_effect
            ),
        ):
            return self.rs.main(
                [
                    "--version",
                    "0.5.0",
                    "--base",
                    "v0.4.1",
                    "--out-dir",
                    str(out),
                    "--overall-timeout",
                    "60",
                ]
            )

    def test_denied_tcc_aborts_before_check(self):
        gradle_cmds: list[list[str]] = []

        def wrapped_run_command(command, **kwargs):
            gradle_cmds.append(list(command))
            if "--stop" in command:
                return 0, "", str(kwargs.get("log_path") or "")
            raise AssertionError(f"unexpected run_command: {command}")

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            code = self._stop_and_tcc_denied(
                run_command_side_effect=wrapped_run_command, out=out
            )
            self.assertEqual(1, code)
            self.assertTrue(
                any("--stop" in cmd for cmd in gradle_cmds),
                gradle_cmds,
            )
            self.assertFalse(
                any("check" in cmd for cmd in gradle_cmds),
                gradle_cmds,
            )
            report = json.loads((out / "release-smoke.json").read_text(encoding="utf-8"))
            by_id = {row["id"]: row for row in report["scenarios"]}
            self.assertEqual("fail", by_id["macos-tcc"]["result"])
            self.assertIn("Accessibility", by_id["macos-tcc"]["detail"])
            self.assertEqual("n/a", by_id["check"]["result"])
            self.assertIn("macos-tcc", by_id["check"]["reason"])

    @unittest.skipIf(
        platform.system() == "Windows",
        "Unix release-smoke entrypoint intentionally rejects Windows",
    )
    def test_macos_tcc_stops_stale_daemons_before_probe(self):
        order: list[str] = []

        def wrapped_run_command(command, **kwargs):
            if "--stop" in command:
                order.append("stop")
                return 0, "", str(kwargs.get("log_path") or "")
            order.append("gradle")
            return 0, "", str(kwargs.get("log_path") or "")

        def accessibility() -> str:
            order.append("probe")
            return smoke_lib.TCC_DENIED

        real_run = self.rs.subprocess.run

        def wrapped(*args, **kwargs):
            cmd = args[0] if args else kwargs.get("args")
            if isinstance(cmd, (list, tuple)) and any(
                str(part).endswith("gradlew") for part in cmd
            ):
                order.append("gradle")
                return subprocess.CompletedProcess(cmd, 0)
            return real_run(*args, **kwargs)

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            with (
                unittest.mock.patch.object(
                    self.rs.platform, "system", return_value="Darwin"
                ),
                unittest.mock.patch.object(
                    self.rs, "macos_tcc_skip_reason", return_value=None
                ),
                unittest.mock.patch.object(
                    self.rs, "probe_macos_accessibility", side_effect=accessibility
                ),
                unittest.mock.patch.object(
                    self.rs,
                    "probe_macos_screen_recording",
                    return_value=smoke_lib.TCC_GRANTED,
                ),
                unittest.mock.patch.object(
                    self.rs,
                    "probe_macos_wrapping_screen_recording",
                    return_value=smoke_lib.TCC_GRANTED,
                ),
                unittest.mock.patch.object(
                    self.rs.subprocess, "run", side_effect=wrapped
                ),
                unittest.mock.patch.object(
                    self.rs, "run_command", side_effect=wrapped_run_command
                ),
            ):
                code = self.rs.main(
                    [
                        "--version",
                        "0.5.0",
                        "--base",
                        "v0.4.1",
                        "--out-dir",
                        str(out),
                        "--overall-timeout",
                        "60",
                    ]
                )
        self.assertEqual(1, code)
        self.assertIn("stop", order)
        self.assertLess(order.index("stop"), order.index("probe"))
        self.assertNotIn("gradle", order)

    def test_macos_daemon_stop_is_bounded_by_overall_timeout(self):
        seen: dict[str, object] = {}

        def wrapped_run_command(command, **kwargs):
            self.assertIn("--stop", command)
            seen["timeout"] = kwargs.get("timeout")
            seen["overall_deadline"] = kwargs.get("overall_deadline")
            seen["now"] = time.monotonic()
            return 0, "", str(kwargs.get("log_path") or "")

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            code = self._stop_and_tcc_denied(
                run_command_side_effect=wrapped_run_command, out=out
            )
        self.assertEqual(1, code)
        self.assertIsNotNone(seen.get("timeout"))
        self.assertIsNotNone(seen.get("overall_deadline"))
        timeout = int(seen["timeout"])  # type: ignore[arg-type]
        deadline = float(seen["overall_deadline"])  # type: ignore[arg-type]
        started = float(seen["now"])  # type: ignore[arg-type]
        self.assertGreater(timeout, 0)
        self.assertLessEqual(timeout, smoke_lib.GRADLE_STOP_TIMEOUT_SECONDS)
        self.assertLessEqual(deadline, started + 60 + 1)
        self.assertGreater(deadline, started)

    def test_macos_daemon_stop_timeout_writes_failure_report(self):
        def wrapped_run_command(command, **kwargs):
            self.assertIn("--stop", command)
            log = str(kwargs.get("log_path") or "gradle-stop.log")
            return 124, "timeout after 1s", log

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            with (
                unittest.mock.patch.object(
                    self.rs.platform, "system", return_value="Darwin"
                ),
                unittest.mock.patch.object(
                    self.rs, "run_command", side_effect=wrapped_run_command
                ),
            ):
                code = self.rs.main(
                    [
                        "--version",
                        "0.5.0",
                        "--base",
                        "v0.4.1",
                        "--out-dir",
                        str(out),
                        "--overall-timeout",
                        "60",
                    ]
                )
            self.assertEqual(1, code)
            report = json.loads((out / "release-smoke.json").read_text(encoding="utf-8"))
            errors = smoke_lib.validate_report(
                report, required_ids=smoke_lib.REQUIRED_SCENARIO_IDS
            )
            self.assertEqual([], errors, errors)
            by_id = {row["id"]: row for row in report["scenarios"]}
            self.assertEqual("fail", by_id["preflight"]["result"])
            self.assertIn("stop", by_id["preflight"]["detail"].lower())
            self.assertEqual("n/a", by_id["check"]["result"])


if __name__ == "__main__":
    unittest.main()

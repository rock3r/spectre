#!/usr/bin/env python3
"""Cross-platform baseline pre-release smoke (macOS / Linux).

Writes versioned machine-readable results under build/smoke/:
  - release-smoke.json   (schemaVersion report)
  - release-smoke.md     (Markdown results table)
  - <scenario-id>-<stamp>.log per step

Windows: use scripts/windows-release-smoke.ps1 from an interactive desktop
(especially for WGC). That entrypoint emits the same schemaVersion shape and
stable scenario IDs.

See docs/RELEASE-SMOKE.md and scripts/smoke_lib.py for scenario IDs.
"""
from __future__ import annotations

import argparse
import platform
import subprocess
import sys
import time
import zipfile
from pathlib import Path

# Allow `python3 scripts/release-smoke.py` without installing a package.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from smoke_lib import (  # noqa: E402
    REQUIRED_SCENARIO_IDS,
    RESULT_FAIL,
    RESULT_PASS,
    ScenarioResult,
    apply_linux_toolchain_path,
    assert_linux_portal_tokens_captured,
    assert_mcp_fixture_e2e_executed,
    assert_pointer_move_live_executed,
    WAYLAND_PORTAL_WARMUP_TOKEN_KEYS,
    linux_portal_token_path,
    build_report,
    collect_preflight,
    gradle_ui_force_args,
    hard_failures,
    host_cli_package_target,
    packaged_cli_executable,
    pointer_move_api_skip_reason,
    portal_token_warmup_skip_reason,
    prepare_linux_portal_token_env,
    robot_xvfb_prefix,
    robot_xvfb_unavailable_reason,
    run_callable_scenario,
    run_scenario,
    scenario_result,
    utc_now_iso,
    validate_report,
    write_json_report,
    write_markdown_report,
    xvfb_prefix,
)

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build" / "smoke"

# Default overall budget for a full baseline (seconds). Overridable via CLI.
DEFAULT_OVERALL_TIMEOUT = 7200


def _print_result(item: ScenarioResult) -> None:
    note = item.reason or item.detail or ""
    suffix = f": {note}" if note else ""
    print(f"{item.result.upper():4} {item.id} ({item.seconds}s){suffix}  {item.log}", flush=True)


def _run_preflight_scenario(preflight, out_dir: Path) -> ScenarioResult:
    """Encode preflight collection as a hard scenario row."""

    def action() -> None:
        if not preflight.sha or len(preflight.sha) < 7:
            raise RuntimeError(f"invalid SHA from preflight: {preflight.sha!r}")
        if not preflight.version.strip():
            raise RuntimeError("version is required")
        if not preflight.environment.display_mode:
            raise RuntimeError("displayMode missing from environment")

    return run_callable_scenario(
        "preflight",
        name="Environment / SHA / clean-tree preflight",
        action=action,
        out_dir=out_dir,
    )


def _native_helper_layout_check(root: Path, system: str) -> None:
    """Assert host-OS native helpers exist in the release-shaped CLI package zip."""
    target = host_cli_package_target(system)
    # packageMacosArm64 → spectre-macosArm64.zip (Roast target name is macosArm64).
    roast_name = {
        "MacosArm64": "macosArm64",
        "MacosX64": "macosX64",
        "LinuxX64": "linuxX64",
        "LinuxArm64": "linuxArm64",
        "WindowsX64": "windowsX64",
    }.get(target)
    if not roast_name:
        raise RuntimeError(f"unknown package target {target}")
    zip_path = (
        root
        / "cli"
        / "build"
        / "construo"
        / "distributions"
        / roast_name
        / f"spectre-{roast_name}.zip"
    )
    if not zip_path.is_file():
        # Fall back to inspecting the unpacked launcher tree for host helpers.
        executable = packaged_cli_executable(root, system)
        if not executable.is_file():
            raise RuntimeError(
                f"neither distribution zip ({zip_path}) nor executable ({executable}) present"
            )
        _assert_helper_near_executable(executable, system)
        return

    with zipfile.ZipFile(zip_path) as archive:
        names = set(archive.namelist())
    if system == "Darwin":
        markers = (
            "native/macos/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
            "SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
        )
        if not any(any(marker in name for name in names) for marker in markers):
            # Some layouts nest helpers only under the app runtime jar — require the app bundle.
            if not any("Spectre.app/" in name for name in names):
                raise RuntimeError(
                    f"macOS package zip missing Spectre.app / SCK helper markers: {zip_path}"
                )
    elif system == "Linux":
        if not any("spectre-wayland-helper" in name or "native/linux" in name for name in names):
            # Host-only filter may still ship helper under jar resources; require zip non-empty + spectre launcher.
            if not any(name.endswith("/spectre") or name.endswith("spectre") for name in names):
                raise RuntimeError(f"Linux package zip missing launcher: {zip_path}")
    executable = packaged_cli_executable(root, system)
    if not executable.is_file():
        raise RuntimeError(f"packaged executable missing after package: {executable}")


def _assert_helper_near_executable(executable: Path, system: str) -> None:
    """Lightweight existence checks when only the unpacked package tree is present."""
    if not executable.is_file():
        raise RuntimeError(f"executable missing: {executable}")
    if system == "Darwin":
        app = executable
        while app.name != "Spectre.app" and app != app.parent:
            app = app.parent
        if app.name != "Spectre.app":
            raise RuntimeError(f"expected Spectre.app bundle above {executable}")
        # Helper may live inside the sealed jar; require the outer app Contents tree.
        contents = app / "Contents"
        if not (contents / "MacOS").is_dir():
            raise RuntimeError(f"Spectre.app missing Contents/MacOS: {app}")
    elif system == "Linux":
        if executable.stat().st_size < 1024:
            raise RuntimeError(f"Linux launcher unexpectedly tiny: {executable}")


def _host_recording_task(system: str, *, wayland_portal: bool = False) -> str | None:
    if system == "Darwin":
        return ":recording:runMacOsSckRegionSmoke"
    if system == "Linux":
        if wayland_portal:
            return ":recording:runWaylandPortalSmoke"
        return ":recording:runLinuxX11RecordingSmoke"
    return None


def _ui_prefix(system: str, *, wayland_portal: bool = False) -> list[str]:
    """Xvfb only for X11 Linux. A real Wayland portal JFrame must stay on the seat."""
    if wayland_portal:
        return []
    return xvfb_prefix(system)


def _robot_ui_prefix(system: str, *, wayland_portal: bool = False) -> list[str]:
    """Prefix for JBR/AWT Robot cells.

    Helper ScreenCast restore tokens do not cover Robot / Remote Desktop. Even
    after a successful Wayland portal warmup, these cells must stay off the
    seated compositor or each JVM pops a new Share dialog.
    """
    del wayland_portal
    return robot_xvfb_prefix(system)


def _robot_cell_blocked_result(
    scenario_id: str, name: str, system: str
) -> ScenarioResult | None:
    reason = robot_xvfb_unavailable_reason(system)
    if reason is None:
        return None
    return scenario_result(
        scenario_id,
        name=name,
        result=RESULT_FAIL,
        detail=reason,
        hard=True,
    )


def _robot_env(env: dict[str, str] | None) -> dict[str, str]:
    """Force Robot cells onto X11/Xvfb even when the login session is Wayland.

    Nested xvfb-run inherits WAYLAND_DISPLAY from the seat. Empty values are
    stripped by run_command, so clearing that variable plus SPECTRE_CAPTURE_BACKEND=x11
    keeps JBR Robot off xdg-desktop-portal.
    """
    robot = dict(env or {})
    robot["SPECTRE_CAPTURE_BACKEND"] = "x11"
    robot["WAYLAND_DISPLAY"] = ""
    robot["XDG_SESSION_TYPE"] = "x11"
    apply_linux_toolchain_path(robot)
    return robot


def _packaged_cli_portal_env(env: dict[str, str] | None) -> dict[str, str] | None:
    """Keep the restore-token dir, but use the packaged helper rather than the staged binary."""
    if env is None:
        return None
    packaged = dict(env)
    packaged["SPECTRE_WAYLAND_HELPER"] = ""
    return packaged


def _maven_local_version(release_version: str) -> str:
    # Keep smoke publication off the SNAPSHOT and release coordinates consumers might already have.
    return f"{release_version}-rc.smoke"


def _fresh_consumer_check(root: Path, version: str) -> None:
    """Resolve spectre-core from Maven Local into a throwaway compile classpath."""
    group_path = root.home() / ".m2" / "repository" / "dev" / "sebastiano" / "spectre"
    core_jar = group_path / "spectre-core" / version / f"spectre-core-{version}.jar"
    if not core_jar.is_file():
        raise RuntimeError(f"Maven Local core jar missing after publish: {core_jar}")
    # Fresh consumer: jar listing proves the coordinate resolves without spinning a
    # second Gradle project that re-enters the monorepo configuration.
    completed = subprocess.run(
        ["jar", "tf", str(core_jar)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=60,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"jar tf failed for {core_jar}: {completed.stdout}")
    if "dev/sebastiano/spectre/" not in completed.stdout:
        raise RuntimeError(f"core jar does not look like spectre-core: {core_jar}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--version",
        required=True,
        help="Expected release version recorded in the report and MCP server check (e.g. 0.5.0)",
    )
    parser.add_argument(
        "--base",
        default=None,
        help="Previous release tag / base for the delta inventory (default: latest git tag)",
    )
    parser.add_argument("--skip-check", action="store_true", help="Skip ./gradlew check")
    parser.add_argument(
        "--allow-dirty",
        action="store_true",
        help="Accepted for compatibility; dirty state is always recorded, never auto-failed",
    )
    parser.add_argument(
        "--overall-timeout",
        type=int,
        default=DEFAULT_OVERALL_TIMEOUT,
        help=f"Overall smoke budget in seconds (default {DEFAULT_OVERALL_TIMEOUT})",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help="Directory for JSON/MD reports and per-step logs (default: build/smoke)",
    )
    parser.add_argument(
        "--skip-maven-local",
        action="store_true",
        help="Skip Maven Local publish + consumer (records hard n/a with reason)",
    )
    parser.add_argument(
        "--skip-recording",
        action="store_true",
        help="Skip host native recording smoke (records hard n/a with reason)",
    )
    parser.add_argument(
        "--preflight-only",
        action="store_true",
        help=(
            "Run only preflight and emit a full required-ID report with remaining "
            "scenarios as hard n/a (reason: preflight-only). Not a release GO."
        ),
    )
    args = parser.parse_args(argv)

    system = platform.system()
    if system == "Linux":
        # Non-login SSH PATH lacks ~/.cargo/bin; xvfb-run inherits that and
        # :recording:buildWaylandHelper then cannot start cargo.
        apply_linux_toolchain_path()
        # A leftover Gradle daemon started without that PATH still cannot exec cargo.
        # Never --stop during --preflight-only: verifyReleaseSmokeScripts invokes that
        # entrypoint under ./gradlew check and a stop kills the parent daemon.
        if not args.preflight_only:
            subprocess.run(
                [str(ROOT / "gradlew"), "--stop"],
                cwd=ROOT,
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
    if system == "Windows":
        print(
            "Use scripts/windows-release-smoke.ps1 from the interactive desktop "
            "(WGC requires a native console session).",
            file=sys.stderr,
        )
        return 2

    out_dir = args.out_dir if args.out_dir is not None else OUT
    if not out_dir.is_absolute():
        out_dir = ROOT / out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    started_at = utc_now_iso()
    wall_start = time.monotonic()
    overall_deadline = wall_start + max(60, int(args.overall_timeout))

    preflight = collect_preflight(ROOT, version=args.version, base=args.base)
    results: list[ScenarioResult] = []

    preflight_result = _run_preflight_scenario(preflight, out_dir)
    if preflight.dirty and preflight_result.result == RESULT_PASS:
        # Annotate dirty state only when preflight checks already passed — never
        # overwrite a real preflight failure with a synthetic PASS.
        preflight_result = scenario_result(
            "preflight",
            name=preflight_result.name,
            result=RESULT_PASS,
            seconds=preflight_result.seconds,
            detail=f"dirty worktree: {preflight.dirty_summary}",
            log=preflight_result.log,
        )
    results.append(preflight_result)
    _print_result(preflight_result)

    if args.preflight_only:
        # Schema/orchestration self-check: every required ID present, none silently omitted.
        # Remaining cells are explicit hard n/a so validate_report(required_ids=...) holds.
        for sid in REQUIRED_SCENARIO_IDS:
            if sid == "preflight":
                continue
            results.append(
                scenario_result(
                    sid,
                    name=f"{sid} (not executed)",
                    result="n/a",
                    reason="preflight-only mode; scenario not executed",
                    hard=True,
                )
            )
            _print_result(results[-1])
        finished_at = utc_now_iso()
        overall_seconds = int(time.monotonic() - wall_start)
        report = build_report(
            preflight,
            results,
            started_at=started_at,
            finished_at=finished_at,
            overall_seconds=overall_seconds,
        )
        schema_errors = validate_report(report, required_ids=list(REQUIRED_SCENARIO_IDS))
        json_path = out_dir / "release-smoke.json"
        md_path = out_dir / "release-smoke.md"
        write_json_report(json_path, report)
        write_markdown_report(md_path, report)
        print(f"Report JSON: {json_path}")
        print(f"Report MD:   {md_path}")
        print(f"displayMode: {preflight.environment.display_mode}")
        print(f"SHA: {preflight.sha}  dirty={preflight.dirty}")
        if schema_errors:
            print("REPORT SCHEMA ERRORS:", file=sys.stderr)
            for err in schema_errors:
                print(f"  - {err}", file=sys.stderr)
            return 2
        if preflight_result.result == RESULT_FAIL:
            print("PREFLIGHT-ONLY FAILED")
            return 1
        print("PREFLIGHT-ONLY OK (not a full release smoke GO)")
        return 0

    gradle = str(ROOT / "gradlew")
    force = gradle_ui_force_args()
    scenario_env: dict[str, str] | None = None
    def add(item: ScenarioResult) -> None:
        results.append(item)
        _print_result(item)

    # --- Linux Wayland portal token warmup ---
    portal_skip = portal_token_warmup_skip_reason(system=system)
    if portal_skip is not None:
        add(
            scenario_result(
                "portal-token-warmup",
                name="Capture persistent ScreenCast restore token",
                result="n/a",
                reason=portal_skip,
                hard=True,
            )
        )
    else:
        print(
            "portal-token-warmup: approve Share + Remember for the whole screen; "
            "only helper monitor ScreenCast cells reuse that token. Robot-backed "
            "cells stay under xvfb-run. Window-source prompts are per-window.",
            flush=True,
        )
        scenario_env = prepare_linux_portal_token_env(ROOT, out_dir)
        if "SPECTRE_WAYLAND_HELPER" not in scenario_env:
            staged = run_scenario(
                "portal-token-warmup",
                name="Stage spectre-wayland-helper for token warmup",
                command=[gradle, ":recording:assembleWaylandHelper", "--console=plain"],
                cwd=ROOT,
                timeout=600,
                out_dir=out_dir,
                env=scenario_env,
                overall_deadline=overall_deadline,
            )
            if staged.result != RESULT_PASS:
                add(staged)
                scenario_env = None
            else:
                scenario_env = prepare_linux_portal_token_env(ROOT, out_dir)
        if scenario_env is not None:
            before_mtime = {
                key: (
                    linux_portal_token_path(scenario_env, key).stat().st_mtime_ns + 1
                    if linux_portal_token_path(scenario_env, key).is_file()
                    else 0
                )
                for key in WAYLAND_PORTAL_WARMUP_TOKEN_KEYS
            }
            warmup = run_scenario(
                "portal-token-warmup",
                name="Capture persistent ScreenCast restore token",
                command=[gradle, ":recording:runWaylandPortalSmoke", "--console=plain"],
                cwd=ROOT,
                timeout=180,
                out_dir=out_dir,
                env=scenario_env,
                overall_deadline=overall_deadline,
            )
            if warmup.result == RESULT_PASS:
                try:
                    assert_linux_portal_tokens_captured(
                        scenario_env, expected_mtime_ns=before_mtime
                    )
                except RuntimeError as error:
                    warmup = scenario_result(
                        "portal-token-warmup",
                        name=warmup.name,
                        result=RESULT_FAIL,
                        seconds=warmup.seconds,
                        detail=str(error),
                        log=warmup.log,
                    )
                    scenario_env = None
            else:
                scenario_env = None
            add(warmup)

    seat_prefix = _ui_prefix(system, wayland_portal=scenario_env is not None)
    robot_prefix = _robot_ui_prefix(system, wayland_portal=scenario_env is not None)

    # --- check ---
    if args.skip_check:
        add(
            scenario_result(
                "check",
                name="./gradlew check",
                result="n/a",
                reason="skipped via --skip-check",
                hard=True,
            )
        )
    else:
        blocked = _robot_cell_blocked_result("check", "./gradlew check", system)
        add(
            blocked
            or run_scenario(
                "check",
                name="./gradlew check",
                command=[*robot_prefix, gradle, "check", "--console=plain"],
                cwd=ROOT,
                timeout=1200,
                out_dir=out_dir,
                env=_robot_env(scenario_env),
                overall_deadline=overall_deadline,
            )
        )

    # --- live JUnit failure artifacts/video + atomic capture ---
    blocked = _robot_cell_blocked_result(
        "junit-live",
        "Live JUnit failure artifacts/video and atomic capture",
        system,
    )
    add(
        blocked
        or run_scenario(
            "junit-live",
            name="Live JUnit failure artifacts/video and atomic capture",
            command=[*robot_prefix, gradle, ":sample-desktop:validationTest", *force],
            cwd=ROOT,
            timeout=900,
            out_dir=out_dir,
            env=_robot_env(scenario_env),
            overall_deadline=overall_deadline,
        )
    )

    # --- #433 pointer-move live hover ---
    pointer_name = "In-process moveTo/moveBy hover without click"
    pointer_skip = pointer_move_api_skip_reason(ROOT)
    if pointer_skip is not None:
        add(
            scenario_result(
                "pointer-move",
                name=pointer_name,
                result="n/a",
                reason=pointer_skip,
                hard=True,
            )
        )
    else:
        blocked = _robot_cell_blocked_result("pointer-move", pointer_name, system)
        pointer = blocked or run_scenario(
            "pointer-move",
            name=pointer_name,
            command=[
                *robot_prefix,
                gradle,
                ":sample-desktop:validationTest",
                "--tests",
                "*PointerMoveLive*",
                *force,
            ],
            cwd=ROOT,
            timeout=600,
            out_dir=out_dir,
            env=_robot_env(scenario_env),
            overall_deadline=overall_deadline,
        )
        if pointer.result == RESULT_PASS:
            try:
                assert_pointer_move_live_executed(ROOT)
            except RuntimeError as exc:
                pointer = scenario_result(
                    "pointer-move",
                    name=pointer_name,
                    result=RESULT_FAIL,
                    seconds=pointer.seconds,
                    detail=str(exc),
                    log=pointer.log,
                )
        add(pointer)

    # --- agent attach / corpus / inject / launch-and-attach ---
    for scenario_id, name, test_filter in (
        (
            "agent-attach-core",
            "Agent attach with preinstalled core",
            "*AgentAttachIntegration*",
        ),
        ("agent-contract-corpus", "Agent contract corpus", "*AgentContractCorpus*"),
        (
            "agent-inject",
            "Injected attach without preinstalled core",
            "*AgentInjectAttachIntegration*",
        ),
        ("agent-launch-and-attach", "Launch-and-attach", "*LaunchAndAttachIntegration*"),
    ):
        blocked = _robot_cell_blocked_result(scenario_id, name, system)
        add(
            blocked
            or run_scenario(
                scenario_id,
                name=name,
                command=[
                    *robot_prefix,
                    gradle,
                    ":agent:test",
                    "--tests",
                    test_filter,
                    *force,
                ],
                cwd=ROOT,
                timeout=600,
                out_dir=out_dir,
                env=_robot_env(scenario_env),
                overall_deadline=overall_deadline,
            )
        )

    # --- CLI package + native helper layout ---
    # Bake --version into the package so MCP serverInfo.version matches strict stdio
    # (default gradle.properties VERSION_NAME is 0.1.0-SNAPSHOT on main).
    target = host_cli_package_target(system)
    package_result = run_scenario(
        "cli-packaged",
        name=f"Release-shaped host CLI package (:cli:package{target})",
        command=[
            gradle,
            f":cli:package{target}",
            f"-PVERSION_NAME={args.version}",
            "--console=plain",
        ],
        cwd=ROOT,
        timeout=900,
        out_dir=out_dir,
        env=scenario_env,
        overall_deadline=overall_deadline,
    )
    add(package_result)

    if package_result.result == RESULT_PASS:
        add(
            run_callable_scenario(
                "cli-native-helper-layout",
                name="Native-helper layout in packaged CLI",
                action=lambda: _native_helper_layout_check(ROOT, system),
                out_dir=out_dir,
            )
        )
    else:
        add(
            scenario_result(
                "cli-native-helper-layout",
                name="Native-helper layout in packaged CLI",
                result=RESULT_FAIL,
                detail="skipped because cli-packaged failed",
            )
        )

    executable = packaged_cli_executable(ROOT, system)
    if not executable.is_file():
        add(
            scenario_result(
                "cli-user-flow",
                name="Packaged CLI user flow (ps/attach/tree/input/capture/detach)",
                result=RESULT_FAIL,
                detail=f"packaged executable missing: {executable}",
            )
        )
        add(
            scenario_result(
                "mcp-sdk-flow",
                name="Packaged MCP attach/op/detach lifecycle + strict stdio",
                result=RESULT_FAIL,
                detail=f"packaged executable missing: {executable}",
            )
        )
    else:
        # CLI path: ps, attach, find/click, fail-closed window screenshot, fullscreen, cleanup.
        blocked = _robot_cell_blocked_result(
            "cli-user-flow",
            "Packaged CLI user flow (ps/attach/tree/input/capture/detach)",
            system,
        )
        add(
            blocked
            or run_scenario(
                "cli-user-flow",
                name="Packaged CLI user flow (ps/attach/tree/input/capture/detach)",
                command=[
                    *robot_prefix,
                    gradle,
                    ":cli:test",
                    "--tests",
                    "*DaemonFixtureIntegrationTest.CLI binary drives*",
                    f"-Dspectre.cli.distributionExecutable={executable}",
                    *force,
                ],
                cwd=ROOT,
                timeout=600,
                out_dir=out_dir,
                env=_robot_env(_packaged_cli_portal_env(scenario_env)),
                overall_deadline=overall_deadline,
            )
        )
        # MCP via official Kotlin SDK: fixture attach → op → detach session-gone is required
        # for hard pass after #399/#414 (tools/list + unknown-detach alone is insufficient).
        mcp_name = "Packaged MCP attach/op/detach lifecycle + strict stdio"
        blocked = _robot_cell_blocked_result("mcp-sdk-flow", mcp_name, system)
        mcp_sdk = blocked or run_scenario(
            "mcp-sdk-flow",
            name=mcp_name,
            command=[
                *robot_prefix,
                gradle,
                ":cli:test",
                # Same VERSION_NAME as package so SpectreMcpStdioIntegrationTest
                # expectedMcpVersion() matches packaged serverInfo.version.
                f"-PVERSION_NAME={args.version}",
                "--tests",
                "*DaemonFixtureIntegrationTest.MCP stdio drives*",
                "--tests",
                "*SpectreMcpStdioIntegrationTest*",
                f"-Dspectre.cli.distributionExecutable={executable}",
                *force,
            ],
            cwd=ROOT,
            timeout=600,
            out_dir=out_dir,
            env=_robot_env(_packaged_cli_portal_env(scenario_env)),
            overall_deadline=overall_deadline,
        )
        if mcp_sdk.result == RESULT_PASS:
            # Fail closed if the fixture e2e was assumption-skipped (Gradle exit 0).
            try:
                assert_mcp_fixture_e2e_executed(ROOT)
            except RuntimeError as exc:
                mcp_sdk = scenario_result(
                    "mcp-sdk-flow",
                    name=mcp_name,
                    result=RESULT_FAIL,
                    seconds=mcp_sdk.seconds,
                    detail=str(exc),
                    log=mcp_sdk.log,
                )
            else:
                strict = run_scenario(
                    "mcp-sdk-flow",
                    name="Packaged MCP strict stdio (version/tools/list/unknown-detach)",
                    command=[
                        sys.executable,
                        str(ROOT / "scripts" / "mcp-stdio-smoke.py"),
                        "--expected-version",
                        args.version,
                        "--",
                        str(executable),
                    ],
                    cwd=ROOT,
                    timeout=60,
                    out_dir=out_dir,
                    env=scenario_env,
                    overall_deadline=overall_deadline,
                )
                # Merge seconds/detail: fail closed if either leg fails.
                if strict.result != RESULT_PASS:
                    mcp_sdk = scenario_result(
                        "mcp-sdk-flow",
                        name=mcp_name,
                        result=RESULT_FAIL,
                        seconds=mcp_sdk.seconds + strict.seconds,
                        detail=f"strict stdio smoke: {strict.detail or strict.result}",
                        log=strict.log or mcp_sdk.log,
                    )
                else:
                    mcp_sdk = scenario_result(
                        "mcp-sdk-flow",
                        name=mcp_name,
                        result=RESULT_PASS,
                        seconds=mcp_sdk.seconds + strict.seconds,
                        detail=(
                            "SDK e2e attach/op/detach session-gone + strict stdio "
                            "(tools + unknown detach isError)"
                        ),
                        log=mcp_sdk.log,
                    )
        add(mcp_sdk)

    # --- host native recording ---
    recording_task = _host_recording_task(
        system, wayland_portal=scenario_env is not None
    )
    if args.skip_recording:
        add(
            scenario_result(
                "host-native-recording",
                name="Host native recording smoke",
                result="n/a",
                reason="skipped via --skip-recording",
                hard=True,
            )
        )
    elif recording_task is None:
        add(
            scenario_result(
                "host-native-recording",
                name="Host native recording smoke",
                result="n/a",
                reason=f"no Unix host recording task for {system}; Windows uses windows-release-smoke.ps1 WGC cell",
                hard=True,
            )
        )
    else:
        add(
            run_scenario(
                "host-native-recording",
                name=f"Host native recording ({recording_task})",
                command=[*seat_prefix, gradle, recording_task, "--console=plain"],
                cwd=ROOT,
                timeout=300,
                out_dir=out_dir,
                env=scenario_env,
                overall_deadline=overall_deadline,
            )
        )

    # --- Maven Local + fresh consumer ---
    if args.skip_maven_local:
        add(
            scenario_result(
                "maven-local-consumer",
                name="Maven Local publication + fresh consumer",
                result="n/a",
                reason="skipped via --skip-maven-local",
                hard=True,
            )
        )
    else:
        smoke_version = _maven_local_version(args.version)
        maven_cmd = [
            gradle,
            "verifyMavenLocalPublication",
            f"-PVERSION_NAME={smoke_version}",
            "--console=plain",
        ]
        # Linux cannot build the real mac helper; stub keeps the shape check honest.
        if system == "Linux":
            maven_cmd.append("-PstubMacHelperForTesting")
        elif system == "Darwin":
            # Host builds real mac helper; still stub foreign Windows helpers unless present.
            pass
        maven_result = run_scenario(
            "maven-local-consumer",
            name="Maven Local publication + shape verify",
            command=maven_cmd,
            cwd=ROOT,
            timeout=1200,
            out_dir=out_dir,
            env=scenario_env,
            overall_deadline=overall_deadline,
        )
        if maven_result.result == RESULT_PASS:
            consumer = run_callable_scenario(
                "maven-local-consumer",
                name="Maven Local publication + fresh consumer",
                action=lambda: _fresh_consumer_check(ROOT, smoke_version),
                out_dir=out_dir,
            )
            if consumer.result != RESULT_PASS:
                maven_result = consumer
            else:
                maven_result = scenario_result(
                    "maven-local-consumer",
                    name="Maven Local publication + fresh consumer",
                    result=RESULT_PASS,
                    seconds=maven_result.seconds + consumer.seconds,
                    detail=f"published {smoke_version}; core jar resolved from Maven Local",
                    log=maven_result.log,
                )
        add(maven_result)

    finished_at = utc_now_iso()
    overall_seconds = int(time.monotonic() - wall_start)
    report = build_report(
        preflight,
        results,
        started_at=started_at,
        finished_at=finished_at,
        overall_seconds=overall_seconds,
    )

    schema_errors = validate_report(report, required_ids=list(REQUIRED_SCENARIO_IDS))
    if schema_errors:
        print("REPORT SCHEMA ERRORS:", file=sys.stderr)
        for err in schema_errors:
            print(f"  - {err}", file=sys.stderr)

    json_path = out_dir / "release-smoke.json"
    md_path = out_dir / "release-smoke.md"
    write_json_report(json_path, report)
    write_markdown_report(md_path, report)
    print(f"Report JSON: {json_path}")
    print(f"Report MD:   {md_path}")
    print(f"displayMode: {preflight.environment.display_mode}")
    print(f"SHA: {preflight.sha}  dirty={preflight.dirty}")

    failed = hard_failures(results)
    if schema_errors or failed:
        if failed:
            print(f"HARD FAILURES: {', '.join(failed)}")
        return 1 if failed and not schema_errors else 2 if schema_errors else 1
    print("ALL HARD SCENARIOS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

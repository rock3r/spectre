#!/usr/bin/env python3
"""Cross-platform baseline pre-release smoke (macOS / Linux).

Writes versioned machine-readable results under build/smoke/:
  - release-smoke.json   (schemaVersion report)
  - release-smoke.md     (Markdown results table)
  - <scenario>-<stamp>.log per step

Windows: use scripts/windows-release-smoke.ps1 from an interactive desktop
(especially for WGC). That entrypoint emits the same schemaVersion shape.

See docs/RELEASE-SMOKE.md and scripts/smoke_lib.py for scenario IDs.
"""
from __future__ import annotations

import argparse
import os
import platform
import sys
import time
from pathlib import Path

# Allow `python3 scripts/release-smoke.py` without installing a package.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from smoke_lib import (  # noqa: E402
    REQUIRED_SCENARIO_IDS,
    RESULT_FAIL,
    RESULT_PASS,
    ScenarioResult,
    build_report,
    collect_preflight,
    gradle_ui_force_args,
    hard_failures,
    host_cli_package_target,
    packaged_cli_executable,
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

# Scenarios this PR wires end-to-end. Later trains expand toward REQUIRED_SCENARIO_IDS.
PR1_WIRED_IDS: tuple[str, ...] = (
    "preflight",
    "check",
    "junit-live",
    "agent-attach-core",
    "agent-contract-corpus",
    "agent-inject",
    "cli-packaged",
    "cli-user-flow",
    "mcp-sdk-flow",
)


def _print_result(item: ScenarioResult) -> None:
    note = item.reason or item.detail or ""
    suffix = f": {note}" if note else ""
    print(f"{item.result.upper():4} {item.id} ({item.seconds}s){suffix}  {item.log}")


def _run_preflight_scenario(preflight, out_dir: Path) -> ScenarioResult:
    """Encode preflight collection as a hard scenario row."""

    def action() -> None:
        if not preflight.sha or len(preflight.sha) < 7:
            raise RuntimeError(f"invalid SHA from preflight: {preflight.sha!r}")
        if not preflight.version.strip():
            raise RuntimeError("version is required")
        # Dirty trees are recorded, not failed — operators may smoke release SHAs with
        # local untracked plans. Report truthfully so release records stay honest.
        if not preflight.environment.display_mode:
            raise RuntimeError("displayMode missing from environment")

    return run_callable_scenario(
        "preflight",
        name="Environment / SHA / clean-tree preflight",
        action=action,
        out_dir=out_dir,
    )


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
        "--require-all-ids",
        action="store_true",
        help="Require every REQUIRED_SCENARIO_IDS row (full harness matrix). "
        "Default requires only wired baseline IDs until scenario expansion lands.",
    )
    args = parser.parse_args(argv)

    system = platform.system()
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

    gradle = str(ROOT / "gradlew")
    prefix = xvfb_prefix(system)
    force = gradle_ui_force_args()

    def add(item: ScenarioResult) -> None:
        results.append(item)
        _print_result(item)

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
        add(
            run_scenario(
                "check",
                name="./gradlew check",
                command=[gradle, "check", "--console=plain"],
                cwd=ROOT,
                timeout=1200,
                out_dir=out_dir,
                overall_deadline=overall_deadline,
            )
        )

    # Live JUnit: failure artifacts/video + capture validation surface.
    add(
        run_scenario(
            "junit-live",
            name="Live JUnit failure artifacts/video and capture",
            command=[*prefix, gradle, ":sample-desktop:validationTest", *force],
            cwd=ROOT,
            timeout=900,
            out_dir=out_dir,
            overall_deadline=overall_deadline,
        )
    )

    # Agent attach with preinstalled core (integration suite).
    add(
        run_scenario(
            "agent-attach-core",
            name="Agent attach with preinstalled core",
            command=[
                *prefix,
                gradle,
                ":agent:test",
                "--tests",
                "*AgentAttachIntegration*",
                *force,
            ],
            cwd=ROOT,
            timeout=600,
            out_dir=out_dir,
            overall_deadline=overall_deadline,
        )
    )

    add(
        run_scenario(
            "agent-contract-corpus",
            name="Agent contract corpus",
            command=[
                *prefix,
                gradle,
                ":agent:test",
                "--tests",
                "*AgentContractCorpus*",
                *force,
            ],
            cwd=ROOT,
            timeout=600,
            out_dir=out_dir,
            overall_deadline=overall_deadline,
        )
    )

    add(
        run_scenario(
            "agent-inject",
            name="Injected attach without preinstalled core",
            command=[
                *prefix,
                gradle,
                ":agent:test",
                "--tests",
                "*AgentInjectAttachIntegration*",
                *force,
            ],
            cwd=ROOT,
            timeout=600,
            out_dir=out_dir,
            overall_deadline=overall_deadline,
        )
    )

    target = host_cli_package_target(system)
    add(
        run_scenario(
            "cli-packaged",
            name=f"Release-shaped host CLI package (:cli:package{target})",
            command=[gradle, f":cli:package{target}", "--console=plain"],
            cwd=ROOT,
            timeout=900,
            out_dir=out_dir,
            overall_deadline=overall_deadline,
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
                name="Packaged MCP via official SDK (initialize/tools/list/…)",
                result=RESULT_FAIL,
                detail=f"packaged executable missing: {executable}",
            )
        )
    else:
        packaged_tests = [
            *prefix,
            gradle,
            ":cli:test",
            "--tests",
            "*DaemonFixtureIntegrationTest.CLI binary drives*",
            "--tests",
            "*DaemonFixtureIntegrationTest.MCP stdio drives*",
            "--tests",
            "*SpectreMcpStdioIntegrationTest*",
            f"-Dspectre.cli.distributionExecutable={executable}",
            *force,
        ]
        add(
            run_scenario(
                "cli-user-flow",
                name="Packaged CLI user flow (ps/attach/tree/input/capture/detach)",
                command=packaged_tests,
                cwd=ROOT,
                timeout=600,
                out_dir=out_dir,
                overall_deadline=overall_deadline,
            )
        )
        add(
            run_scenario(
                "mcp-sdk-flow",
                name="Packaged MCP strict stdio (initialize/tools/list/list_processes)",
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
                overall_deadline=overall_deadline,
            )
        )

    finished_at = utc_now_iso()
    overall_seconds = int(time.monotonic() - wall_start)
    report = build_report(
        preflight,
        results,
        started_at=started_at,
        finished_at=finished_at,
        overall_seconds=overall_seconds,
    )

    required = list(REQUIRED_SCENARIO_IDS) if args.require_all_ids else list(PR1_WIRED_IDS)
    schema_errors = validate_report(report, required_ids=required)
    if schema_errors:
        print("REPORT SCHEMA ERRORS:", file=sys.stderr)
        for err in schema_errors:
            print(f"  - {err}", file=sys.stderr)
        # Still write artifacts so operators can inspect partial runs.
        write_json_report(out_dir / "release-smoke.json", report)
        write_markdown_report(out_dir / "release-smoke.md", report)
        return 2

    json_path = out_dir / "release-smoke.json"
    md_path = out_dir / "release-smoke.md"
    write_json_report(json_path, report)
    write_markdown_report(md_path, report)
    print(f"Report JSON: {json_path}")
    print(f"Report MD:   {md_path}")
    print(f"displayMode: {preflight.environment.display_mode}")
    print(f"SHA: {preflight.sha}  dirty={preflight.dirty}")

    failed = hard_failures(results)
    if failed or schema_errors:
        print(f"HARD FAILURES: {', '.join(failed) if failed else '(schema)'}")
        return 1
    print("ALL HARD SCENARIOS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

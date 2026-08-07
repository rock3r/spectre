#!/usr/bin/env python3
"""Cross-platform baseline pre-release smoke. Writes build/smoke/release-smoke.json."""
import argparse
import json
import os
import platform
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build" / "smoke"


def run(name: str, command: list[str], timeout: int, env: dict[str, str] | None = None) -> dict:
    OUT.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    log = OUT / f"{name}.log"
    try:
        completed = subprocess.run(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, timeout=timeout)
        output, code = completed.stdout, completed.returncode
        result, detail = ("pass", "") if code == 0 else ("fail", f"exit {code}")
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        result, detail = "fail", f"timeout after {timeout}s"
    log.write_text(output)
    print(f"{result.upper():4} {name}: {detail} ({log})")
    return {"name": name, "result": result, "seconds": int(time.monotonic()-started),
            "detail": detail, "log": str(log)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="expected release version, e.g. 0.5.0")
    parser.add_argument("--skip-check", action="store_true")
    args = parser.parse_args()
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    system = platform.system()
    if system == "Windows":
        raise SystemExit("Use scripts/windows-release-smoke.ps1 from the interactive desktop")
    prefix = ["xvfb-run", "-a"] if system == "Linux" and not os.environ.get("DISPLAY") else []
    results = []
    if not args.skip_check:
        results.append(run("check", [gradle, "check", "--console=plain"], 1200))
    results.append(run("junit-live", [*prefix, gradle, ":sample-desktop:validationTest", "--console=plain"], 900))
    results.append(run("agent-attach", [*prefix, gradle, ":agent:test", "--tests", "*AgentAttachIntegration*", "--tests", "*AgentContractCorpus*", "--console=plain"], 600))
    results.append(run("agent-inject", [*prefix, gradle, ":agent:test", "--tests", "*AgentInjectAttachIntegration*", "--console=plain"], 600))
    target = "MacosArm64" if system == "Darwin" and platform.machine() == "arm64" else "LinuxX64"
    results.append(run("cli-packaged", [gradle, f":cli:package{target}", "--console=plain"], 900))
    if system == "Darwin":
        executable = ROOT / "cli/build/construo/macosArm64/Spectre.app/Contents/MacOS/spectre"
    else:
        executable = ROOT / "cli/build/construo/linuxX64/roast/spectre"
    results.append(run("cli-packaged-help", [str(executable), "--help"], 60))
    packaged_tests = [
        *prefix, gradle, ":cli:test",
        "--tests", "*DaemonFixtureIntegrationTest.CLI binary drives*",
        "--tests", "*DaemonFixtureIntegrationTest.MCP stdio drives*",
        "--tests", "*SpectreMcpStdioIntegrationTest*",
        f"-Dspectre.cli.distributionExecutable={executable}", "--console=plain",
    ]
    results.append(run("cli-mcp-packaged-user-flow", packaged_tests, 600))
    results.append(run("mcp-packaged-strict", [sys.executable, str(ROOT / "scripts/mcp-stdio-smoke.py"), "--expected-version", args.version, "--", str(executable)], 60))
    report = {"version": args.version, "sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), "os": system, "results": results}
    (OUT / "release-smoke.json").write_text(json.dumps(report, indent=2) + "\n")
    if any(item["result"] != "pass" for item in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()

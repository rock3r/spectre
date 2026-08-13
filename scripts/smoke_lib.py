#!/usr/bin/env python3
"""Shared report model, preflight, skip policy, and process helpers for release smoke.

Used by scripts/release-smoke.py (macOS/Linux). Windows PowerShell emits the same
schemaVersion report shape; keep field names stable across both entrypoints.
"""
from __future__ import annotations

import json
import os
import platform
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, MutableMapping, Sequence

# Bump only when report field names/semantics change incompatibly (rename/remove a field,
# or change meaning of an existing value). Additive optional fields and new scenario IDs
# do not require a bump — keep field names stable. Single source of truth: Windows
# scripts/windows-release-smoke.ps1 reads this constant via Get-SmokeSchemaVersion.
# Update contract tests + docs/RELEASE-SMOKE.md when bumping.
SCHEMA_VERSION = 1

# Stable scenario IDs shared across macOS / Linux / Windows. Every automated run
# must emit a result row for each ID that is in scope for that OS entrypoint.
REQUIRED_SCENARIO_IDS: tuple[str, ...] = (
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
)

RESULT_PASS = "pass"
RESULT_FAIL = "fail"
RESULT_NA = "n/a"
VALID_RESULTS = frozenset({RESULT_PASS, RESULT_FAIL, RESULT_NA})


@dataclass
class ScenarioResult:
    id: str
    name: str
    result: str
    seconds: int = 0
    detail: str = ""
    reason: str = ""
    log: str = ""
    hard: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "result": self.result,
            "seconds": self.seconds,
            "detail": self.detail,
            "reason": self.reason,
            "log": self.log,
            "hard": self.hard,
        }


@dataclass
class EnvironmentInfo:
    os: str
    os_version: str
    arch: str
    hostname: str
    user: str
    python: str
    display_mode: str
    java: str = ""
    extra: dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "os": self.os,
            "osVersion": self.os_version,
            "arch": self.arch,
            "hostname": self.hostname,
            "user": self.user,
            "python": self.python,
            "displayMode": self.display_mode,
            "java": self.java,
        }
        if self.extra:
            payload["extra"] = dict(self.extra)
        return payload


@dataclass
class PreflightInfo:
    version: str
    base: str
    sha: str
    sha_short: str
    dirty: bool
    dirty_summary: str
    repo_root: str
    environment: EnvironmentInfo

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "base": self.base,
            "sha": self.sha,
            "shaShort": self.sha_short,
            "dirty": self.dirty,
            "dirtySummary": self.dirty_summary,
            "repoRoot": self.repo_root,
            "environment": self.environment.to_dict(),
        }


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def git_output(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed ({completed.returncode}): {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def detect_display_mode(system: str | None = None) -> str:
    """Record how the host supplies a display for UI/live cells."""
    system = system or platform.system()
    if system == "Windows":
        # Interactive desktop vs SSH is operator-documented; harness cannot fully
        # prove WGC session type over the network.
        session = os.environ.get("SESSIONNAME", "")
        if session.upper().startswith("RDP") or os.environ.get("SSH_CONNECTION"):
            return "windows-remote-or-rdp"
        return "windows-interactive"
    if system == "Linux":
        display = os.environ.get("DISPLAY", "").strip()
        if display:
            return f"real-display:{display}"
        if _which("xvfb-run"):
            return "xvfb-auto"
        return "no-display"
    if system == "Darwin":
        return "macos-native"
    return f"unknown:{system}"


def _which(name: str) -> str | None:
    path = os.environ.get("PATH", "")
    for directory in path.split(os.pathsep):
        candidate = Path(directory) / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def detect_java_version() -> str:
    try:
        completed = subprocess.run(
            ["java", "-version"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=15,
            check=False,
        )
        first = (completed.stdout or "").strip().splitlines()
        return first[0] if first else ""
    except (OSError, subprocess.TimeoutExpired):
        return ""


def collect_preflight(
    root: Path,
    *,
    version: str,
    base: str | None = None,
) -> PreflightInfo:
    sha = git_output(root, "rev-parse", "HEAD")
    sha_short = git_output(root, "rev-parse", "--short", "HEAD")
    status = git_output(root, "status", "--porcelain")
    dirty = bool(status)
    dirty_summary = ""
    if dirty:
        lines = [line for line in status.splitlines() if line.strip()]
        dirty_summary = f"{len(lines)} path(s) dirty"
        if len(lines) <= 8:
            dirty_summary = "; ".join(lines)
    resolved_base = base or _default_base_tag(root)
    env = EnvironmentInfo(
        os=platform.system(),
        os_version=platform.version(),
        arch=platform.machine(),
        hostname=platform.node(),
        user=os.environ.get("USER") or os.environ.get("USERNAME") or "",
        python=sys.version.split()[0],
        display_mode=detect_display_mode(),
        java=detect_java_version(),
    )
    return PreflightInfo(
        version=version,
        base=resolved_base,
        sha=sha,
        sha_short=sha_short,
        dirty=dirty,
        dirty_summary=dirty_summary,
        repo_root=str(root.resolve()),
        environment=env,
    )


def _default_base_tag(root: Path) -> str:
    try:
        return git_output(root, "describe", "--tags", "--abbrev=0")
    except RuntimeError:
        return ""


def scenario_result(
    scenario_id: str,
    *,
    name: str,
    result: str,
    seconds: int = 0,
    detail: str = "",
    reason: str = "",
    log: str = "",
    hard: bool = True,
) -> ScenarioResult:
    """Build a scenario row; enforces fail-closed skip policy for hard cells."""
    if result not in VALID_RESULTS:
        raise ValueError(f"invalid result {result!r} for {scenario_id}")
    if result == RESULT_NA and hard and not reason.strip():
        # Fail-closed: hard N/A without reason becomes fail.
        return ScenarioResult(
            id=scenario_id,
            name=name,
            result=RESULT_FAIL,
            seconds=seconds,
            detail=detail or "hard skip without N/A reason",
            reason="",
            log=log,
            hard=hard,
        )
    return ScenarioResult(
        id=scenario_id,
        name=name,
        result=result,
        seconds=seconds,
        detail=detail,
        reason=reason.strip(),
        log=log,
        hard=hard,
    )


def validate_scenario_result(item: Mapping[str, Any]) -> list[str]:
    """Return validation errors for one scenario result dict."""
    errors: list[str] = []
    scenario_id = item.get("id")
    if not isinstance(scenario_id, str) or not scenario_id.strip():
        errors.append("scenario missing non-empty id")
        scenario_id = "<missing>"
    result = item.get("result")
    if result not in VALID_RESULTS:
        errors.append(f"{scenario_id}: result must be pass|fail|n/a, got {result!r}")
    hard = bool(item.get("hard", True))
    reason = item.get("reason") or ""
    if result == RESULT_NA and hard and not str(reason).strip():
        errors.append(f"{scenario_id}: hard n/a requires non-empty reason")
    if "name" not in item:
        errors.append(f"{scenario_id}: missing name")
    return errors


def validate_report(
    report: Mapping[str, Any],
    *,
    required_ids: Sequence[str] | None = None,
) -> list[str]:
    """Validate versioned release-smoke report. Returns human-readable errors."""
    errors: list[str] = []
    if report.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(
            f"schemaVersion must be {SCHEMA_VERSION}, got {report.get('schemaVersion')!r}"
        )
    for key in ("version", "base", "sha", "startedAt", "finishedAt", "environment", "scenarios"):
        if key not in report:
            errors.append(f"missing top-level field {key!r}")
    env = report.get("environment")
    if isinstance(env, Mapping):
        for key in ("os", "arch", "displayMode"):
            if key not in env:
                errors.append(f"environment missing {key!r}")
    else:
        if "environment" in report:
            errors.append("environment must be an object")
    scenarios = report.get("scenarios")
    if not isinstance(scenarios, list):
        errors.append("scenarios must be a list")
        return errors
    seen: set[str] = set()
    for item in scenarios:
        if not isinstance(item, Mapping):
            errors.append("scenario entry must be an object")
            continue
        errors.extend(validate_scenario_result(item))
        sid = item.get("id")
        if isinstance(sid, str):
            if sid in seen:
                errors.append(f"duplicate scenario id {sid!r}")
            seen.add(sid)
    if required_ids is not None:
        missing = [sid for sid in required_ids if sid not in seen]
        if missing:
            errors.append(f"missing required scenario ids: {', '.join(missing)}")
    return errors


def hard_failures(scenarios: Sequence[ScenarioResult | Mapping[str, Any]]) -> list[str]:
    failed: list[str] = []
    for item in scenarios:
        data = item.to_dict() if isinstance(item, ScenarioResult) else dict(item)
        if not data.get("hard", True):
            continue
        if data.get("result") == RESULT_FAIL:
            failed.append(str(data.get("id", "?")))
        if data.get("result") == RESULT_NA and not str(data.get("reason", "")).strip():
            failed.append(str(data.get("id", "?")))
    return failed


def build_report(
    preflight: PreflightInfo,
    scenarios: Sequence[ScenarioResult],
    *,
    started_at: str,
    finished_at: str | None = None,
    overall_seconds: int | None = None,
) -> dict[str, Any]:
    finished = finished_at or utc_now_iso()
    payload: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "version": preflight.version,
        "base": preflight.base,
        "sha": preflight.sha,
        "shaShort": preflight.sha_short,
        "dirty": preflight.dirty,
        "dirtySummary": preflight.dirty_summary,
        "repoRoot": preflight.repo_root,
        "startedAt": started_at,
        "finishedAt": finished,
        "environment": preflight.environment.to_dict(),
        "scenarios": [s.to_dict() for s in scenarios],
    }
    if overall_seconds is not None:
        payload["overallSeconds"] = overall_seconds
    return payload


def write_json_report(path: Path, report: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def markdown_table(scenarios: Sequence[ScenarioResult | Mapping[str, Any]]) -> str:
    lines = [
        "| ID | Name | Result | Seconds | Note |",
        "| --- | --- | --- | ---: | --- |",
    ]
    for item in scenarios:
        data = item.to_dict() if isinstance(item, ScenarioResult) else dict(item)
        note = data.get("reason") or data.get("detail") or ""
        note = str(note).replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {data.get('id', '')} | {data.get('name', '')} | {data.get('result', '')} | "
            f"{data.get('seconds', 0)} | {note} |"
        )
    return "\n".join(lines) + "\n"


def write_markdown_report(
    path: Path,
    report: Mapping[str, Any],
) -> None:
    scenarios = report.get("scenarios") or []
    env = report.get("environment") or {}
    body = [
        f"# Release smoke report",
        "",
        f"- **schemaVersion**: {report.get('schemaVersion')}",
        f"- **version**: {report.get('version')}",
        f"- **base**: {report.get('base')}",
        f"- **sha**: `{report.get('sha')}`",
        f"- **dirty**: {report.get('dirty')}",
        f"- **startedAt**: {report.get('startedAt')}",
        f"- **finishedAt**: {report.get('finishedAt')}",
        f"- **os**: {env.get('os')} / {env.get('arch')}",
        f"- **displayMode**: {env.get('displayMode')}",
        "",
        markdown_table(scenarios),  # type: ignore[arg-type]
    ]
    if report.get("dirtySummary"):
        body.insert(8, f"- **dirtySummary**: {report.get('dirtySummary')}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(body), encoding="utf-8")


def kill_process_tree(proc: subprocess.Popen[Any]) -> None:
    """Best-effort kill of the process and its descendants."""
    if proc.poll() is not None:
        return
    pid = proc.pid
    if pid is None:
        return
    try:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            try:
                os.killpg(pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                proc.terminate()
            deadline = time.monotonic() + 5
            while proc.poll() is None and time.monotonic() < deadline:
                time.sleep(0.1)
            if proc.poll() is None:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError, OSError):
                    proc.kill()
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass
    try:
        proc.wait(timeout=10)
    except Exception:
        pass


def run_command(
    command: Sequence[str],
    *,
    cwd: Path,
    timeout: int,
    log_path: Path,
    env: Mapping[str, str] | None = None,
    overall_deadline: float | None = None,
) -> tuple[int, str, str]:
    """Run command with timeout and process-group cleanup.

    Returns (exit_code, detail, log_path_str). detail is empty on success.
    """
    log_path.parent.mkdir(parents=True, exist_ok=True)
    merged_env: MutableMapping[str, str] | None = None
    if env is not None:
        merged_env = os.environ.copy()
        merged_env.update(env)

    remaining = timeout
    if overall_deadline is not None:
        budget_left = int(overall_deadline - time.monotonic())
        if budget_left <= 0:
            message = "overall smoke deadline exceeded before step start"
            log_path.write_text(message + "\n", encoding="utf-8")
            return 124, message, str(log_path)
        remaining = min(remaining, budget_left)

    # start_new_session creates a new process group on POSIX so killpg works.
    popen_kwargs: dict[str, Any] = {
        "cwd": str(cwd),
        "env": merged_env,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.STDOUT,
        "text": True,
    }
    if os.name != "nt":
        popen_kwargs["start_new_session"] = True

    proc = subprocess.Popen(list(command), **popen_kwargs)
    try:
        stdout, _ = proc.communicate(timeout=remaining)
        code = int(proc.returncode if proc.returncode is not None else -1)
        output = stdout or ""
        log_path.write_text(output, encoding="utf-8")
        if code == 0:
            return 0, "", str(log_path)
        return code, f"exit {code}", str(log_path)
    except subprocess.TimeoutExpired as error:
        kill_process_tree(proc)
        # communicate() stashes already-captured stdout on the exception; prefer that
        # over re-reading the pipe (which is often empty after the timeout).
        partial = ""
        if isinstance(error.stdout, (str, bytes)):
            partial = (
                error.stdout.decode("utf-8", errors="replace")
                if isinstance(error.stdout, bytes)
                else error.stdout
            )
        else:
            try:
                if proc.stdout is not None:
                    partial = proc.stdout.read() or ""
                    proc.stdout.close()
            except Exception:
                partial = ""
        try:
            proc.wait(timeout=5)
        except Exception:
            pass
        message = f"timeout after {remaining}s"
        log_path.write_text((partial or "") + f"\n\n[{message}]\n", encoding="utf-8")
        return 124, message, str(log_path)


def run_scenario(
    scenario_id: str,
    *,
    name: str,
    command: Sequence[str],
    cwd: Path,
    timeout: int,
    out_dir: Path,
    env: Mapping[str, str] | None = None,
    overall_deadline: float | None = None,
    hard: bool = True,
    na_reason: str | None = None,
) -> ScenarioResult:
    """Execute a hard scenario command, or record explicit N/A."""
    if na_reason is not None:
        return scenario_result(
            scenario_id,
            name=name,
            result=RESULT_NA,
            reason=na_reason,
            hard=hard,
        )
    started = time.monotonic()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    log_path = out_dir / f"{scenario_id}-{stamp}.log"
    code, detail, log = run_command(
        command,
        cwd=cwd,
        timeout=timeout,
        log_path=log_path,
        env=env,
        overall_deadline=overall_deadline,
    )
    seconds = int(time.monotonic() - started)
    result = RESULT_PASS if code == 0 else RESULT_FAIL
    return scenario_result(
        scenario_id,
        name=name,
        result=result,
        seconds=seconds,
        detail=detail,
        log=log,
        hard=hard,
    )


def run_callable_scenario(
    scenario_id: str,
    *,
    name: str,
    action: Callable[[], None],
    out_dir: Path,
    hard: bool = True,
    na_reason: str | None = None,
) -> ScenarioResult:
    """Run a Python action as a scenario (for layout checks, preflight, etc.)."""
    if na_reason is not None:
        return scenario_result(
            scenario_id,
            name=name,
            result=RESULT_NA,
            reason=na_reason,
            hard=hard,
        )
    started = time.monotonic()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    log_path = out_dir / f"{scenario_id}-{stamp}.log"
    try:
        action()
        log_path.write_text("ok\n", encoding="utf-8")
        return scenario_result(
            scenario_id,
            name=name,
            result=RESULT_PASS,
            seconds=int(time.monotonic() - started),
            log=str(log_path),
            hard=hard,
        )
    except Exception as error:  # noqa: BLE001 — surface any check failure as red cell
        log_path.write_text(f"{type(error).__name__}: {error}\n", encoding="utf-8")
        return scenario_result(
            scenario_id,
            name=name,
            result=RESULT_FAIL,
            seconds=int(time.monotonic() - started),
            detail=str(error),
            log=str(log_path),
            hard=hard,
        )


def gradle_ui_force_args() -> list[str]:
    """Flags that force live UI tests to re-execute rather than cache-only pass."""
    return [
        "--rerun-tasks",
        "--no-build-cache",
        "--console=plain",
    ]


WAYLAND_RESTORE_TOKEN_PREFIX = "wayland-screencast-restore-token-"
WAYLAND_HELPER_NAME = "spectre-wayland-helper"


def is_linux_wayland_portal_session(
    env: Mapping[str, str] | None = None,
    *,
    display_is_pure_x11: Callable[[str], bool] | None = None,
) -> bool:
    """True when this process should use seated Wayland portal warmup."""
    environ = env or os.environ
    override = (environ.get("SPECTRE_CAPTURE_BACKEND") or "").strip().lower()
    if override in {"x11", "xorg", "xvfb"}:
        return False
    if override in {"wayland", "portal"}:
        return True
    session = (environ.get("XDG_SESSION_TYPE") or "").strip().lower()
    if session == "x11":
        return False
    display = (environ.get("DISPLAY") or "").strip()
    probe = display_is_pure_x11 or linux_display_is_pure_x11
    if display and probe(display):
        # Nested xvfb-run on a Wayland login inherits WAYLAND_DISPLAY, but windows live
        # on the Xvfb DISPLAY. Real Wayland+XWayland (XWAYLAND extension) stays portal.
        return False
    wayland_display = (environ.get("WAYLAND_DISPLAY") or "").strip()
    runtime_dir = (environ.get("XDG_RUNTIME_DIR") or "").strip()
    if wayland_display:
        if runtime_dir:
            return (Path(runtime_dir) / wayland_display).exists()
        return True
    return session == "wayland"


def linux_display_is_pure_x11(display: str) -> bool:
    """Best-effort Xvfb / non-XWayland probe. Unknown displays are not treated as Xvfb."""
    if not display:
        return False
    try:
        completed = subprocess.run(
            ["xdpyinfo", "-display", display],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    output = completed.stdout or ""
    if completed.returncode != 0 or not output.strip():
        return False
    return "XWAYLAND" not in output.upper()


def portal_token_warmup_skip_reason(
    env: Mapping[str, str] | None = None,
    system: str | None = None,
) -> str | None:
    """Hard N/A reason when ScreenCast restore-token warmup cannot run."""
    host = system or platform.system()
    if host != "Linux":
        return f"{host} does not use xdg-desktop-portal ScreenCast restore tokens"
    if not is_linux_wayland_portal_session(env):
        return "Linux Wayland portal token warmup requires a real Wayland session"
    return None


def linux_wayland_helper_candidates(root: Path) -> list[Path]:
    machine = platform.machine()
    arch = "aarch64" if machine in {"arm64", "aarch64"} else "x86_64"
    return [
        root
        / "recording"
        / "build"
        / "generated"
        / "waylandHelper"
        / "native"
        / "linux"
        / arch
        / WAYLAND_HELPER_NAME,
        root / "recording" / "native" / "linux" / "target" / "release" / WAYLAND_HELPER_NAME,
        root
        / "recording-linux"
        / "build"
        / "resources"
        / "main"
        / "native"
        / "linux"
        / arch
        / WAYLAND_HELPER_NAME,
    ]


def linux_wayland_helper_path(root: Path) -> Path | None:
    for candidate in linux_wayland_helper_candidates(root):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    return None


def prepare_linux_portal_token_env(root: Path, out_dir: Path) -> dict[str, str]:
    """Pin a restore-token dir, and a helper binary when one is already staged."""
    token_dir = Path(out_dir) / "wayland-restore-tokens"
    token_dir.mkdir(parents=True, exist_ok=True)
    token_dir.chmod(0o700)
    env = {"SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": str(token_dir)}
    helper = linux_wayland_helper_path(root)
    if helper is not None:
        env["SPECTRE_WAYLAND_HELPER"] = str(helper)
    return env


def assert_linux_portal_tokens_captured(env: Mapping[str, str]) -> None:
    token_dir = Path(env.get("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR") or "")
    if not token_dir.is_dir():
        raise RuntimeError(
            "ScreenCast restore token dir missing: "
            f"{token_dir or '(SPECTRE_WAYLAND_RESTORE_TOKEN_DIR unset)'}"
        )
    captured = [
        path
        for path in token_dir.glob(f"{WAYLAND_RESTORE_TOKEN_PREFIX}*")
        if path.is_file() and path.read_text(encoding="utf-8").strip()
    ]
    if not captured:
        raise RuntimeError(
            f"no ScreenCast restore token under {token_dir}; approve Share + Remember "
            "once during portal-token-warmup"
        )


def xvfb_prefix(system: str | None = None) -> list[str]:
    system = system or platform.system()
    if system == "Linux" and not os.environ.get("DISPLAY", "").strip():
        if _which("xvfb-run"):
            return ["xvfb-run", "-a"]
    return []


def host_cli_package_target(system: str | None = None, machine: str | None = None) -> str:
    system = system or platform.system()
    machine = machine or platform.machine()
    if system == "Darwin":
        return "MacosArm64" if machine in ("arm64", "aarch64") else "MacosX64"
    if system == "Linux":
        return "LinuxX64"
    if system == "Windows":
        return "WindowsX64"
    raise RuntimeError(f"unsupported host for CLI packaging: {system}/{machine}")


def assert_mcp_fixture_e2e_executed(root: Path) -> None:
    """Fail closed if DaemonFixture MCP e2e did not execute attach/op/detach.

    JUnit assumption-skips still yield Gradle exit 0; tools/list-only is not enough for
    hard mcp-sdk-flow pass after #414. Looks for the MCP fixture testcase in
    cli/build/test-results/test/TEST-*.xml and rejects skipped/failed runs.
    """
    results_dir = root / "cli" / "build" / "test-results" / "test"
    if not results_dir.is_dir():
        raise RuntimeError(
            f"MCP e2e test results missing under {results_dir} (Gradle did not write JUnit XML)"
        )
    xml_files = sorted(results_dir.glob("TEST-*.xml"))
    if not xml_files:
        raise RuntimeError(f"MCP e2e produced no TEST-*.xml under {results_dir}")
    found_mcp = False
    for path in xml_files:
        try:
            raw = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise RuntimeError(f"unable to read {path}: {exc}") from exc
        if "MCP stdio drives" not in raw:
            continue
        found_mcp = True
        if "<skipped" in raw:
            raise RuntimeError(
                "MCP fixture e2e was skipped (assumption); hard pass requires "
                "attach→op→detach on a headed display (Windows also needs "
                "-Pspectre.agent.attachE2e.allowWindows=true)"
            )
        # failures/errors attributes on testsuite
        for attr in ("failures", "errors"):
            match = re.search(rf'{attr}="(\d+)"', raw)
            if match and int(match.group(1)) > 0:
                raise RuntimeError(
                    f"MCP fixture e2e reported {attr}={match.group(1)} in {path.name}"
                )
        break
    if not found_mcp:
        raise RuntimeError(f"MCP fixture e2e testcase not found in JUnit XML under {results_dir}")


def packaged_cli_executable(root: Path, system: str | None = None, machine: str | None = None) -> Path:
    system = system or platform.system()
    machine = machine or platform.machine()
    if system == "Darwin":
        if machine in ("arm64", "aarch64"):
            return root / "cli/build/construo/macosArm64/Spectre.app/Contents/MacOS/spectre"
        return root / "cli/build/construo/macosX64/Spectre.app/Contents/MacOS/spectre"
    if system == "Linux":
        return root / "cli/build/construo/linuxX64/roast/spectre"
    if system == "Windows":
        roast = root / "cli/build/construo/windowsX64/roast"
        for name in ("spectre.exe", "Spectre.exe"):
            candidate = roast / name
            if candidate.is_file():
                return candidate
        return roast / "spectre.exe"
    raise RuntimeError(f"unsupported host for CLI executable: {system}")

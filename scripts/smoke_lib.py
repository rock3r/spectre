#!/usr/bin/env python3
"""Shared report model, preflight, skip policy, and process helpers for release smoke.

Used by scripts/release-smoke.py (macOS/Linux). Windows PowerShell emits the same
schemaVersion report shape; keep field names stable across both entrypoints.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
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
    "macos-tcc",
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
    # Experimental desktop input coordination (#459). Delta hard cells on macOS, Windows, and
    # Linux Xorg/Xvfb per the spectre-release skill. The coordinator protocol/lifecycle tests are
    # deterministic and display-independent, so these stay hard on every OS entrypoint (including
    # Windows SSH) rather than n/a like the WGC/attach-screenshot cells.
    "input-coord-contention",
    "input-coord-cancellation",
    "input-coord-quarantine",
    "input-coord-revoke",
    "input-coord-forced-recovery",
    "input-coord-junit-pertest",
    # The headed half of the gate: two real RobotDriver JVMs, which every cell above avoids
    # constructing (that is why they all pass headless and under SSH). Driven by its own e2e on
    # hosts that can run one, and by an operator signature on hosts that cannot -- never by the
    # coordinator-protocol cells.
    "input-coord-headed-robot",
)

HEADED_ROBOT_SCENARIO_ID: str = "input-coord-headed-robot"
HEADED_ROBOT_NAME: str = "Headed two-JVM Robot contention"

# The automated proof (#491). Two forked JVMs, each with RobotDriver(InputLeasePolicy.Required),
# type one distinguishable block into the same focused text field of one fixture window; a field
# holding `ababab` is the failure being ruled out, and it is exactly what a lease-level proof
# cannot observe. Both entrypoints run this task and both verify the testcase in the JUnit XML.
HEADED_ROBOT_GRADLE_TASK: str = ":sample-desktop:headedRobotContentionTest"
HEADED_ROBOT_RESULTS_SUBPATH: str = "sample-desktop/build/test-results/headedRobotContentionTest"
HEADED_ROBOT_TESTCASE: str = (
    "two Robot JVMs typing into one field never interleave their keystrokes"
)
_HEADED_ROBOT_SAMPLE_TEST_DIR: str = (
    "sample-desktop/src/test/kotlin/dev/sebastiano/spectre/sample"
)
HEADED_ROBOT_TEST_SOURCE: str = f"{_HEADED_ROBOT_SAMPLE_TEST_DIR}/HeadedRobotContentionTest.kt"
HEADED_ROBOT_PROBE_SOURCE: str = f"{_HEADED_ROBOT_SAMPLE_TEST_DIR}/HeadedRobotContentionProbe.kt"

HEADED_ROBOT_AUTOMATED_DETAIL: str = (
    f"automated headed two-JVM Robot contention passed ({HEADED_ROBOT_GRADLE_TASK}): two forked "
    "RobotDriver(InputLeasePolicy.Required) JVMs released from one barrier typed into a single "
    "focused field without interleaving"
)

# Deliberately blunt: a reader skimming a report must not mistake this row for "covered by the
# automated cells". Both entrypoints emit this exact sentence; the contract tests pin it on each.
HEADED_ROBOT_MISSING_EVIDENCE: str = (
    "headed two-JVM Robot contention was NOT recorded: this host did not run the automated "
    "proof and no operator evidence was supplied; the coordinator-protocol cells do not prove "
    "real-input non-interleaving, so this blocks the tag on any OS whose release notes claim "
    "headed coordination"
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


# --- macOS TCC preflight (#502) ------------------------------------------------
# Mirrors MacOsTccGuard (Accessibility osascript) and MacOsScreenCaptureAccess
# (helper --mode preflight / CGPreflightScreenCaptureAccess). Release smoke is
# fail-closed: Denied, Locked, and Unknown all block before ./gradlew check.
# Never read TCC.db. Never invoke helper request / guide-permissions.

TCC_GRANTED = "granted"
TCC_DENIED = "denied"
TCC_LOCKED = "locked"
TCC_UNKNOWN = "unknown"
TCC_NOT_APPLICABLE = "not_applicable"

ACCESSIBILITY_OSASCRIPT = (
    'tell application "System Events" to return name of first process'
)
SCREENCAPTURE_HELPER_NAME = "spectre-screencapture"
SCREENCAPTURE_PREFLIGHT_TIMEOUT_SECONDS = 15
SCREENCAPTURE_HELPER_EXIT_NOT_GRANTED = 6
SCREENCAPTURE_HELPER_OVERRIDE_ENV = "SPECTRE_SCREENCAPTURE_HELPER"
SCREENCAPTURE_HELPER_DIR_PROPERTY = "spectre.recording.screencapturekit.helperDir"
# Same effective -D precedence as the java launcher / HotSpot (JDK 21+):
# _JAVA_OPTIONS appends and wins, JDK_JAVA_OPTIONS prepends onto the command
# line and beats JAVA_TOOL_OPTIONS. GRADLE_OPTS is not read by child JVMs.
SCREENCAPTURE_HELPER_DIR_JVM_ENVS = (
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "JAVA_TOOL_OPTIONS",
)
MACOS_TCC_BLOCKED_REASON = (
    "blocked by macos-tcc failure; grant Accessibility and Screen Recording to the "
    "wrapping app and Screen Recording to Spectre Capture Helper, then quit/relaunch "
    "and ./gradlew --stop"
)
IOREG_CONSOLE_LOCK_TIMEOUT_SECONDS = 3
WRAPPING_SCREEN_RECORDING_PROBE_SIZE_PX = 32
WRAPPING_SCREEN_RECORDING_RGB_MASK = 0x00FFFFFF
SCREENCAPTURE_HELPER_DISPLAY_NAME = "Spectre Capture Helper"
SCREENCAPTURE_HELPER_APP_NAME = "SpectreCaptureHelper.app"
ASSEMBLE_SCREENCAPTURE_HELPER_TASK = ":recording:assembleScreenCaptureKitHelper"
ASSEMBLE_SCREENCAPTURE_HELPER_TIMEOUT_SECONDS = 180
MACOS_TCC_ACCESSIBILITY_GUIDANCE = (
    "macOS attributes Robot input to the wrapping app that launched this process "
    "(Terminal, iTerm2, IntelliJ IDEA, Grok Bot, Claude.app, etc.) — not to the JVM "
    "binary itself. Grant System Settings → Privacy & Security → Accessibility to that "
    "wrapping app, then fully quit and relaunch it (macOS only re-evaluates TCC at "
    "process start). Run `./gradlew --stop` so Gradle daemons started before the grant "
    "are not reused."
)
MACOS_TCC_SCREEN_RECORDING_GUIDANCE = (
    f"Native capture TCC accrues to {SCREENCAPTURE_HELPER_DISPLAY_NAME} "
    f"({SCREENCAPTURE_HELPER_APP_NAME}), not the wrapping Terminal/IDE. Grant System "
    f"Settings → Privacy & Security → Screen & System Audio Recording to that helper "
    f"row. If the helper is not on disk yet, run `./gradlew "
    f"{ASSEMBLE_SCREENCAPTURE_HELPER_TASK}` (or set SPECTRE_SCREENCAPTURE_HELPER)."
)
MACOS_TCC_WRAPPING_SCREEN_RECORDING_GUIDANCE = (
    "RobotDriver.screenshot() and junit-live captures require Screen Recording for the "
    "wrapping Terminal/IDE, in addition to Spectre Capture Helper. Grant System "
    "Settings → Privacy & Security → Screen & System Audio Recording to that wrapping "
    "app, then fully quit and relaunch it and run `./gradlew --stop`."
)


def macos_tcc_skip_reason(system: str | None = None) -> str | None:
    """Hard N/A reason when macOS Screen Recording / Accessibility TCC does not apply."""
    host = system or platform.system()
    if host != "Darwin":
        return f"{host} does not use macOS Screen Recording / Accessibility TCC"
    return None


def evaluate_macos_tcc(
    *,
    accessibility: str,
    screen_recording: str,
    wrapping_screen_recording: str = TCC_GRANTED,
) -> None:
    """Fail closed unless helper, wrapping-app, and Accessibility probes pass."""
    problems: list[str] = []
    accessibility_failed = False
    helper_failed = False
    wrapping_failed = False
    for line in _tcc_status_problem(
        "Accessibility",
        accessibility,
        allow_locked=False,
        grant_target="the wrapping app",
    ):
        problems.append(line)
        accessibility_failed = True
    for line in _tcc_status_problem(
        "Screen Recording",
        screen_recording,
        allow_locked=True,
        grant_target=f"{SCREENCAPTURE_HELPER_DISPLAY_NAME} ({SCREENCAPTURE_HELPER_APP_NAME})",
    ):
        problems.append(line)
        helper_failed = True
    for line in _tcc_status_problem(
        "wrapping-app Screen Recording",
        wrapping_screen_recording,
        allow_locked=True,
        grant_target="the wrapping app (RobotDriver.screenshot / junit-live)",
    ):
        problems.append(line)
        wrapping_failed = True
    if accessibility_failed:
        problems.append(MACOS_TCC_ACCESSIBILITY_GUIDANCE)
    if helper_failed:
        problems.append(MACOS_TCC_SCREEN_RECORDING_GUIDANCE)
    if wrapping_failed:
        problems.append(MACOS_TCC_WRAPPING_SCREEN_RECORDING_GUIDANCE)
    if problems:
        raise RuntimeError("\n".join(problems))


def _tcc_status_problem(
    label: str,
    status: str,
    *,
    allow_locked: bool,
    grant_target: str,
) -> list[str]:
    if status in {TCC_GRANTED, TCC_NOT_APPLICABLE}:
        return []
    settings = (
        "System Settings → Privacy & Security → Accessibility"
        if label == "Accessibility"
        else "System Settings → Privacy & Security → Screen & System Audio Recording"
    )
    if allow_locked and status == TCC_LOCKED:
        return [
            "macOS screen capture is unavailable because the console session is locked. "
            "Unlock the screen and retry before treating this as a TCC denial."
        ]
    if status == TCC_DENIED:
        return [f"macOS {label} TCC is denied. Grant {settings} to {grant_target}."]
    extra = ""
    if label == "Screen Recording":
        extra = (
            f" Stage the helper with `./gradlew {ASSEMBLE_SCREENCAPTURE_HELPER_TASK}` "
            f"and probe the runtime install under "
            f"~/Library/Application Support/spectre/helpers/{SCREENCAPTURE_HELPER_NAME}/"
            f"{SCREENCAPTURE_HELPER_APP_NAME}. A missing or non-executable "
            f"{SCREENCAPTURE_HELPER_OVERRIDE_ENV} is fail-closed."
        )
    return [
        f"could not determine macOS {label} TCC permission state (probe was unknown/"
        f"inconclusive: {status}). Release smoke is fail-closed — grant {settings} "
        f"to {grant_target}.{extra}"
    ]


def macos_console_lock_status(ioreg_output: str) -> bool | None:
    """Same parse as MacOsTccGuard.macOsConsoleLockStatus."""
    match = re.search(r'"IOConsoleLocked"\s*=\s*(Yes|No)', ioreg_output)
    if match is None:
        return None
    return match.group(1) == "Yes"


def probe_macos_console_locked(
    runner: Callable[[], str | None] | None = None,
) -> bool | None:
    """True when ioreg reports IOConsoleLocked=Yes (MacOsTccGuard)."""
    output = runner() if runner is not None else _run_ioreg_console_lock()
    if output is None:
        return None
    return macos_console_lock_status(output)


def _run_ioreg_console_lock() -> str | None:
    try:
        completed = subprocess.run(
            ["/usr/sbin/ioreg", "-n", "Root", "-d", "1", "-r"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=IOREG_CONSOLE_LOCK_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return completed.stdout or ""


def interpret_wrapping_screen_recording_pixels(
    pixels: Sequence[int],
    *,
    width: int,
    height: int,
) -> str:
    """Same all-black origin-region rule as MacOsTccGuard.robotScreenRecordingProbe."""
    if width <= 1 or height <= 1:
        return TCC_UNKNOWN
    if len(pixels) < width * height:
        return TCC_UNKNOWN
    for rgb in pixels:
        if (int(rgb) & WRAPPING_SCREEN_RECORDING_RGB_MASK) != 0:
            return TCC_GRANTED
    return TCC_DENIED


def probe_macos_wrapping_screen_recording(
    *,
    runner: Callable[[], str | None] | None = None,
    console_locked_probe: Callable[[], bool | None] | None = None,
    system: str | None = None,
) -> str:
    """Wrapping-app Screen Recording for RobotDriver.screenshot / junit-live."""
    if macos_tcc_skip_reason(system=system) is not None:
        return TCC_NOT_APPLICABLE
    if (console_locked_probe or probe_macos_console_locked)() is True:
        return TCC_LOCKED
    if runner is not None:
        raw = runner()
        if raw is None:
            return TCC_UNKNOWN
        return _wrapping_status_from_bmp(raw.encode("latin1") if isinstance(raw, str) else raw)

    bmp = _capture_wrapping_screen_recording_bmp()
    if bmp is None:
        return TCC_UNKNOWN
    return _wrapping_status_from_bmp(bmp)


def _wrapping_status_from_bmp(data: bytes) -> str:
    parsed = _bmp_rgb_pixels(data)
    if parsed is None:
        return TCC_UNKNOWN
    pixels, width, height = parsed
    return interpret_wrapping_screen_recording_pixels(pixels, width=width, height=height)


def _bmp_rgb_pixels(data: bytes) -> tuple[list[int], int, int] | None:
    if len(data) < 30 or data[:2] != b"BM":
        return None
    offset = struct.unpack_from("<I", data, 10)[0]
    width, height = struct.unpack_from("<ii", data, 18)
    bits = struct.unpack_from("<H", data, 28)[0]
    height = abs(height)
    if width <= 0 or height <= 0 or bits not in {24, 32} or offset < 0:
        return None
    row_size = ((width * bits + 31) // 32) * 4
    pixels: list[int] = []
    for row in range(height):
        start = offset + row * row_size
        for col in range(width):
            pixel_at = start + col * (bits // 8)
            if pixel_at + 2 >= len(data):
                return None
            blue, green, red = data[pixel_at], data[pixel_at + 1], data[pixel_at + 2]
            pixels.append((red << 16) | (green << 8) | blue)
    return pixels, width, height


def _capture_wrapping_screen_recording_bmp() -> bytes | None:
    path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".bmp", delete=False) as handle:
            path = Path(handle.name)
        completed = subprocess.run(
            [
                "screencapture",
                "-x",
                "-t",
                "bmp",
                "-R",
                f"0,0,{WRAPPING_SCREEN_RECORDING_PROBE_SIZE_PX},"
                f"{WRAPPING_SCREEN_RECORDING_PROBE_SIZE_PX}",
                str(path),
            ],
            timeout=SCREENCAPTURE_PREFLIGHT_TIMEOUT_SECONDS,
            check=False,
        )
        if completed.returncode != 0 or not path.is_file():
            return None
        return path.read_bytes()
    except (OSError, subprocess.TimeoutExpired):
        return None
    finally:
        if path is not None:
            path.unlink(missing_ok=True)


def probe_macos_accessibility(
    runner: Callable[[], tuple[int, str] | None] | None = None,
) -> str:
    """Same semantics as `MacOsTccGuard.osascriptAccessibilityProbe`."""
    result = runner() if runner is not None else _run_osascript_accessibility()
    if result is None:
        return TCC_UNKNOWN
    exit_code, output = result
    text = (output or "").strip()
    if exit_code == 0 and text:
        return TCC_GRANTED
    if exit_code != 0 and "not allowed assistive access" in text.lower():
        return TCC_DENIED
    return TCC_UNKNOWN


def _run_osascript_accessibility() -> tuple[int, str] | None:
    try:
        completed = subprocess.run(
            ["osascript", "-e", ACCESSIBILITY_OSASCRIPT],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=3,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return int(completed.returncode), completed.stdout or ""


def macos_screencapture_staged_helper(root: Path) -> Path:
    return (
        root
        / "recording"
        / "build"
        / "generated"
        / "screenCaptureHelper"
        / "native"
        / "macos"
        / SCREENCAPTURE_HELPER_APP_NAME
        / "Contents"
        / "MacOS"
        / SCREENCAPTURE_HELPER_NAME
    )


def macos_screencapture_runtime_helper(
    home: Path | None = None,
    helper_dir: Path | None = None,
    environ: Mapping[str, str] | None = None,
) -> Path:
    """Same extract path as HelperBinaryExtractor (helperDir property or default)."""
    if helper_dir is not None:
        return (
            helper_dir
            / SCREENCAPTURE_HELPER_APP_NAME
            / "Contents"
            / "MacOS"
            / SCREENCAPTURE_HELPER_NAME
        )
    resolved = home
    if resolved is None:
        resolved = macos_screencapture_jvm_user_home(environ)
    if resolved is None:
        resolved = macos_screencapture_query_java_user_home(environ)
    if resolved is None:
        raise InvalidScreencaptureHelperDir(
            "could not resolve JVM user.home for the default helper extract path; "
            "set -Duser.home to an absolute path or install a usable java"
        )
    return (
        resolved
        / "Library"
        / "Application Support"
        / "spectre"
        / "helpers"
        / SCREENCAPTURE_HELPER_NAME
        / SCREENCAPTURE_HELPER_APP_NAME
        / "Contents"
        / "MacOS"
        / SCREENCAPTURE_HELPER_NAME
    )


class InvalidScreencaptureHelperDir(RuntimeError):
    """helperDir is configured but cannot be mirrored by the smoke preflight."""


@dataclass(frozen=True)
class ScreencaptureHelperDirSetting:
    """One JVM-option helperDir token: defined-and-blank is not the same as absent."""

    defined: bool
    path: Path | None = None


def parse_jvm_system_property(text: str, name: str) -> ScreencaptureHelperDirSetting:
    """Last -Dname token in JVM option text."""
    prefix = f"-D{name}"
    try:
        tokens = shlex.split(text, posix=True)
    except ValueError as error:
        raise InvalidScreencaptureHelperDir(
            f"{name} is set but cannot be parsed: {error}"
        ) from error
    last: Path | None = None
    found = False
    for token in tokens:
        if token == prefix:
            raise InvalidScreencaptureHelperDir(
                f"{name} is set without a value; cannot mirror the child JVM"
            )
        if token.startswith(f"{prefix}="):
            found = True
            value = token[len(prefix) + 1 :]
            last = Path(value) if value.strip() else None
    return ScreencaptureHelperDirSetting(defined=found, path=last)


def parse_screencapture_helper_dir_property(text: str) -> ScreencaptureHelperDirSetting:
    """Last -Dspectre.recording.screencapturekit.helperDir token in JVM option text."""
    return parse_jvm_system_property(text, SCREENCAPTURE_HELPER_DIR_PROPERTY)


def macos_screencapture_configured_helper_dir(
    environ: Mapping[str, str] | None = None,
    *,
    root: Path | None = None,
) -> Path | None:
    """Resolve helperDir from env vars the child JVM actually inherits."""
    del root  # gradle.properties is Gradle-JVM only; JavaExec does not forward it.
    env = environ if environ is not None else os.environ
    for name in SCREENCAPTURE_HELPER_DIR_JVM_ENVS:
        raw = env.get(name, "")
        if not raw:
            continue
        parsed = parse_screencapture_helper_dir_property(raw)
        if not parsed.defined:
            continue
        if parsed.path is None:
            return None
        if not parsed.path.is_absolute():
            raise InvalidScreencaptureHelperDir(
                f"{SCREENCAPTURE_HELPER_DIR_PROPERTY} must be an absolute path "
                f"(got {str(parsed.path)!r} from {name}). Relative values resolve "
                "against different working directories in smoke vs Gradle."
            )
        return parsed.path
    return None


def macos_screencapture_jvm_user_home(
    environ: Mapping[str, str] | None = None,
) -> Path | None:
    """Absolute -Duser.home the child JVM will use, if one is defined."""
    env = environ if environ is not None else os.environ
    for name in SCREENCAPTURE_HELPER_DIR_JVM_ENVS:
        raw = env.get(name, "")
        if not raw:
            continue
        parsed = parse_jvm_system_property(raw, "user.home")
        if not parsed.defined:
            continue
        if parsed.path is None or not parsed.path.is_absolute():
            raise InvalidScreencaptureHelperDir(
                f"user.home must be an absolute path (got {parsed.path!r} from {name})"
            )
        return parsed.path
    return None


def parse_java_show_settings_property(text: str, name: str) -> Path | None:
    """Parse `name = value` from `java -XshowSettings:properties` output."""
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped.startswith(f"{name} ="):
            continue
        value = stripped.split("=", 1)[1].strip()
        if not value:
            return None
        path = Path(value)
        if not path.is_absolute():
            raise InvalidScreencaptureHelperDir(
                f"{name} from java -XshowSettings:properties must be absolute "
                f"(got {value!r})"
            )
        return path
    return None


def macos_screencapture_query_java_user_home(
    environ: Mapping[str, str] | None = None,
) -> Path | None:
    """Effective JVM user.home, including the launcher default (not Path.home())."""
    env = os.environ if environ is None else {**os.environ, **dict(environ)}
    java = shutil.which("java", path=env.get("PATH"))
    java_home = env.get("JAVA_HOME", "").rstrip("/")
    if java is None and java_home:
        candidate = Path(java_home) / "bin" / "java"
        if candidate.is_file():
            java = str(candidate)
    if not java:
        return None
    try:
        completed = subprocess.run(
            [java, "-XshowSettings:properties", "-version"],
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=20,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return parse_java_show_settings_property(completed.stdout or "", "user.home")


class InvalidScreencaptureHelperOverride(RuntimeError):
    """SPECTRE_SCREENCAPTURE_HELPER is set but is not an executable helper."""


def macos_screencapture_override_path(
    environ: Mapping[str, str] | None = None,
) -> Path | None:
    """Authoritative SPECTRE_SCREENCAPTURE_HELPER. Absolute paths only."""
    raw = (environ if environ is not None else os.environ).get(
        SCREENCAPTURE_HELPER_OVERRIDE_ENV, ""
    )
    # HelperBinaryExtractor uses isNotBlank() then Path.of(untrimmed). Do not strip:
    # " /abs/helper" is a different path than "/abs/helper".
    if not raw.strip():
        return None
    override = Path(raw)
    # HelperBinaryExtractor.resolveOverrideExecutable accepts a relative Path.of()
    # value, but smoke CWD (repo root) and Gradle JavaExec CWD (module dir) differ.
    # Fail closed instead of probing a different helper than later capture cells.
    if not override.is_absolute():
        raise InvalidScreencaptureHelperOverride(
            f"{SCREENCAPTURE_HELPER_OVERRIDE_ENV} must be an absolute path "
            f"(got {raw!r}). Relative values resolve against different working "
            f"directories in smoke vs Gradle. Point it at {SCREENCAPTURE_HELPER_NAME} "
            f"or {SCREENCAPTURE_HELPER_APP_NAME}, or unset it."
        )
    # Same shapes as HelperBinaryExtractor.resolveOverrideExecutable: the path
    # itself, a .app bundle, or <dir>/Contents/MacOS/spectre-screencapture.
    if override.is_dir() and override.name.endswith(".app"):
        resolved = override / "Contents" / "MacOS" / SCREENCAPTURE_HELPER_NAME
    elif not _is_executable_helper(override):
        nested = override / "Contents" / "MacOS" / SCREENCAPTURE_HELPER_NAME
        resolved = nested if _is_executable_helper(nested) else override
    else:
        resolved = override
    if _is_executable_helper(resolved):
        return resolved
    raise InvalidScreencaptureHelperOverride(
        f"{SCREENCAPTURE_HELPER_OVERRIDE_ENV} points at {raw!r} but no executable "
        f"helper was found. Point it at {SCREENCAPTURE_HELPER_NAME} or "
        f"{SCREENCAPTURE_HELPER_APP_NAME}, or unset it."
    )


def macos_screencapture_helper_candidates(
    root: Path,
    *,
    home: Path | None = None,
) -> list[Path]:
    candidates: list[Path] = []
    try:
        override = macos_screencapture_override_path()
    except InvalidScreencaptureHelperOverride:
        return []
    if override is not None:
        return [override]
    try:
        helper_dir = macos_screencapture_configured_helper_dir(root=root)
        jvm_home = macos_screencapture_jvm_user_home()
        resolved_home = home if home is not None else jvm_home
        if resolved_home is not None or helper_dir is not None or platform.system() == "Darwin":
            candidates.append(
                macos_screencapture_runtime_helper(resolved_home, helper_dir=helper_dir)
            )
    except InvalidScreencaptureHelperDir:
        return []
    return candidates


def macos_screencapture_helper_path(
    root: Path,
    *,
    home: Path | None = None,
) -> Path | None:
    try:
        override = macos_screencapture_override_path()
    except InvalidScreencaptureHelperOverride:
        return None
    if override is not None:
        return override
    for candidate in macos_screencapture_helper_candidates(root, home=home):
        if _is_executable_helper(candidate):
            return candidate
    return None


def parse_screencapture_preflight_json(stdout: str) -> str:
    """Parse the first nonblank helper line, matching MacOsScreenCaptureAccess.runHelper."""
    line = next((text for raw in stdout.splitlines() if (text := raw.strip())), "")
    if not line:
        return TCC_UNKNOWN
    try:
        payload = json.loads(line)
    except json.JSONDecodeError:
        return TCC_UNKNOWN
    if not isinstance(payload, Mapping) or "granted" not in payload:
        return TCC_UNKNOWN
    return TCC_GRANTED if payload.get("granted") is True else TCC_DENIED


def interpret_screencapture_preflight(exit_code: int, stdout: str) -> str:
    """Match MacOsScreenCaptureAccess.runHelper: exit 0 granted, exit 6 denied."""
    parsed = parse_screencapture_preflight_json(stdout)
    if exit_code == 0 and parsed == TCC_GRANTED:
        return TCC_GRANTED
    if exit_code == SCREENCAPTURE_HELPER_EXIT_NOT_GRANTED and parsed == TCC_DENIED:
        return TCC_DENIED
    return TCC_UNKNOWN


def probe_macos_screen_recording(
    *,
    root: Path | None = None,
    runner: Callable[[], tuple[int, str] | None] | None = None,
    helper_path: Path | None = None,
    invoke_helper: Callable[[list[str]], tuple[int, str] | None] | None = None,
    ensure_helper: Callable[[], Path | None] | None = None,
    refresh_helper: Callable[[], Path | None] | None = None,
    home: Path | None = None,
    console_locked_probe: Callable[[], bool | None] | None = None,
) -> str:
    """Run MacOsScreenCaptureAccess.preflight via the helper; never request/guide."""
    if (console_locked_probe or probe_macos_console_locked)() is True:
        return TCC_LOCKED
    if runner is not None:
        result = runner()
        if result is None:
            return TCC_UNKNOWN
        return interpret_screencapture_preflight(result[0], result[1])

    try:
        override = macos_screencapture_override_path()
    except InvalidScreencaptureHelperOverride:
        return TCC_UNKNOWN
    if override is None:
        try:
            macos_screencapture_configured_helper_dir(root=root)
        except InvalidScreencaptureHelperDir:
            return TCC_UNKNOWN
    resolved = override if override is not None else helper_path
    if resolved is None and ensure_helper is not None:
        resolved = ensure_helper()
    if resolved is None and root is not None:
        resolved = macos_screencapture_helper_path(root, home=home)
    if resolved is None:
        return TCC_UNKNOWN

    invoker = invoke_helper or _run_screencapture_preflight
    status = _invoke_screencapture_preflight(resolved, invoker)
    if (
        status == TCC_UNKNOWN
        and override is None
        and refresh_helper is not None
    ):
        refreshed = refresh_helper()
        if refreshed is not None:
            status = _invoke_screencapture_preflight(refreshed, invoker)
    return status


def macos_screencapture_helper_fingerprint(executable: Path) -> str | None:
    """SHA-256 of the helper .app tree, matching HelperAppBundleMaterial."""
    if not executable.is_file():
        return None
    app = macos_screencapture_app_root(executable)
    root = app if app is not None and app.is_dir() else executable
    digest = hashlib.sha256()
    if root.is_file():
        digest.update(root.name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(root.read_bytes())
        return digest.hexdigest()
    files = sorted(path for path in root.rglob("*") if path.is_file())
    for path in files:
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def macos_screencapture_app_root(executable: Path) -> Path | None:
    cursor = executable
    for _ in range(6):
        if cursor.name.endswith(".app"):
            return cursor
        parent = cursor.parent
        if parent == cursor:
            break
        cursor = parent
    return None


def install_macos_screencapture_helper(
    staged_executable: Path,
    dest_executable: Path,
) -> Path | None:
    """Copy the staged .app onto the HelperBinaryExtractor runtime path."""
    src_app = macos_screencapture_app_root(staged_executable)
    dest_app = macos_screencapture_app_root(dest_executable)
    if src_app is None or dest_app is None or not src_app.is_dir():
        dest_executable.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(staged_executable, dest_executable)
        dest_executable.chmod(0o755)
        return dest_executable if _is_executable_helper(dest_executable) else None
    if dest_app.exists():
        shutil.rmtree(dest_app)
    dest_app.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src_app, dest_app)
    if dest_executable.is_file():
        dest_executable.chmod(0o755)
    return dest_executable if _is_executable_helper(dest_executable) else None


def _invoke_screencapture_preflight(
    helper: Path,
    invoker: Callable[[list[str]], tuple[int, str] | None],
) -> str:
    invoked = invoker([str(helper), "--mode", "preflight"])
    if invoked is None:
        return TCC_UNKNOWN
    return interpret_screencapture_preflight(invoked[0], invoked[1])


def ensure_macos_screencapture_helper(
    root: Path,
    *,
    assemble: Callable[[], int] | None = None,
    home: Path | None = None,
    install: Callable[[Path, Path], Path | None] | None = None,
    refresh: bool = False,
) -> Path | None:
    """Install the helper to the runtime TCC path, assembling first when needed."""
    try:
        override = macos_screencapture_override_path()
    except InvalidScreencaptureHelperOverride:
        return None
    if override is not None:
        return override
    try:
        helper_dir = macos_screencapture_configured_helper_dir(root=root)
        jvm_home = macos_screencapture_jvm_user_home()
        resolved_home = home if home is not None else jvm_home
        runtime = macos_screencapture_runtime_helper(resolved_home, helper_dir=helper_dir)
    except InvalidScreencaptureHelperDir:
        return None
    assembler = (
        assemble if assemble is not None else (lambda: _assemble_screencapture_helper(root))
    )
    if assembler() != 0:
        return None
    staged = macos_screencapture_staged_helper(root)
    if not _is_executable_helper(staged):
        return None
    if (
        _is_executable_helper(runtime)
        and not refresh
        and macos_screencapture_helper_fingerprint(runtime)
        == macos_screencapture_helper_fingerprint(staged)
    ):
        return runtime

    if helper_dir is None and resolved_home is None and platform.system() != "Darwin":
        return None
    installer = install if install is not None else install_macos_screencapture_helper
    return installer(staged, runtime)


def _is_executable_helper(path: Path) -> bool:
    return path.is_file() and os.access(path, os.X_OK)


def _assemble_screencapture_helper(root: Path) -> int:
    gradlew = root / "gradlew"
    if not gradlew.is_file():
        return 127
    try:
        completed = subprocess.run(
            [str(gradlew), ASSEMBLE_SCREENCAPTURE_HELPER_TASK, "--console=plain"],
            cwd=root,
            timeout=ASSEMBLE_SCREENCAPTURE_HELPER_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return 124
    return int(completed.returncode)


def _run_screencapture_preflight(argv: Sequence[str]) -> tuple[int, str] | None:
    try:
        completed = subprocess.run(
            list(argv),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=SCREENCAPTURE_PREFLIGHT_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return int(completed.returncode), completed.stdout or ""


def require_macos_tcc(
    *,
    accessibility_probe: Callable[[], str] | None = None,
    screen_recording_probe: Callable[[], str] | None = None,
    wrapping_screen_recording_probe: Callable[[], str] | None = None,
    system: str | None = None,
) -> None:
    """Fail closed for live Robot / capture cells when Darwin TCC is missing."""
    if macos_tcc_skip_reason(system=system) is not None:
        return
    evaluate_macos_tcc(
        accessibility=(accessibility_probe or probe_macos_accessibility)(),
        screen_recording=(screen_recording_probe or probe_macos_screen_recording)(),
        wrapping_screen_recording=(
            wrapping_screen_recording_probe or probe_macos_wrapping_screen_recording
        )(),
    )


def fill_blocked_remaining(
    results: Sequence[ScenarioResult],
    *,
    reason: str,
) -> list[ScenarioResult]:
    """Keep existing rows and hard-N/A any missing required IDs (fail-fast abort)."""
    filled = list(results)
    seen = {row.id for row in filled}
    for scenario_id in REQUIRED_SCENARIO_IDS:
        if scenario_id in seen:
            continue
        filled.append(
            scenario_result(
                scenario_id,
                name=f"{scenario_id} (not executed)",
                result=RESULT_NA,
                reason=reason,
                hard=True,
            )
        )
    return filled


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
        # Empty values unset inherited overrides (e.g. SPECTRE_WAYLAND_RESTORE_TOKEN_PATH).
        for key, value in list(merged_env.items()):
            if key in env and value == "":
                del merged_env[key]

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


WAYLAND_RESTORE_TOKEN_PREFIX = "wayland-rd-restore-token-"
WAYLAND_PORTAL_SMOKE_TOKEN_KEY = "rd-monitor-embedded"
WAYLAND_PORTAL_WARMUP_TOKEN_KEYS: tuple[str, ...] = (WAYLAND_PORTAL_SMOKE_TOKEN_KEY,)
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
    session_dir = Path(out_dir) / "wayland-session"
    session_dir.mkdir(parents=True, exist_ok=True)
    session_dir.chmod(0o700)
    env = {
        "SPECTRE_WAYLAND_RESTORE_TOKEN_DIR": str(token_dir),
        # PATH takes precedence over DIR in the helper. Unset any inherited override.
        "SPECTRE_WAYLAND_RESTORE_TOKEN_PATH": "",
        "SPECTRE_WAYLAND_SESSION_DIR": str(session_dir),
    }
    helper = linux_wayland_helper_path(root)
    if helper is not None:
        env["SPECTRE_WAYLAND_HELPER"] = str(helper)
    return env


def linux_portal_token_path(
    env: Mapping[str, str],
    token_key: str = WAYLAND_PORTAL_SMOKE_TOKEN_KEY,
) -> Path:
    token_dir = Path(env.get("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR") or "")
    return token_dir / f"{WAYLAND_RESTORE_TOKEN_PREFIX}{token_key}"


def assert_linux_portal_tokens_captured(
    env: Mapping[str, str],
    *,
    token_keys: Sequence[str] = WAYLAND_PORTAL_WARMUP_TOKEN_KEYS,
    expected_mtime_ns: Mapping[str, int] | int | None = None,
) -> None:
    token_dir = Path(env.get("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR") or "")
    if not token_dir.is_dir():
        raise RuntimeError(
            "RemoteDesktop restore token dir missing: "
            f"{token_dir or '(SPECTRE_WAYLAND_RESTORE_TOKEN_DIR unset)'}"
        )
    missing: list[str] = []
    stale: list[str] = []
    for token_key in token_keys:
        path = linux_portal_token_path(env, token_key)
        if not path.is_file() or not path.read_text(encoding="utf-8").strip():
            missing.append(path.name)
            continue
        if expected_mtime_ns is None:
            continue
        required = (
            expected_mtime_ns.get(token_key)
            if isinstance(expected_mtime_ns, Mapping)
            else expected_mtime_ns
        )
        if required is not None and path.stat().st_mtime_ns < required:
            stale.append(path.name)
    if missing:
        raise RuntimeError(
            "missing RemoteDesktop restore token(s) "
            f"{', '.join(missing)} under {token_dir}; approve Share + Remember / "
            "Allow remote interaction during portal-token-warmup"
        )
    if stale:
        raise RuntimeError(
            "RemoteDesktop restore token(s) not refreshed by this warmup: "
            f"{', '.join(stale)}; later cells may prompt again"
        )


def linux_toolchain_path(environ: Mapping[str, str] | None = None) -> str:
    """PATH that includes rustup/cargo for non-login SSH and xvfb-run children.

    Login shells source ~/.cargo/env. Release-smoke is started from a non-login
    SSH session whose PATH is /usr/bin:..., so `:recording:buildWaylandHelper`
    dies with "command 'cargo'" when `--rerun-tasks` rebuilds the helper.
    """
    overlay = dict(environ or {})
    current = overlay.get("PATH", os.environ.get("PATH", ""))
    parts = [p for p in current.split(os.pathsep) if p]
    cargo_home_raw = overlay.get("CARGO_HOME") or os.environ.get("CARGO_HOME")
    cargo_home = Path(cargo_home_raw) if cargo_home_raw else Path.home() / ".cargo"
    cargo_bin = cargo_home / "bin"
    if cargo_bin.is_dir() and str(cargo_bin) not in parts:
        parts.insert(0, str(cargo_bin))
    return os.pathsep.join(parts)


def apply_linux_toolchain_path(
    environ: MutableMapping[str, str] | None = None,
) -> str:
    """Write [linux_toolchain_path] into PATH on the given mapping (default os.environ)."""
    target: MutableMapping[str, str] = os.environ if environ is None else environ
    target["PATH"] = linux_toolchain_path(target)
    return target["PATH"]


def xvfb_prefix(system: str | None = None) -> list[str]:
    system = system or platform.system()
    if system == "Linux" and not os.environ.get("DISPLAY", "").strip():
        if _which("xvfb-run"):
            return ["xvfb-run", "-a"]
    return []


def robot_xvfb_prefix(system: str | None = None) -> list[str]:
    """Always wrap JBR/AWT Robot cells in Xvfb on Linux.

    Unlike [xvfb_prefix], this ignores an inherited seated DISPLAY. Helper
    ScreenCast restore tokens do not cover Robot / Remote Desktop, so Robot
    cells must not reuse the compositor seat after portal warmup.
    """
    system = system or platform.system()
    if system == "Linux" and _which("xvfb-run"):
        return ["xvfb-run", "-a"]
    return []


def robot_xvfb_unavailable_reason(system: str | None = None) -> str | None:
    """Hard-fail reason when Linux Robot cells cannot leave the compositor seat."""
    system = system or platform.system()
    if system != "Linux":
        return None
    if _which("xvfb-run"):
        return None
    return (
        "xvfb-run is required for JBR Robot cells on Linux; running them on a "
        "seated Wayland display pops a new ScreenCast/Remote Desktop dialog per JVM"
    )


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


_COMPOSE_AUTOMATOR_KT = Path(
    "core/src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt"
)
_MOVE_TO_FUN = re.compile(
    r"^\s*(?:public\s+)?(?:suspend\s+)?fun\s+moveTo\s*\(",
    re.MULTILINE,
)
_MOVE_BY_FUN = re.compile(
    r"^\s*(?:public\s+)?(?:suspend\s+)?fun\s+moveBy\s*\(",
    re.MULTILINE,
)


def pointer_move_api_skip_reason(root: Path) -> str | None:
    """Hard N/A until ComposeAutomator exposes moveTo/moveBy (#433)."""
    path = Path(root) / _COMPOSE_AUTOMATOR_KT
    if not path.is_file():
        return "ComposeAutomator.kt missing; cannot prove #433 pointer-move verbs"
    text = path.read_text(encoding="utf-8")
    missing: list[str] = []
    if _MOVE_TO_FUN.search(text) is None:
        missing.append("moveTo")
    if _MOVE_BY_FUN.search(text) is None:
        missing.append("moveBy")
    if not missing:
        return None
    return "ComposeAutomator." + "/".join(missing) + " not shipped (#433)"


def assert_pointer_move_live_executed(root: Path) -> None:
    """Fail closed if PointerMoveLive validation was skipped or never ran.

    Gradle --tests can exit 0 when JUnit assumptions skip every method. Hard
    pointer-move pass requires the live hover test to actually execute.
    """
    results_dir = root / "sample-desktop" / "build" / "test-results" / "validationTest"
    if not results_dir.is_dir():
        raise RuntimeError(
            f"pointer-move test results missing under {results_dir} "
            "(Gradle did not write validationTest JUnit XML)"
        )
    xml_files = sorted(results_dir.glob("TEST-*.xml"))
    if not xml_files:
        raise RuntimeError(f"pointer-move produced no TEST-*.xml under {results_dir}")
    found = False
    for path in xml_files:
        try:
            raw = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise RuntimeError(f"unable to read {path}: {exc}") from exc
        if "PointerMoveLive" not in raw:
            continue
        found = True
        if "<skipped" in raw:
            raise RuntimeError(
                "PointerMoveLive validation was skipped (assumption); hard pass "
                "requires a headed display and shipped moveTo/moveBy (#433)"
            )
        for attr in ("failures", "errors"):
            match = re.search(rf'{attr}="(\d+)"', raw)
            if match and int(match.group(1)) > 0:
                raise RuntimeError(
                    f"PointerMoveLive reported {attr}={match.group(1)} in {path.name}"
                )
        break
    if not found:
        raise RuntimeError(
            f"PointerMoveLive testcase not found in JUnit XML under {results_dir}"
        )


# --- Experimental desktop input coordination (#459 release gate) ---------------------------------

# The coordinator's own deterministic + forked-process tests are the automated proof for each
# gate bullet in docs/RELEASE-SMOKE.md. They do not need a display, so the cells stay hard on
# every OS. A missing test source means the experimental surface was removed or moved, which is a
# regression the smoke must surface (mirrors pointer_move_api_skip_reason).
_INPUT_COORDINATOR_SERVER_TEST_SOURCE = Path(
    "input-coordinator-server/src/test/kotlin/dev/sebastiano/spectre/input/server/"
    "LocalCoordinatorServerTest.kt"
)
_INPUT_ISOLATION_TEST_SOURCE = Path(
    "testing/src/test/kotlin/dev/sebastiano/spectre/testing/InputIsolationLifecycleTest.kt"
)


def input_coordination_missing_surface(root: Path) -> str | None:
    """Detail for a coordination cell whose proof source is gone, else None.

    Absence is a **fail**, never a reasoned `n/a`. [hard_failures] ignores a reasoned `n/a`, so
    returning one here would let deleting or renaming a single test file turn all six coordination
    cells green-by-omission and still print "ALL HARD SCENARIOS PASSED" — the whole gate defeated
    by a rename. Legitimately dropping the feature means affirmatively re-scoping the release:
    remove the IDs from [REQUIRED_SCENARIO_IDS] and update docs/RELEASE-SMOKE.md, which is a
    reviewable change rather than a silent one.
    """
    for source in (_INPUT_COORDINATOR_SERVER_TEST_SOURCE, _INPUT_ISOLATION_TEST_SOURCE):
        if not (Path(root) / source).is_file():
            return (
                f"experimental input coordination proof missing ({source.name}); this is a "
                "failure, not a skip: if the surface was genuinely removed or graduated, "
                "re-scope the release by dropping the input-coord-* IDs from "
                "REQUIRED_SCENARIO_IDS and updating docs/RELEASE-SMOKE.md"
            )
    return None


def assert_junit_testcases_passed(
    results_dir: Path,
    *,
    needles: Sequence[str],
    cell: str,
) -> None:
    """Fail closed unless every named JUnit testcase actually executed and passed.

    Gradle `--tests` exits 0 when a filter matches nothing or when every method assumption-skips,
    so a hard coordination pass must prove the specific testcase ran (present, not `<skipped/>`,
    and its testsuite reported no failures/errors). Mirrors assert_pointer_move_live_executed and
    assert_mcp_fixture_e2e_executed.
    """
    results_dir = Path(results_dir)
    if not results_dir.is_dir():
        raise RuntimeError(
            f"{cell} test results missing under {results_dir} (Gradle did not write JUnit XML)"
        )
    xml_files = sorted(results_dir.glob("TEST-*.xml"))
    if not xml_files:
        raise RuntimeError(f"{cell} produced no TEST-*.xml under {results_dir}")
    for needle in needles:
        found = False
        for path in xml_files:
            try:
                raw = path.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                raise RuntimeError(f"unable to read {path}: {exc}") from exc
            if needle not in raw:
                continue
            found = True
            if "<skipped" in raw:
                raise RuntimeError(
                    f"{cell} testcase '{needle}' was skipped (assumption); hard pass "
                    "requires the coordination test to execute"
                )
            for attr in ("failures", "errors"):
                match = re.search(rf'{attr}="(\d+)"', raw)
                if match and int(match.group(1)) > 0:
                    raise RuntimeError(
                        f"{cell} reported {attr}={match.group(1)} for '{needle}' in {path.name}"
                    )
            break
        if not found:
            raise RuntimeError(
                f"{cell} testcase '{needle}' not found in JUnit XML under {results_dir}"
            )


def headed_robot_missing_surface(root: Path) -> str | None:
    """Detail when the headed Robot contention e2e's sources are gone, else None.

    Same fail-closed rule as [input_coordination_missing_surface], and kept separate from it so a
    change to one proof cannot quietly re-colour the other's cells. Deleting the e2e does not fall
    back to the operator signature: the signature covers a *host* that cannot run the proof, not
    the proof ceasing to exist. Dropping the claim means re-scoping the release on purpose.
    """
    for source in (HEADED_ROBOT_TEST_SOURCE, HEADED_ROBOT_PROBE_SOURCE):
        if not (Path(root) / source).is_file():
            name = source.rsplit("/", maxsplit=1)[-1]
            return (
                f"headed two-JVM Robot contention proof missing ({name}); this is a failure, "
                "not a skip, and an operator signature does not substitute for a deleted test: "
                "if the surface was genuinely removed or graduated, re-scope the release by "
                "dropping input-coord-headed-robot from REQUIRED_SCENARIO_IDS and updating "
                "docs/RELEASE-SMOKE.md"
            )
    return None


def headed_robot_cell(
    evidence: str | None,
    automated: ScenarioResult | None = None,
    unavailable_reason: str | None = None,
    missing_surface: str | None = None,
) -> ScenarioResult:
    """Hard cell for the headed two-JVM Robot contention proof (#459, #484, #491).

    Four inputs, one precedence, and the order is the whole design:

    1. `missing_surface` — the e2e's sources are gone. Fail; nothing else is consulted.
    2. `automated` — the e2e actually ran on this host. Its verdict wins, and a **red** run wins
       over operator evidence too: a signature is a claim about a run nobody can re-read, and an
       observed interleave is a measurement. Letting the note outrank the measurement would put
       the escape hatch exactly where it must never be.
    3. `evidence` — nothing ran here, but an operator attests they ran it on a real desktop. The
       host's `unavailable_reason` is recorded alongside so the report says *why* automation was
       skipped rather than leaving a reader to assume it passed.
    4. Neither — **fail**, never a reasoned `n/a`. [hard_failures] deliberately ignores an `n/a`
       that carries a reason, so recording this as `n/a` would let the runner exit 0 and print
       "ALL HARD SCENARIOS PASSED" without the headed proof the release gate requires. The
       `input-coord-*` cells never fill this gap: they construct no RobotDriver and pass headless.
    """
    if missing_surface:
        return scenario_result(
            HEADED_ROBOT_SCENARIO_ID,
            name=HEADED_ROBOT_NAME,
            result=RESULT_FAIL,
            detail=missing_surface,
            hard=True,
        )
    recorded = (evidence or "").strip()
    if automated is not None:
        if automated.result == RESULT_PASS:
            detail = HEADED_ROBOT_AUTOMATED_DETAIL
            if recorded:
                detail = f"{detail}; operator evidence also recorded: {recorded}"
            return scenario_result(
                HEADED_ROBOT_SCENARIO_ID,
                name=HEADED_ROBOT_NAME,
                result=RESULT_PASS,
                seconds=automated.seconds,
                detail=detail,
                log=automated.log,
                hard=True,
            )
        detail = (
            "the automated headed two-JVM Robot contention proof "
            f"({HEADED_ROBOT_GRADLE_TASK}) went red on this host: {automated.detail}"
        )
        if recorded:
            detail = (
                f"{detail}. Operator evidence was also supplied ({recorded}) and does not "
                "override an observed failure."
            )
        return scenario_result(
            HEADED_ROBOT_SCENARIO_ID,
            name=HEADED_ROBOT_NAME,
            result=RESULT_FAIL,
            seconds=automated.seconds,
            detail=detail,
            log=automated.log,
            hard=True,
        )
    blocked = (unavailable_reason or "").strip()
    if recorded:
        detail = f"operator evidence: {recorded}"
        if blocked:
            detail = f"{detail} (the automated proof did not run here: {blocked})"
        return scenario_result(
            HEADED_ROBOT_SCENARIO_ID,
            name=HEADED_ROBOT_NAME,
            result=RESULT_PASS,
            detail=detail,
            hard=True,
        )
    detail = HEADED_ROBOT_MISSING_EVIDENCE
    if blocked:
        detail = f"{detail}. The automated proof did not run here: {blocked}"
    return scenario_result(
        HEADED_ROBOT_SCENARIO_ID,
        name=HEADED_ROBOT_NAME,
        result=RESULT_FAIL,
        detail=detail,
        hard=True,
    )


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

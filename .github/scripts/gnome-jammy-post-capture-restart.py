#!/usr/bin/env python3
"""Independently observe a Jammy portal grant after restarting its owned helper.

This optional verification cell is deliberately conservative: it only signals the
single helper proven by the preceding live-capture evidence and never unlinks a
socket, reads a token, or changes a permission store.
"""
from __future__ import annotations

from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
from typing import Callable


FUSER = Callable[[Path], list[int]]
PROC = Path("/proc")


def sanitize(text: str) -> str:
    """Redact likely credential values without hiding useful diagnostics."""
    return re.sub(
        r"(?i)(authorization|bearer|restore[_ -]?token)([\"=: ]+)[^\s\",}]+",
        r"\1\2<redacted>",
        text,
    )


def socket_owners(path: Path) -> list[int]:
    result = subprocess.run(
        ["fuser", str(path)], text=True, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, check=False,
    )
    if result.returncode not in (0, 1):
        raise RuntimeError(f"fuser failed for the exact session socket ({result.returncode})")
    return sorted({int(value) for value in result.stdout.split()})


def status_fields(path: Path) -> dict[str, str]:
    fields = {}
    for line in path.read_text().splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            fields[key] = value.strip()
    return fields


def private_runtime(path: Path, socket: Path) -> None:
    resolved = path.resolve()
    if socket.resolve().parent != resolved:
        raise RuntimeError("session socket is not directly inside the recorded private runtime directory")
    info = resolved.stat()
    if info.st_uid != os.getuid() or not stat.S_ISDIR(info.st_mode):
        raise RuntimeError("runtime directory is not a directory owned by the current UID")
    if stat.S_IMODE(info.st_mode) & 0o077:
        raise RuntimeError("runtime directory is accessible to group or other users")


def verify_helper(
    pid: int,
    socket: Path,
    expected_exe: Path | None,
    runtime: Path,
    proc_root: Path = PROC,
    fuser: FUSER = socket_owners,
) -> dict:
    """Prove all identity constraints before a caller may signal ``pid``."""
    if pid <= 1:
        raise RuntimeError("refusing an invalid helper PID")
    private_runtime(runtime, socket)
    process = proc_root / str(pid)
    fields = status_fields(process / "status")
    uids = [int(item) for item in fields.get("Uid", "").split()]
    if len(uids) != 4 or any(uid != os.getuid() for uid in uids):
        raise RuntimeError("helper does not belong entirely to the current Linux UID")
    state = fields.get("State", "")
    if state.startswith(("Z", "X", "x")):
        raise RuntimeError(f"helper is not live (state {state!r})")
    executable = (process / "exe").resolve(strict=True)
    if expected_exe is not None and executable != expected_exe.resolve():
        raise RuntimeError(f"helper executable differs from recorded executable: {executable}")
    if executable.name != "spectre-wayland-helper":
        raise RuntimeError(f"unexpected helper executable: {executable}")
    command = [part.decode(errors="replace") for part in
               (process / "cmdline").read_bytes().split(b"\0") if part]
    if command.count("--session") != 1:
        raise RuntimeError("helper command line is not the direct --session process")
    owners = fuser(socket)
    if owners != [pid]:
        raise RuntimeError(f"exact session socket is not exclusively owned by original PID {pid}: {owners}")
    return {
        "pid": pid,
        "uid": os.getuid(),
        "executable": str(executable),
        "commandMode": "--session",
        "processState": state,
        "socketOwners": owners,
        "runtimeDirectory": str(runtime.resolve()),
    }


def pid_matches(pid: int, executable: str, proc_root: Path = PROC) -> bool:
    try:
        return str((proc_root / str(pid) / "exe").resolve(strict=True)) == executable
    except (FileNotFoundError, PermissionError):
        return False


def wait_for_disconnect(
    pid: int,
    socket: Path,
    executable: str,
    timeout: float = 10,
    fuser: FUSER = socket_owners,
) -> dict:
    deadline = time.monotonic() + timeout
    while True:
        matches = pid_matches(pid, executable)
        owners = fuser(socket)
        if not matches and pid not in owners:
            return {
                "processStillMatches": False,
                "socketOwners": owners,
                "socketPathExists": socket.exists(),
                "socketPathIsSocket": socket.is_socket(),
                "socketListening": bool(owners),
            }
        if time.monotonic() >= deadline:
            raise RuntimeError("owned helper did not disconnect within the bounded SIGTERM wait")
        time.sleep(0.1)


def run_cli(command: list[str], timeout: float = 70) -> tuple[dict, int, str]:
    result = subprocess.run(
        command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=timeout, check=False,
    )
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"packaged CLI emitted non-JSON output (exit {result.returncode})") from error
    return value, result.returncode, sanitize(result.stderr)


def marker_facts(path: Path) -> dict:
    """Observe metadata only; token content is intentionally never opened."""
    try:
        info = path.stat()
    except FileNotFoundError:
        return {"exists": False}
    return {
        "exists": True,
        "isRegularFile": stat.S_ISREG(info.st_mode),
        "mode": f"{stat.S_IMODE(info.st_mode):04o}",
        "ownedByCurrentUid": info.st_uid == os.getuid(),
        "sizeBytes": info.st_size,
    }


def portal_versions() -> dict[str, int]:
    import dbus
    bus = dbus.SessionBus()
    proxy = bus.get_object("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")
    properties = dbus.Interface(proxy, "org.freedesktop.DBus.Properties")
    return {name.rsplit(".", 1)[-1]: int(properties.Get(name, "version")) for name in (
        "org.freedesktop.portal.RemoteDesktop", "org.freedesktop.portal.ScreenCast")}


def load_portal():
    path = Path(__file__).with_name("gnome-portal-dialog-warmup.py")
    spec = importlib.util.spec_from_file_location("gnome_portal_dialog_warmup_restart", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load real portal picker validator from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validated_picker_count(portal, backend_pid: int) -> int:
    import pyatspi
    count = 0
    apps = [app for app in pyatspi.Registry.getDesktop(0)
            if app is not None and app.get_process_id() == backend_pid]
    for app in apps:
        for node in portal.descendants(app):
            if portal.semantics.is_dialog(node.getRoleName(), node.name):
                facts = portal.facts(list(portal.descendants(node)))
                if portal.semantics.profile(facts) is not None:
                    count += 1
    return count


def request_with_observation(cli: Path, artifact: Path) -> tuple[dict, int, str, dict]:
    """Request once, recording positive AT-SPI readiness rather than inferred absence."""
    import dbus
    portal = load_portal()
    backend = portal.backend_pid(dbus.SessionBus())
    process = subprocess.Popen(
        [str(cli), "permissions", "request", "--json", "--timeout", "60"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    started = time.monotonic()
    samples: list[dict] = []
    selected = None
    picker_seen = False
    deadline = started + 65
    try:
        while time.monotonic() < deadline:
            now = time.monotonic()
            count = validated_picker_count(portal, backend)
            samples.append({"seconds": round(now - started, 6), "validatedPickerCount": count})
            if count:
                if count != 1:
                    raise RuntimeError(f"ambiguous validated picker count: {count}")
                picker_seen = True
                selected = portal.accept_dialog(backend, artifact)
                break
            if process.poll() is not None:
                break
            time.sleep(0.1)
        if process.poll() is None:
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
    except BaseException:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                # Do not broaden the experiment into signaling any helper.
                raise RuntimeError("request CLI did not stop after its own SIGTERM")
        raise
    stdout, stderr = process.communicate()
    if not picker_seen:
        intervals = [right["seconds"] - left["seconds"] for left, right in zip(samples, samples[1:])]
        if len(samples) < 2 or any(interval > 0.5 for interval in intervals):
            raise RuntimeError("picker absence is ambiguous: insufficient subsecond positive samples")
    try:
        value = json.loads(stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("permissions request did not return JSON") from error
    observation = {
        "secondPickerAppeared": picker_seen,
        "observation": "actual-positive" if picker_seen else "actual-independent-negative",
        "backendPid": backend,
        "samples": samples,
        "selectedSource": selected,
    }
    return value, int(process.returncode), sanitize(stderr), observation


def main() -> None:
    if sys.platform != "linux":
        raise RuntimeError("this verifier is Linux-only")
    artifacts = Path(os.environ["SMOKE_ARTIFACTS"]).resolve()
    evidence_path = artifacts / "cli-recording-live-evidence.json"
    evidence = json.loads(evidence_path.read_text())
    original_pid = int(evidence["helperPid"])
    socket = Path(evidence["grant"]["sessionSocket"]).resolve()
    marker = Path(evidence["grant"]["tokenPath"]).resolve()
    cli = Path(evidence["packagedLauncher"]).resolve()
    runtime = socket.parent
    before_marker = marker_facts(marker)

    original = verify_helper(original_pid, socket, None, runtime)
    check, check_exit, check_stderr = run_cli([str(cli), "permissions", "check", "--json"])
    # Re-verify immediately before the only destructive operation (TOCTOU boundary).
    verify_helper(original_pid, socket, Path(original["executable"]), runtime)
    os.kill(original_pid, signal.SIGTERM)
    disconnected = wait_for_disconnect(original_pid, socket, original["executable"])

    gone_marker = marker_facts(marker)
    versions = portal_versions()
    request, request_exit, request_stderr, picker = request_with_observation(
        cli, artifacts / "post-restart-request-atspi-tree.json")
    if request_exit != 0 or not (request.get("granted") and request.get("sessionActive")):
        raise RuntimeError(f"post-restart request did not return an active grant (exit {request_exit})")
    if request.get("sessionSocket") != str(socket) or request.get("tokenPath") != str(marker):
        raise RuntimeError("post-restart request changed the original socket or token path")
    owners = socket_owners(socket)
    if len(owners) != 1 or owners[0] == original_pid:
        raise RuntimeError(f"expected exactly one new helper owner, found {owners}")
    replacement = verify_helper(owners[0], socket, Path(original["executable"]), runtime)

    result = {
        "schemaVersion": 1,
        "completedAtUtc": datetime.now(timezone.utc).isoformat(),
        "scope": "same-process-helper-restart-only",
        "verifierPid": os.getpid(),
        "originalHelper": original,
        "disconnect": disconnected,
        "replacementHelper": replacement,
        "sameOriginalPaths": True,
        "nonmutatingCheck": {"exitCode": check_exit, "response": check,
                              "sanitizedStderr": check_stderr},
        "marker": {"before": before_marker, "afterHelperGone": gone_marker,
                   "afterRequest": marker_facts(marker)},
        "portalInterfaceVersions": versions,
        "request": {"exitCode": request_exit, "response": request,
                    "sanitizedStderr": request_stderr},
        "pickerObservation": picker,
        "limitations": [
            "This is one independent same-session observation on the disposable Jammy VM.",
            "It makes no across-machine, personal-reset, or unobservable lifetime claim.",
        ],
    }
    output = artifacts / "gnome-jammy-post-capture-restart.json"
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"completed": False, "error": sanitize(str(error)),
                          "errorType": type(error).__name__}, sort_keys=True))
        raise SystemExit(1)

#!/usr/bin/env python3
"""Prove a packaged CLI grant governs the native-tested public crop probe."""
from __future__ import annotations

import atexit
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

import dbus
from gi.repository import GLib

_portal_path = Path(__file__).with_name("gnome-portal-dialog-warmup.py")
_portal_spec = importlib.util.spec_from_file_location("gnome_portal_dialog_warmup", _portal_path)
if _portal_spec is None or _portal_spec.loader is None:
    raise RuntimeError(f"cannot load portal verifier from {_portal_path}")
portal = importlib.util.module_from_spec(_portal_spec)
_portal_spec.loader.exec_module(portal)


def extract_release(archive: Path, destination: Path) -> tuple[Path, Path]:
    """Extract a trusted ZIP without traversal and preserve executable modes."""
    destination.mkdir()
    root = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for member in source.infolist():
            target = (destination / member.filename).resolve()
            if target != root and root not in target.parents:
                raise RuntimeError(f"unsafe ZIP member: {member.filename}")
            source.extract(member, destination)
            mode = (member.external_attr >> 16) & 0o777
            if mode and target.exists():
                target.chmod(mode)
    launchers = list(destination.glob("*/bin/spectre"))
    runtimes = list(destination.glob("*/runtime/bin/java"))
    if len(launchers) != 1 or len(runtimes) != 1:
        raise RuntimeError(f"unexpected release layout: {launchers=}, {runtimes=}")
    if not os.access(launchers[0], os.X_OK) or not os.access(runtimes[0], os.X_OK):
        raise RuntimeError("packaged launcher/runtime lost executable permissions")
    return launchers[0].resolve(), runtimes[0].resolve()


def expected_disappearance(error: BaseException) -> bool:
    """Recognize only the AT-SPI errors produced while a real GTK node vanishes."""
    message = str(error).lower()
    if isinstance(error, GLib.GError):
        return ("atspi_error" in message and
                any(text in message for text in ("no longer exists", "does not exist")))
    return isinstance(error, (AttributeError, TypeError)) and "nonetype" in message


def dialog_count(root) -> int:
    """Count only complete GNOME 42/46 pickers; reject title-only lookalikes."""
    count = 0
    pending = [root]
    while pending:
        node = pending.pop()
        if node is None:
            raise AttributeError("NoneType AT-SPI node disappeared")
        children = list(node)
        if portal.semantics.is_dialog(node.getRoleName(), node.name):
            dialog_nodes = list(portal.descendants(node))
            if portal.semantics.profile(portal.facts(dialog_nodes)) is not None:
                count += 1
            # The dialog subtree has already been consumed and cannot contain a second picker.
            continue
        pending.extend(children)
    return count


def observe_dialogs(pid: int, output: Path, ready: Path) -> None:
    # A separate process owns this pyatspi Registry; the grant driver never shares its singleton.
    import pyatspi
    with output.open("a", buffering=1) as receipt:
        while True:
            try:
                apps = [app for app in pyatspi.Registry.getDesktop(0)
                        if app is not None and app.get_process_id() == pid]
                count = sum(dialog_count(app) for app in apps)
                receipt.write(json.dumps({"kind": "sample", "monotonic": time.monotonic(),
                                          "backendPid": pid, "pickerCount": count}) + "\n")
                if not ready.exists():
                    ready.write_text("real-sample\n")
            except (GLib.GError, AttributeError, TypeError) as error:
                if not expected_disappearance(error):
                    raise
                # This is explicitly not a sample: never turn a missing object into a fake zero.
                receipt.write(json.dumps({"kind": "transient-removed-node",
                                          "monotonic": time.monotonic(),
                                          "backendPid": pid,
                                          "errorType": type(error).__name__}) + "\n")
            time.sleep(.1)


def sanitized(text: str) -> str:
    return re.sub(r'(?i)(authorization|bearer|restore[_ -]?token)(["=: ]+)[^\s",}]+',
                  r'\1\2<redacted>', text)


def receipt(path: Path, phase: str, **facts: object) -> None:
    with path.open("a") as output:
        output.write(json.dumps({"phase": phase, "monotonicNs": time.monotonic_ns(),
                                 "timestampUtc": datetime.now(timezone.utc).isoformat(),
                                 **facts}, sort_keys=True) + "\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_json(command: list[str], stdout_path: Path, stderr_path: Path) -> tuple[dict, int]:
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout_path.write_text(result.stdout)
    stderr_path.write_text(sanitized(result.stderr))
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}")
    value = json.loads(result.stdout)
    stdout_path.write_text(json.dumps(value, indent=2) + "\n")
    return value, result.returncode


def main() -> None:
    artifacts = Path(os.environ["SMOKE_ARTIFACTS"])
    receipts = artifacts / "cli-live-phase-receipts.jsonl"
    archive = Path(os.environ["SPECTRE_CLI_ZIP"])
    cli, runtime_java = extract_release(archive, Path(os.environ["SPECTRE_CLI_HOME"]))
    version, _ = run_json([str(cli), "version", "--json"],
                          artifacts / "cli-version.json", artifacts / "cli-version.stderr")
    java_version = subprocess.run([str(runtime_java), "-version"], text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)

    backend = portal.backend_pid(dbus.SessionBus())
    observation_file = artifacts / "portal-observations.jsonl"
    observer_ready = artifacts / "portal-observer.ready"
    watcher = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--observe",
                                str(backend), str(observation_file), str(observer_ready)])

    def stop_watcher() -> None:
        if watcher.poll() is None:
            watcher.terminate()
            try:
                watcher.wait(timeout=5)
            except subprocess.TimeoutExpired:
                watcher.kill()
                watcher.wait(timeout=5)

    atexit.register(stop_watcher)
    for _ in range(100):
        if observer_ready.is_file():
            break
        if watcher.poll() is not None:
            raise RuntimeError("independent portal observer exited")
        time.sleep(.1)
    else:
        raise RuntimeError("independent portal observer produced no real sample")

    observer_before_grant = time.monotonic()
    request_out = artifacts / "cli-permissions-request.json"
    request_err = artifacts / "cli-permissions-request.stderr"
    request = None
    selected = None
    try:
        with request_out.open("w") as out, request_err.open("w") as err:
            request = subprocess.Popen(
                [str(cli), "permissions", "request", "--json", "--timeout", "60"],
                stdout=out, stderr=err)
            time.sleep(.5)
            if request.poll() not in (None, 0):
                raise RuntimeError(f"packaged CLI request exited early ({request.returncode})")
            selected = portal.accept_dialog(backend, artifacts / "cli-request-atspi-tree.json")
            if request.wait(timeout=45) != 0:
                raise RuntimeError(f"packaged CLI request failed; see {request_err}")
    finally:
        if request is not None and request.poll() is None:
            request.terminate()
            try:
                request.wait(timeout=5)
            except subprocess.TimeoutExpired:
                request.kill()
                request.wait(timeout=5)
        if request_err.exists():
            request_err.write_text(sanitized(request_err.read_text()))
    proof = json.loads(request_out.read_text())
    request_out.write_text(json.dumps(proof, indent=2) + "\n")
    observer_after_grant = time.monotonic()
    if watcher.poll() is not None:
        raise RuntimeError("independent portal observer stopped during grant")
    if not (proof.get("granted") and proof.get("sessionActive") and proof.get("tokenStored")):
        raise RuntimeError(f"CLI did not report an active persistent grant: {proof}")
    marker = Path(proof["tokenPath"])
    socket = Path(proof["sessionSocket"])
    if not marker.is_file() or not socket.is_socket():
        raise RuntimeError("CLI grant token or active session socket is missing")
    helper_pids = [int(value) for value in subprocess.check_output(
        ["fuser", str(socket)], text=True, stderr=subprocess.DEVNULL).split()]
    if len(helper_pids) != 1 or not Path(f"/proc/{helper_pids[0]}").is_dir():
        raise RuntimeError(f"expected one live owner of the session socket: {helper_pids}")
    receipt(receipts, "permissions-request", exitCode=request.returncode, **{
        key: proof[key] for key in ("granted", "sessionActive", "tokenStored")})

    checked, check_exit = run_json(
        [str(cli), "permissions", "check", "--json"],
        artifacts / "cli-permissions-check.json", artifacts / "cli-permissions-check.stderr")
    if not (checked.get("granted") and checked.get("sessionActive") and
            checked.get("tokenStored") and checked.get("sessionSocket") == str(socket) and
            checked.get("tokenPath") == str(marker)):
        raise RuntimeError(f"CLI check did not describe the same active grant: {checked}")
    receipt(receipts, "permissions-check", exitCode=check_exit, **{
        key: checked[key] for key in ("granted", "sessionActive", "tokenStored")})

    evidence_path = artifacts / "cli-recording-live-evidence.json"
    evidence = {
        "candidateSha": os.environ.get("CANDIDATE_SHA"),
        "releaseZip": archive.name,
        "releaseZipSha256": sha256(archive),
        "packagedCliVersion": version,
        "packagedLauncher": str(cli),
        "packagedRuntimeJava": str(runtime_java),
        "packagedJavaVersion": (java_version.stderr or java_version.stdout).splitlines()[0],
        "selectedSource": selected,
        "grant": {key: proof.get(key) for key in
                  ("granted", "sessionActive", "tokenStored", "sessionSocket", "tokenPath")},
        "check": checked,
        "helperPid": helper_pids[0],
        "crossProcessByTitleScope": {
            "documentedUnsupportedLinuxApi": True,
            "doesNotAssertRawPeerDenial": True,
        },
        "cropExitCode": None,
    }
    # Preserve the complete packaged-CLI proof even if the downstream native probe fails.
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n")

    env = os.environ.copy()
    env["SPECTRE_WAYLAND_CONSENT_PROMPT"] = "never"
    crop_script = Path(__file__).with_name("gnome-public-window-crop.py")
    capture_started = time.monotonic()
    crop = subprocess.run([sys.executable, str(crop_script)], env=env, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (artifacts / "crop-proof.stdout").write_text(crop.stdout)
    (artifacts / "crop-proof.stderr").write_text(sanitized(crop.stderr))
    observer_after_capture = time.monotonic()
    time.sleep(.5)
    stop_watcher()
    atexit.unregister(stop_watcher)

    observations = [json.loads(line) for line in observation_file.read_text().splitlines()]
    samples = [item for item in observations if item.get("kind") == "sample"]
    nonzero_runs = sum(item["pickerCount"] > 0 and
                       (index == 0 or samples[index - 1]["pickerCount"] == 0)
                       for index, item in enumerate(samples))
    subsequent_picker_samples = [item for item in samples
                                 if item["monotonic"] >= capture_started and
                                 item["pickerCount"] != 0]
    minimum_observation_seconds = 5.0
    observed_seconds = samples[-1]["monotonic"] - samples[0]["monotonic"] if samples else 0.0
    sampling_intervals = [later["monotonic"] - earlier["monotonic"]
                          for earlier, later in zip(samples, samples[1:])]
    mean_sampling_interval_seconds = (
        sum(sampling_intervals) / len(sampling_intervals) if sampling_intervals else None)
    picker_valid = (
        bool(samples) and samples[0]["monotonic"] <= observer_before_grant and
        any(observer_before_grant <= item["monotonic"] <= observer_after_grant and
            item["pickerCount"] == 1 for item in samples) and
        samples[-1]["monotonic"] >= observer_after_capture and
        observed_seconds >= minimum_observation_seconds and
        nonzero_runs == 1 and max(item["pickerCount"] for item in samples) == 1 and
        not subsequent_picker_samples
    )
    helper_alive = Path(f"/proc/{helper_pids[0]}").is_dir() and socket.is_socket()
    evidence.update({
        "cropExitCode": crop.returncode,
        "cropEvidence": json.loads((artifacts / "public-window-crop-evidence.json").read_text())
                        if (artifacts / "public-window-crop-evidence.json").is_file() else None,
        "portalBackendPid": backend,
        "portalObservations": observations,
        "pickerAppearances": nonzero_runs,
        "pickerCountAfterGrant": len(subsequent_picker_samples),
        "realDialogSamples": len(samples),
        "observedSeconds": observed_seconds,
        "minimumObservationSeconds": minimum_observation_seconds,
        "meanSamplingIntervalSeconds": mean_sampling_interval_seconds,
        "samplingIntervalTargetSeconds": 0.1,
        "helperAliveAfterCaptures": helper_alive,
        "sessionSocketExists": socket.is_socket(),
        "grantMarkerExists": marker.is_file(),
    })
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n")
    receipt(receipts, "native-proven-public-crop", exitCode=crop.returncode,
            pickerValid=picker_valid, helperAlive=helper_alive)
    if crop.returncode:
        raise RuntimeError(f"native-tested public crop probe failed ({crop.returncode})")
    if not picker_valid:
        raise RuntimeError("observer did not prove exactly one grant picker and zero later pickers")
    if not helper_alive or not marker.is_file():
        raise RuntimeError("packaged CLI session did not govern capture through completion")


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "--observe":
        observe_dialogs(int(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
        raise SystemExit(0)
    try:
        main()
    except Exception as error:
        print(f"GNOME packaged CLI/crop verification failed: {error}", file=sys.stderr)
        raise

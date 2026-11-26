#!/usr/bin/env python3
"""Drive and independently attest the installed CLI/public recording live cell."""
from __future__ import annotations

import json
import importlib.util
import atexit
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

import dbus
from gi.repository import GLib
from PIL import Image

_portal_path = Path(__file__).with_name("gnome-portal-dialog-warmup.py")
_portal_spec = importlib.util.spec_from_file_location("gnome_portal_dialog_warmup", _portal_path)
if _portal_spec is None or _portal_spec.loader is None:
    raise RuntimeError(f"cannot load portal verifier from {_portal_path}")
portal = importlib.util.module_from_spec(_portal_spec)
_portal_spec.loader.exec_module(portal)


def extract_release(archive: Path, destination: Path) -> tuple[Path, Path]:
    """Extract a trusted build output without traversal, retaining Unix executable modes."""
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
        raise RuntimeError(f"unexpected release layout: launchers={launchers}, runtimes={runtimes}")
    if not os.access(launchers[0], os.X_OK) or not os.access(runtimes[0], os.X_OK):
        raise RuntimeError("packaged launcher/runtime lost executable permissions")
    return launchers[0].resolve(), runtimes[0].resolve()


def observe_dialogs(pid: int, output: Path, ready: Path) -> None:
    # This process owns its pyatspi Registry and D-Bus connection. pyatspi's singleton Registry is
    # not safe to access from a watcher thread while the parent drives the picker.
    import pyatspi
    with output.open("a", buffering=1) as receipt:
        while True:
            try:
                apps = [app for app in pyatspi.Registry.getDesktop(0)
                        if app.get_process_id() == pid]
                count = sum(1 for app in apps for node in portal.descendants(app)
                            if node.name == portal.TITLE and
                            node.getRoleName() in ("dialog", "frame"))
                receipt.write(json.dumps({"monotonic": time.monotonic(),
                                          "pickerCount": count}) + "\n")
                if not ready.exists():
                    ready.write_text("sampled\n")
            except GLib.GError as error:
                if "atspi_error" not in str(error).lower() or \
                        "application no longer exists" not in str(error).lower():
                    raise
                receipt.write(json.dumps({"monotonic": time.monotonic(),
                                          "event": "application-disappeared"}) + "\n")
            time.sleep(.1)


def decoy_xid(pid: int, title: str, artifacts: Path) -> str:
    pattern = f"^{re.escape(title)}$"
    search = subprocess.run(["xdotool", "search", "--all", "--onlyvisible", "--pid", str(pid),
                             "--name", pattern], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    raw_candidates = [value for value in search.stdout.splitlines() if value.isdecimal()]
    candidates = list(dict.fromkeys(int(value) for value in raw_candidates))
    metadata = []
    for xid in candidates:
        props = subprocess.run(["xprop", "-id", str(xid), "_NET_WM_PID", "WM_CLASS", "WM_NAME",
                                "_NET_WM_NAME"], text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT).stdout
        match = re.search(r"_NET_WM_PID\(CARDINAL\) = (\d+)", props)
        actual_pid = int(match.group(1)) if match else None
        decoy_class = "verification-BlueDecoy" in props
        metadata.append({"xidNumeric": xid, "netWmPid": actual_pid,
                         "decoyClass": decoy_class, "xprop": props,
                         "accepted": actual_pid == pid and decoy_class})
    (artifacts / "decoy-x11-selection.json").write_text(json.dumps({
        "expectedPid": pid, "titlePattern": pattern, "searchExitCode": search.returncode,
        "searchStderr": search.stderr, "rawSearchResults": raw_candidates,
        "distinctNumericXids": candidates, "candidates": metadata,
    }, indent=2) + "\n")
    owned = [item["xidNumeric"] for item in metadata if item["accepted"]]
    if search.returncode != 0 or len(owned) != 1:
        raise RuntimeError(
            f"expected exactly one visible verified client XID owned by decoy PID {pid}: {metadata}")
    return str(owned[0])


def colors(path: Path) -> dict[str, float]:
    image = Image.open(path).convert("RGB")
    pixels = list(image.getdata())
    total = len(pixels)
    return {
        "red": sum(r > 200 and g < 60 and b < 60 for r, g, b in pixels) / total,
        "green": sum(g > 200 and r < 60 and b < 60 for r, g, b in pixels) / total,
        "blue": sum(b > 200 and r < 60 and g < 60 for r, g, b in pixels) / total,
    }


def sanitized(text: str) -> str:
    """Keep diagnostics useful without ever retaining portal/authorization bearer values."""
    return re.sub(r'(?i)(authorization|bearer|restore[_ -]?token)(["=: ]+)[^\s",}]+',
                  r'\1\2<redacted>', text)


def receipt(path: Path, phase: str, **facts: object) -> None:
    with path.open("a") as output:
        output.write(json.dumps({"phase": phase, **facts}, sort_keys=True) + "\n")


def main() -> None:
    artifacts = Path(os.environ["SMOKE_ARTIFACTS"])
    receipts = artifacts / "cli-live-phase-receipts.jsonl"
    repo = Path(os.environ["SMOKE_REPO"])
    cli, runtime_java = extract_release(Path(os.environ["SPECTRE_CLI_ZIP"]),
                                        Path(os.environ["SPECTRE_CLI_HOME"]))
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
            try: watcher.wait(timeout=5)
            except subprocess.TimeoutExpired:
                watcher.kill(); watcher.wait(timeout=5)
    atexit.register(stop_watcher)
    for _ in range(100):
        if observer_ready.is_file(): break
        if watcher.poll() is not None: raise RuntimeError("independent portal observer exited")
        time.sleep(.1)
    else: raise RuntimeError("independent portal observer was not ready")
    observer_before_grant = time.monotonic()
    request_out = artifacts / "cli-permissions-request.json"
    request_err = artifacts / "cli-permissions-request.stderr"
    try:
        with request_out.open("w") as out, request_err.open("w") as err:
            request = subprocess.Popen([str(cli), "permissions", "request", "--json", "--timeout", "60"],
                                       stdout=out, stderr=err)
            # Detect bootstrap/runtime failures before spending the full picker timeout.
            time.sleep(.5)
            if request.poll() not in (None, 0):
                raise RuntimeError(f"packaged CLI request exited early ({request.returncode})")
            selected = portal.accept_dialog(backend, artifacts / "cli-request-atspi-tree.json")
            if request.wait(timeout=45) != 0:
                raise RuntimeError(f"packaged CLI request failed; see {request_err}")
    finally:
        if "request" in locals() and request.poll() is None:
            request.terminate()
            try: request.wait(timeout=5)
            except subprocess.TimeoutExpired:
                request.kill(); request.wait(timeout=5)
        if request_err.exists(): request_err.write_text(sanitized(request_err.read_text()))
        (artifacts / "cli-request-phase.json").write_text(json.dumps({
            "stdoutBytes": request_out.stat().st_size if request_out.exists() else 0,
            "stderrBytes": request_err.stat().st_size if request_err.exists() else 0,
        }, indent=2) + "\n")
    proof = json.loads(request_out.read_text())
    observer_after_grant = time.monotonic()
    if watcher.poll() is not None:
        raise RuntimeError("independent portal observer stopped during grant")
    request_out.write_text(json.dumps(proof, indent=2) + "\n")
    receipt(receipts, "permissions-request", exitCode=request.returncode,
            granted=proof.get("granted"), sessionActive=proof.get("sessionActive"),
            tokenStored=proof.get("tokenStored"))
    if not (proof["granted"] and proof["sessionActive"]):
        raise RuntimeError(f"CLI did not report an active persistent session: {proof}")
    token_stored = bool(proof["tokenStored"])
    marker = Path(proof["tokenPath"]) if token_stored else None
    socket = Path(proof["sessionSocket"])
    if (token_stored and not marker.is_file()) or not socket.is_socket():
        raise RuntimeError("CLI session evidence did not persist")
    helper_pids = [int(value) for value in subprocess.check_output(
        ["fuser", str(socket)], text=True, stderr=subprocess.DEVNULL).split()]
    if len(helper_pids) != 1 or not Path(f"/proc/{helper_pids[0]}").is_dir():
        raise RuntimeError(f"expected one live owner of the persistent session socket: {helper_pids}")

    check = subprocess.run([str(cli), "permissions", "check", "--json"], text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    (artifacts / "cli-permissions-check.json").write_text(check.stdout)
    (artifacts / "cli-permissions-check.stderr").write_text(sanitized(check.stderr))
    checked = json.loads(check.stdout)
    if not (checked["granted"] and checked["sessionActive"] and
            bool(checked["tokenStored"]) == token_stored):
        raise RuntimeError(f"CLI check lost grant: {checked}")
    (artifacts / "cli-check-phase.json").write_text(json.dumps({"exitCode": check.returncode,
        "granted": checked["granted"], "sessionActive": checked["sessionActive"],
        "tokenStored": checked["tokenStored"]}, indent=2) + "\n")
    receipt(receipts, "permissions-check", exitCode=check.returncode,
            granted=checked["granted"], sessionActive=checked["sessionActive"],
            tokenStored=checked["tokenStored"])

    decoy_classes = artifacts / "decoy-classes"
    decoy_classes.mkdir()
    source = Path(__file__).parents[1] / "verification/wayland-cli-recording-live/BlueDecoy.java"
    subprocess.run(["javac", "-d", str(decoy_classes), str(source)], check=True)
    ready = artifacts / "decoy.pid"
    decoy_log = (artifacts / "decoy.stderr").open("w")
    decoy = subprocess.Popen(["java", "-cp", str(decoy_classes), "verification.BlueDecoy", str(ready)],
                             stdout=subprocess.DEVNULL, stderr=decoy_log)
    try:
        for _ in range(100):
            if ready.is_file(): break
            if decoy.poll() is not None: raise RuntimeError("blue decoy exited")
            time.sleep(.1)
        xid = decoy_xid(decoy.pid, "Spectre governed same-title target", artifacts)
        subprocess.run(["xdotool", "windowactivate", "--sync", xid], check=True)
        subprocess.run(["xdotool", "windowraise", xid], check=True)
        env = os.environ.copy()
        env["DECOY_PID"] = str(decoy.pid)
        env["DECOY_XID"] = xid
        env["SPECTRE_LIVE_VERIFICATION_SOURCE"] = str(source.parent)
        run = subprocess.run([
            str(repo / "gradlew"), "--no-daemon", "-I",
            str(source.parent / "build.init.gradle"),
            ":recording:runPublicWaylandCliRecordingLive", "--console=plain"],
            cwd=repo, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (artifacts / "public-recording-live.stdout").write_text(run.stdout)
        (artifacts / "public-recording-live.stderr").write_text(run.stderr)
        if run.returncode:
            print(run.stderr, file=sys.stderr, end="")
            raise RuntimeError("public recording live process failed")
        receipt(receipts, "public-api-captures", exitCode=run.returncode)
        observer_after_green = time.monotonic()
        time.sleep(.25)
        if watcher.poll() is not None:
            raise RuntimeError("independent portal observer stopped before post-green sample")
    finally:
        if decoy.poll() is None:
            decoy.terminate()
            try: decoy.wait(timeout=10)
            except subprocess.TimeoutExpired:
                decoy.kill(); decoy.wait(timeout=5)
        decoy_log.close()

    frames = artifacts / "movie-frames"
    frames.mkdir()
    movie = artifacts / "red-covered.mp4"
    duration = float(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(movie)], text=True))
    samples = [duration * fraction for fraction in (.1, .5, .9)]
    frame_evidence = []
    for index, position in enumerate(samples):
        frame = frames / f"frame-{index}.png"
        subprocess.run(["ffmpeg", "-v", "error", "-ss", str(position), "-i", str(movie),
                        "-frames:v", "1", "-y", str(frame)], check=True)
        coverage = colors(frame)
        if coverage["red"] <= .5 or coverage["blue"] >= .01:
            raise RuntimeError(f"movie frame {index} has wrong coverage: {coverage}")
        frame_evidence.append({"positionSeconds": position, "coverage": coverage})
    stills = {name: colors(artifacts / name) for name in ("red-covered.png", "green-covered.png")}
    if stills["red-covered.png"]["red"] <= .5 or stills["green-covered.png"]["green"] <= .5:
        raise RuntimeError(f"still coverage is not affirmative: {stills}")
    if any(value["blue"] >= .01 for value in stills.values()):
        raise RuntimeError(f"blue decoy leaked into stills: {stills}")
    if not Path(f"/proc/{helper_pids[0]}").is_dir() or not socket.is_socket():
        raise RuntimeError("the CLI-started helper did not survive all captures")
    receipt(receipts, "media-validation", helperAlive=True, sessionSocketExists=True)

    stop_watcher()
    atexit.unregister(stop_watcher)
    observations = [json.loads(line) for line in observation_file.read_text().splitlines()]
    samples = [item for item in observations if "pickerCount" in item]
    nonzero_runs = sum(current["pickerCount"] > 0 and
                       (i == 0 or samples[i-1]["pickerCount"] == 0)
                       for i, current in enumerate(samples))
    if (not samples or samples[0]["monotonic"] > observer_before_grant or
            not any(observer_before_grant <= item["monotonic"] <= observer_after_grant and
                    item["pickerCount"] > 0 for item in samples) or
            samples[-1]["monotonic"] < observer_after_green or nonzero_runs != 1 or
            max(item["pickerCount"] for item in samples) != 1):
        raise RuntimeError(f"expected exactly one singular consent picker, observed {nonzero_runs}")
    evidence = {"selectedSource": selected, "pickerAppearances": nonzero_runs,
                "maxConcurrentPickers": max(item["pickerCount"] for item in samples),
                "dialogSamples": len(samples),
                "portalObservations": observations,
                "tokenStored": token_stored,
                "grantMarkerExists": marker.is_file() if marker else False,
                "sessionSocketExists": socket.is_socket(), "helperPid": helper_pids[0],
                "packagedLauncher": str(cli), "packagedRuntimeJava": str(runtime_java),
                "packagedJavaVersion": (java_version.stderr or java_version.stdout).splitlines()[0],
                "helperAliveAfterCaptures": True, "stills": stills,
                "movieFrames": frame_evidence, "movieDurationSeconds": duration}
    (artifacts / "cli-recording-live-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "--observe":
        observe_dialogs(int(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
        raise SystemExit(0)
    try:
        main()
    except Exception as error:
        print(f"GNOME CLI/recording live verification failed: {error}", file=sys.stderr)
        raise

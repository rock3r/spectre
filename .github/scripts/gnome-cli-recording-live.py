#!/usr/bin/env python3
"""Drive and independently attest the installed CLI/public recording live cell."""
from __future__ import annotations

import json
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

import dbus
import pyatspi
from PIL import Image

_portal_path = Path(__file__).with_name("gnome-portal-dialog-warmup.py")
_portal_spec = importlib.util.spec_from_file_location("gnome_portal_dialog_warmup", _portal_path)
if _portal_spec is None or _portal_spec.loader is None:
    raise RuntimeError(f"cannot load portal verifier from {_portal_path}")
portal = importlib.util.module_from_spec(_portal_spec)
_portal_spec.loader.exec_module(portal)


def dialog_count(pid: int) -> int:
    return sum(1 for app in pyatspi.Registry.getDesktop(0) if app.get_process_id() == pid
               for node in portal.descendants(app)
               if node.name == portal.TITLE and node.getRoleName() in ("dialog", "frame"))


def colors(path: Path) -> dict[str, float]:
    image = Image.open(path).convert("RGB")
    pixels = list(image.getdata())
    total = len(pixels)
    return {
        "red": sum(r > 200 and g < 60 and b < 60 for r, g, b in pixels) / total,
        "green": sum(g > 200 and r < 60 and b < 60 for r, g, b in pixels) / total,
        "blue": sum(b > 200 and r < 60 and g < 60 for r, g, b in pixels) / total,
    }


def main() -> None:
    artifacts = Path(os.environ["SMOKE_ARTIFACTS"])
    repo = Path(os.environ["SMOKE_REPO"])
    cli = repo / "cli/build/install/spectre/bin/spectre"
    backend = portal.backend_pid(dbus.SessionBus())
    observations: list[dict] = []
    stop = threading.Event()

    def observe() -> None:
        while not stop.is_set():
            observations.append({"monotonic": time.monotonic(), "pickerCount": dialog_count(backend)})
            time.sleep(.1)

    watcher = threading.Thread(target=observe, daemon=True)
    watcher.start()
    request_out = artifacts / "cli-permissions-request.json"
    request_err = artifacts / "cli-permissions-request.stderr"
    with request_out.open("w") as out, request_err.open("w") as err:
        request = subprocess.Popen([str(cli), "permissions", "request", "--json", "--timeout", "60"],
                                   stdout=out, stderr=err)
        selected = portal.accept_dialog(backend, artifacts / "cli-request-atspi-tree.json")
        if request.wait(timeout=45) != 0:
            raise RuntimeError(f"installed CLI request failed; see {request_err}")
    proof = json.loads(request_out.read_text())
    if not (proof["granted"] and proof["sessionActive"] and proof["tokenStored"]):
        raise RuntimeError(f"CLI did not report an active persistent grant: {proof}")
    marker = Path(proof["tokenPath"])
    socket = Path(proof["sessionSocket"])
    if not marker.is_file() or not socket.is_socket():
        raise RuntimeError("CLI grant marker/session socket did not persist")
    helper_pids = [int(value) for value in subprocess.check_output(
        ["fuser", str(socket)], text=True, stderr=subprocess.DEVNULL).split()]
    if len(helper_pids) != 1 or not Path(f"/proc/{helper_pids[0]}").is_dir():
        raise RuntimeError(f"expected one live owner of the persistent session socket: {helper_pids}")

    check = subprocess.run([str(cli), "permissions", "check", "--json"], text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    (artifacts / "cli-permissions-check.json").write_text(check.stdout)
    (artifacts / "cli-permissions-check.stderr").write_text(check.stderr)
    checked = json.loads(check.stdout)
    if not (checked["granted"] and checked["sessionActive"] and checked["tokenStored"]):
        raise RuntimeError(f"CLI check lost grant: {checked}")

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
        subprocess.run(["xdotool", "search", "--pid", str(decoy.pid), "windowactivate", "--sync"], check=True)
        env = os.environ.copy()
        env["DECOY_PID"] = str(decoy.pid)
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
    finally:
        decoy.terminate()
        decoy.wait(timeout=10)
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

    stop.set(); watcher.join(timeout=2)
    nonzero_runs = sum(current["pickerCount"] > 0 and (i == 0 or observations[i-1]["pickerCount"] == 0)
                       for i, current in enumerate(observations))
    if nonzero_runs != 1 or max(item["pickerCount"] for item in observations) != 1:
        raise RuntimeError(f"expected exactly one singular consent picker, observed {nonzero_runs}")
    evidence = {"selectedSource": selected, "pickerAppearances": nonzero_runs,
                "maxConcurrentPickers": max(item["pickerCount"] for item in observations),
                "portalObservations": observations, "grantMarkerExists": marker.is_file(),
                "sessionSocketExists": socket.is_socket(), "helperPid": helper_pids[0],
                "helperAliveAfterCaptures": True, "stills": stills,
                "movieFrames": frame_evidence, "movieDurationSeconds": duration}
    (artifacts / "cli-recording-live-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"GNOME CLI/recording live verification failed: {error}", file=sys.stderr)
        raise

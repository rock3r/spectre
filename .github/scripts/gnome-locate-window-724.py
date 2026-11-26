#!/usr/bin/env python3
"""Owned-Xwayland fixture for #724; exercises installed helper binaries only."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("GdkX11", "3.0")
from gi.repository import GdkX11, GLib, Gtk  # noqa: E402,F401

A = Path(os.environ["SMOKE_ARTIFACTS"])
BUS = "dev.sebastiano.Spectre.WindowIdentity"
EXTENSION = "spectre-window-identity@sebastiano.dev"
results = []


def pump():
    while Gtk.events_pending():
        Gtk.main_iteration_do(False)


def owner():
    output = subprocess.check_output([
        "gdbus", "call", "--session", "--dest", "org.freedesktop.DBus",
        "--object-path", "/org/freedesktop/DBus", "--method",
        "org.freedesktop.DBus.NameHasOwner", BUS], text=True)
    return "true" in output.lower()


def wait_for(description, predicate, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pump()
        if predicate():
            return
        time.sleep(.1)
    raise RuntimeError(f"timeout waiting for {description}")


def inspect_x11(xid):
    info = subprocess.check_output(["xwininfo", "-id", hex(xid)], text=True)
    props = subprocess.check_output(["xprop", "-id", hex(xid), "_NET_WM_PID", "WM_CLASS", "_NET_WM_WINDOW_TYPE"], text=True)
    values = {}
    for key, label in (("x", "Absolute upper-left X"), ("y", "Absolute upper-left Y"),
                       ("width", "Width"), ("height", "Height")):
        match = re.search(rf"^\s*{label}:\s+(-?\d+)$", info, re.MULTILINE)
        if not match:
            raise RuntimeError(f"missing {label} in xwininfo")
        values[key] = int(match.group(1))
    pid_match = re.search(r"_NET_WM_PID\(CARDINAL\) = (\d+)", props)
    actual_pid = int(pid_match.group(1)) if pid_match else None
    if actual_pid != os.getpid() or "_NET_WM_WINDOW_TYPE_NORMAL" not in props:
        raise RuntimeError(f"fixture ownership/type mismatch: pid={actual_pid}; {props}")
    (A / "fixture-xwininfo.txt").write_text(info)
    (A / "fixture-xprop.txt").write_text(props)
    evidence = {"xid": xid, "xidHex": hex(xid), "ownerPid": actual_pid,
                "launcherPid": os.getpid(), "clientFrame": values}
    (A / "fixture.json").write_text(json.dumps(evidence, indent=2) + "\n")
    return values


def invoke(label, helper, frame, expect):
    request = {"command": "locate_window", "owner_pid": os.getpid(), "frame": frame}
    started = time.monotonic_ns()
    # Popen is intentionally here: the public helper must be a direct child of the X11 owner.
    child = subprocess.Popen([helper], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
    payload = (json.dumps(request, separators=(",", ":")) + "\n").encode()
    stdout, stderr = child.communicate(payload, timeout=20)  # one line, then closes stdin
    elapsed = time.monotonic_ns() - started
    (A / f"{label}.request.json").write_bytes(payload)
    (A / f"{label}.stderr").write_bytes(stderr)
    lines = stdout.splitlines()
    parsed = []
    for line in lines:
        parsed.append(json.loads(line))
    record = {"case": label, "helperPid": child.pid, "parentPid": os.getpid(),
              "returnCode": child.returncode, "elapsedNs": elapsed,
              "stdoutBytes": len(stdout), "stderrBytes": len(stderr), "events": parsed}
    if expect == "old":
        (A / f"{label}.stdout").write_bytes(stdout)
        if parsed or child.returncode == 0 or not stderr:
            raise RuntimeError(f"old shape changed: {record}")
    elif expect == "error":
        (A / f"{label}.stdout").write_bytes(stdout)
        if child.returncode != 0 or len(parsed) != 1 or parsed[0].get("event") != "error":
            raise RuntimeError(f"corrected negative is not one clean Error: {record}")
        event = parsed[0]
        if event.get("kind") != "Helper" or not any(word in event.get("message", "").lower()
                for word in ("extension", "service", "name", "unavailable")):
            raise RuntimeError(f"corrected Error lacks accurate actionable cause: {event}")
    else:
        if child.returncode != 0 or len(parsed) != 1 or parsed[0].get("event") != "window_located":
            raise RuntimeError(f"restored positive failed: {record}")
        token = parsed[0].pop("binding_token", None)
        if not isinstance(token, str) or not token:
            raise RuntimeError("positive result has no binding token")
        record["events"] = [{"event": "window_located", "bindingTokenLength": len(token)}]
        # Never persist the usable success token, even transiently.
        (A / f"{label}.stdout").write_text(json.dumps(record["events"][0]) + "\n")
    results.append(record)
    (A / "locate-window-results.json").write_text(json.dumps(results, indent=2) + "\n")


def main():
    A.mkdir(parents=True, exist_ok=True)
    helpers = {name: os.environ[name] for name in ("BASELINE_HELPER", "CORRECTED_HELPER")}
    hashes = {name: {"path": path, "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                     "mode": oct(Path(path).stat().st_mode & 0o777)} for name, path in helpers.items()}
    (A / "helper-artifacts.json").write_text(json.dumps(hashes, indent=2) + "\n")

    win = Gtk.Window(title="Spectre #724 owned normal fixture")
    win.set_default_size(360, 240)
    win.move(180, 160)
    win.show_all()
    wait_for("mapped fixture", lambda: win.get_window() is not None and win.get_window().is_viewable())
    xid = win.get_window().get_xid()
    frame = inspect_x11(xid)

    subprocess.run(["gnome-extensions", "disable", EXTENSION], check=True)
    wait_for("identity D-Bus name to become unowned", lambda: not owner())
    (A / "pre-negative.json").write_text(json.dumps({"extensionEnabled": False,
        "identityNameOwned": owner(), "frame": frame, "ownerPid": os.getpid(), "xid": xid}, indent=2) + "\n")
    invoke("baseline-disabled-extension", helpers["BASELINE_HELPER"], frame, "old")
    invoke("corrected-disabled-extension", helpers["CORRECTED_HELPER"], frame, "error")

    subprocess.run(["gnome-extensions", "enable", EXTENSION], check=True)
    wait_for("production identity D-Bus owner", owner)
    (A / "post-restore.json").write_text(json.dumps({"extensionEnabled": True,
        "identityNameOwned": owner(), "frame": frame, "ownerPid": os.getpid(), "xid": xid}, indent=2) + "\n")
    invoke("corrected-restored-positive", helpers["CORRECTED_HELPER"], frame, "positive")
    win.destroy()


if __name__ == "__main__":
    main()

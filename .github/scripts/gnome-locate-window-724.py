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
from gi.repository import GdkX11, Gtk  # noqa: E402,F401

A = Path(os.environ["SMOKE_ARTIFACTS"])
BUS = "dev.sebastiano.Spectre.WindowIdentity"
EXTENSION = "spectre-window-identity@sebastiano.dev"
INSTALL_GUIDANCE = ("Install and enable the bundled spectre-window-identity "
                    "GNOME Shell extension.")
NATIVE_TIMEOUT = 20
STABLE_PROBES = 3
results = []


def pump():
    while Gtk.events_pending():
        Gtk.main_iteration_do(False)


def run(*args):
    return subprocess.check_output(args, text=True, timeout=5)


def owner():
    output = run("gdbus", "call", "--session", "--dest", "org.freedesktop.DBus",
                 "--object-path", "/org/freedesktop/DBus", "--method",
                 "org.freedesktop.DBus.NameHasOwner", BUS)
    return "true" in output.lower()


def wait_for(description, predicate, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pump()
        if predicate():
            return
        time.sleep(.1)
    raise RuntimeError(f"timeout waiting for {description} after {timeout}s")


def ensure_overview_inactive():
    base = ("gdbus", "call", "--session", "--dest", "org.gnome.Shell",
            "--object-path", "/org/gnome/Shell")

    def active():
        value = run(*base, "--method", "org.freedesktop.DBus.Properties.Get",
                    "org.gnome.Shell", "OverviewActive")
        return "true" in value.lower()

    before = active()
    if before:
        subprocess.run((*base, "--method", "org.freedesktop.DBus.Properties.Set",
                        "org.gnome.Shell", "OverviewActive", "<false>"),
                       check=True, timeout=5)
    wait_for("GNOME Shell OverviewActive=false", lambda: not active(), timeout=5)
    (A / "overview.json").write_text(json.dumps(
        {"before": before, "after": active()}, indent=2) + "\n")


def provider_status():
    completed = subprocess.run(["gnome-extensions", "info", EXTENSION], text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=5)
    return {"identityName": BUS, "identityNameOwned": owner(),
            "extension": EXTENSION, "extensionInfoReturnCode": completed.returncode,
            "extensionInfo": completed.stdout}


def require_identity_error(message, source):
    if BUS not in message or INSTALL_GUIDANCE not in message:
        raise RuntimeError(f"{source} lacks identity service/install-enable guidance: {message}")


def parse_frame(info):
    frame = {}
    for key, label in (("x", "Absolute upper-left X"), ("y", "Absolute upper-left Y"),
                       ("width", "Width"), ("height", "Height")):
        match = re.search(rf"^\s*{label}:\s+(-?\d+)$", info, re.MULTILINE)
        if not match:
            raise RuntimeError(f"missing {label} in xwininfo native frame")
        frame[key] = int(match.group(1))
    return frame


def inspect_x11(xid):
    info = run("xwininfo", "-id", hex(xid))
    props = run("xprop", "-id", hex(xid), "_NET_WM_PID", "WM_CLASS",
                "_NET_WM_WINDOW_TYPE")
    clients = run("xprop", "-root", "_NET_CLIENT_LIST")
    if "Map State: IsViewable" not in info:
        raise RuntimeError("native X11 window is not IsViewable")
    client_xids = {int(value, 16) for value in re.findall(r"0x[0-9a-fA-F]+", clients)}
    if xid not in client_xids:
        raise RuntimeError(f"{hex(xid)} is absent from _NET_CLIENT_LIST")
    frame = parse_frame(info)
    pid_match = re.search(r"_NET_WM_PID\(CARDINAL\) = (\d+)", props)
    actual_pid = int(pid_match.group(1)) if pid_match else None
    if actual_pid != os.getpid() or "_NET_WM_WINDOW_TYPE_NORMAL" not in props:
        raise RuntimeError(f"fixture ownership/type mismatch: pid={actual_pid}; {props}")
    return {"xid": xid, "xidHex": hex(xid), "ownerPid": actual_pid,
            "clientFrame": frame, "xwininfo": info, "xprop": props,
            "rootClientList": clients}


def stable_x11(xid, label, requested_size):
    provider = provider_status()
    deadline = time.monotonic() + NATIVE_TIMEOUT
    previous = None
    consecutive = 0
    last_error = None
    while time.monotonic() < deadline:
        pump()
        try:
            sample = inspect_x11(xid)
            current = sample["clientFrame"]
            consecutive = consecutive + 1 if current == previous else 1
            previous = current
            if consecutive >= STABLE_PROBES:
                evidence = {key: value for key, value in sample.items()
                            if key not in ("xwininfo", "xprop", "rootClientList")}
                evidence.update({"requestedSize": requested_size,
                                 "stableProbeCount": consecutive,
                                 "timeoutSeconds": NATIVE_TIMEOUT,
                                 "provider": provider})
                (A / f"{label}-native.json").write_text(
                    json.dumps(evidence, indent=2) + "\n")
                (A / f"{label}-xwininfo.txt").write_text(sample["xwininfo"])
                (A / f"{label}-xprop.txt").write_text(sample["xprop"])
                (A / f"{label}-root-client-list.txt").write_text(sample["rootClientList"])
                return current
        except (RuntimeError, subprocess.SubprocessError) as error:
            previous, consecutive, last_error = None, 0, str(error)
        time.sleep(.1)
    raise RuntimeError(f"timeout waiting {NATIVE_TIMEOUT}s for stable native X11 "
                       f"window {hex(xid)}: {last_error}")


def invoke(label, helper, xid, requested_size, expect):
    # Mutter can move the window while an extension is toggled; obtain the
    # contemporary stable frame immediately before every helper request.
    frame = stable_x11(xid, label, requested_size)
    request = {"command": "locate_window", "owner_pid": os.getpid(), "frame": frame}
    started = time.monotonic_ns()
    child = subprocess.Popen([helper], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE)
    payload = (json.dumps(request, separators=(",", ":")) + "\n").encode()
    stdout, stderr = child.communicate(payload, timeout=20)
    elapsed = time.monotonic_ns() - started
    (A / f"{label}.request.json").write_bytes(payload)
    (A / f"{label}.stderr").write_bytes(stderr)
    parsed = [json.loads(line) for line in stdout.splitlines()]
    record = {"case": label, "xid": xid, "frame": frame, "helperPid": child.pid,
              "parentPid": os.getpid(), "returnCode": child.returncode,
              "elapsedNs": elapsed, "timeoutSeconds": 20, "stdoutBytes": len(stdout),
              "stderrBytes": len(stderr), "events": parsed}
    if expect == "old":
        (A / f"{label}.stdout").write_bytes(stdout)
        if parsed or child.returncode == 0 or not stderr:
            raise RuntimeError(f"old zero-JSON failure changed: {record}")
        require_identity_error(stderr.decode(errors="replace"), "original stderr")
    elif expect == "error":
        (A / f"{label}.stdout").write_bytes(stdout)
        if child.returncode != 0 or len(parsed) != 1 or parsed[0].get("event") != "error":
            raise RuntimeError(f"corrected negative is not one clean Error: {record}")
        event = parsed[0]
        if event.get("kind") != "Helper":
            raise RuntimeError(f"corrected Error has wrong kind: {event}")
        require_identity_error(event.get("message", ""), "corrected Helper Error")
    else:
        if child.returncode != 0 or len(parsed) != 1 or parsed[0].get("event") != "window_located":
            raise RuntimeError(f"restored same-window positive failed: {record}")
        token = parsed[0].pop("binding_token", None)
        if not isinstance(token, str) or not token:
            raise RuntimeError("positive result has no binding token")
        record["events"] = [{"event": "window_located", "bindingTokenLength": len(token)}]
        (A / f"{label}.stdout").write_text(json.dumps(record["events"][0]) + "\n")
    results.append(record)
    (A / "locate-window-results.json").write_text(json.dumps(results, indent=2) + "\n")


def main():
    A.mkdir(parents=True, exist_ok=True)
    helpers = {name: os.environ[name] for name in ("BASELINE_HELPER", "CORRECTED_HELPER")}
    hashes = {name: {"path": path, "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                     "mode": oct(Path(path).stat().st_mode & 0o777)}
              for name, path in helpers.items()}
    (A / "helper-artifacts.json").write_text(json.dumps(hashes, indent=2) + "\n")
    ensure_overview_inactive()

    requested_size = {"width": 360, "height": 240}
    win = Gtk.Window(title="Spectre #724 owned normal fixture")
    win.set_default_size(requested_size["width"], requested_size["height"])
    win.move(180, 160)
    win.show_all()
    wait_for("GDK window creation", lambda: win.get_window() is not None)
    xid = win.get_window().get_xid()
    stable_x11(xid, "initial", requested_size)

    subprocess.run(["gnome-extensions", "disable", EXTENSION], check=True)
    wait_for("identity D-Bus name to become unowned", lambda: not owner())
    invoke("baseline-disabled-extension", helpers["BASELINE_HELPER"], xid,
           requested_size, "old")
    invoke("corrected-disabled-extension", helpers["CORRECTED_HELPER"], xid,
           requested_size, "error")

    subprocess.run(["gnome-extensions", "enable", EXTENSION], check=True)
    wait_for("production identity D-Bus owner", owner)
    invoke("corrected-restored-positive", helpers["CORRECTED_HELPER"], xid,
           requested_size, "positive")
    win.destroy()


if __name__ == "__main__":
    main()

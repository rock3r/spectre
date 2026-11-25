#!/usr/bin/env python3
"""Verify owned GTK3/X11 window identity through Mutter's ScreenCast API."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("GdkX11", "3.0")
from gi.repository import GdkX11, Gio, GLib, Gtk  # noqa: E402,F401
from PIL import Image  # noqa: E402

ARTIFACTS = Path(os.environ["SMOKE_ARTIFACTS"])
BUS = "dev.sebastiano.Spectre.WindowIdentity"
IDENTITY_PATH = "/dev/sebastiano/Spectre/WindowIdentity"
SCREENCAST = "org.gnome.Mutter.ScreenCast"
SCREENCAST_PATH = "/org/gnome/Mutter/ScreenCast"
TITLE = "Spectre identity collision"
WIDTH, HEIGHT = 320, 240


def log(message):
    print(f"identity probe: {message}", flush=True)


def pump():
    while Gtk.events_pending():
        Gtk.main_iteration_do(False)
    GLib.MainContext.default().iteration(False)


def retry(description, predicate, timeout=20):
    deadline, last = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        pump()
        try:
            last = predicate()
        except (GLib.Error, OSError, subprocess.SubprocessError, ValueError) as error:
            last = f"{type(error).__name__}: {error}"
        log(f"waiting for {description}: {last}")
        if last is True:
            return
        time.sleep(0.1)
    raise AssertionError(f"timed out waiting for {description}: {last}")


def window(name, color, accepts_focus=False):
    win = Gtk.Window(title=f"{TITLE} - {name}")
    win.set_default_size(WIDTH, HEIGHT)
    win.set_accept_focus(accepts_focus)
    win._probe_configured = False
    win._probe_painted = False
    win.connect("configure-event", lambda *_args: setattr(win, "_probe_configured", True))
    area = Gtk.DrawingArea()

    def draw(_widget, cr):
        cr.set_source_rgb(*color)
        cr.paint()
        win._probe_painted = True
        return False

    area.connect("draw", draw)
    win.add(area)
    win.show_all()
    return win


def xid(win):
    value = win.get_window().get_xid()
    assert value > 0, value
    return value


def ready(win, expected_xid):
    gdk_window = win.get_window()
    if (not win.get_mapped() or gdk_window is None or not gdk_window.is_viewable()
            or not win._probe_configured or not win._probe_painted):
        return False
    if xid(win) != expected_xid:
        return f"XID changed to {xid(win)}"
    width, height = gdk_window.get_geometry()[2:4]
    return width >= WIDTH and height >= HEIGHT


def active_xid():
    output = subprocess.check_output(
        ["xprop", "-root", "_NET_ACTIVE_WINDOW"], text=True,
        stderr=subprocess.STDOUT, timeout=2,
    )
    match = re.search(r"0x[0-9a-fA-F]+", output)
    if not match:
        raise ValueError(output.strip())
    return int(match.group(), 16)


def blue_is_focused(blue, blue_xid):
    # Avoid XGetInputFocus: GNOME startup can briefly return a destroyed XID/BadWindow.
    return bool(blue.is_active() and active_xid() == blue_xid)


def call(connection, destination, path, interface, method, parameters, reply_type):
    loop, result = GLib.MainLoop(), []

    def finished(_connection, answer):
        try:
            result.append(connection.call_finish(answer).unpack())
        except GLib.Error as error:
            result.append(error)
        loop.quit()

    connection.call(destination, path, interface, method, parameters, reply_type,
                    Gio.DBusCallFlags.NONE, 10_000, None, finished)
    loop.run()  # The nested loop keeps GTK/GDK and D-Bus dispatch moving during IPC.
    if isinstance(result[0], Exception):
        raise result[0]
    return result[0]


def wait_signal(connection, path, interface, signal, start):
    loop, answer = GLib.MainLoop(), []

    def received(_connection, _sender, _path, _interface, _signal, parameters):
        answer.extend(parameters.unpack())
        loop.quit()

    def expired():
        loop.quit()
        return GLib.SOURCE_REMOVE

    subscription = connection.signal_subscribe(
        SCREENCAST, interface, signal, path, None, Gio.DBusSignalFlags.NONE, received
    )
    timeout = GLib.timeout_add_seconds(15, expired)
    start()
    loop.run()
    if answer:
        GLib.source_remove(timeout)
    connection.signal_unsubscribe(subscription)
    assert len(answer) == 1, f"no {signal} from {path}"
    return answer[0]


def run_pumping(command, timeout):
    process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT)
    deadline = time.monotonic() + timeout
    while process.poll() is None and time.monotonic() < deadline:
        pump()
        time.sleep(0.02)
    if process.poll() is None:
        process.kill()
        raise subprocess.TimeoutExpired(command, timeout)
    return process.returncode, process.communicate()[0]


def validate_image(path, wanted):
    image = Image.open(path).convert("RGBA")
    bounds = image.getchannel("A").getbbox()
    assert bounds, f"{path} has no opaque pixels"
    opaque = image.crop(bounds)
    inset = max(2, min(opaque.size) // 12)
    assert opaque.width > 2 * inset and opaque.height > 2 * inset
    pixels = list(opaque.crop((inset, inset, opaque.width - inset,
                               opaque.height - inset)).getdata())
    counts = {
        "red": sum(r > 200 and g < 60 and b < 60 and a > 240 for r, g, b, a in pixels),
        "green": sum(g > 200 and r < 60 and b < 60 and a > 240 for r, g, b, a in pixels),
        "blue": sum(b > 200 and r < 60 and g < 60 and a > 240 for r, g, b, a in pixels),
        "total": len(pixels),
    }
    assert counts[wanted] > counts["total"] * 0.70, counts
    for other in {"red", "green", "blue"} - {wanted}:
        assert counts[other] < counts["total"] * 0.01, counts
    return {"size": image.size, "opaqueBounds": bounds, "interiorColorCounts": counts}


def lookup(connection, pid, candidate_xid, label):
    (window_id,) = call(
        connection, BUS, IDENTITY_PATH, BUS, "LookupWindow",
        GLib.Variant("(ut)", (pid, candidate_xid)), GLib.VariantType.new("(u)"),
    )
    log(f"LookupWindow({label}, pid={pid}, xid={candidate_xid}) returned {window_id}")
    return window_id


def capture(connection, window_id, label, focus_check):
    (session_path,) = call(
        connection, SCREENCAST, SCREENCAST_PATH, SCREENCAST, "CreateSession",
        GLib.Variant("(a{sv})", ({},)), GLib.VariantType.new("(o)"),
    )
    (stream_path,) = call(
        connection, SCREENCAST, session_path, f"{SCREENCAST}.Session", "RecordWindow",
        # Mutter 46--50 specifies window-id as uint64.
        GLib.Variant("(a{sv})", ({"window-id": GLib.Variant("t", window_id)},)),
        GLib.VariantType.new("(o)"),
    )
    log(f"RecordWindow({label}, window-id={window_id}) returned {stream_path}")
    node = wait_signal(
        connection, stream_path, f"{SCREENCAST}.Stream", "PipeWireStreamAdded",
        lambda: call(connection, SCREENCAST, session_path,
                     f"{SCREENCAST}.Session", "Start", None, None),
    )
    log(f"PipeWireStreamAdded({label}) node={node}")
    image_path = ARTIFACTS / f"identity-{label}-receipt.png"
    command = [
        "gst-launch-1.0", "-v", "pipewiresrc", f"path={node}", "num-buffers=1",
        "do-timestamp=true", "!", "videoconvert", "!", "video/x-raw,format=RGBA",
        "!", "pngenc", "snapshot=true", "!", "filesink", f"location={image_path}",
    ]
    returncode, output = run_pumping(command, 30)
    (ARTIFACTS / f"identity-{label}-gstreamer.log").write_text(output)
    assert returncode == 0, output
    assert image_path.exists(), f"GStreamer produced no {label} receipt"
    rates = re.findall(r"framerate=\(fraction\)(\d+/\d+)", output)
    assert rates, "GStreamer did not negotiate a framerate"
    assert focus_check(), f"blue decoy lost focus during {label} capture"
    call(connection, SCREENCAST, session_path, f"{SCREENCAST}.Session", "Stop", None, None)
    details = validate_image(image_path, label)
    details.update({"windowId": window_id, "sessionPath": session_path,
                    "streamPath": stream_path, "pipeWireNode": node,
                    "negotiatedFps": rates[-1], "receipt": image_path.name})
    return details


def main():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    pid = os.getpid()
    log(f"PID={pid}")
    red = window("red", (1, 0, 0))
    blue = window("blue decoy", (0, 0, 1), accepts_focus=True)
    red_xid, blue_xid = xid(red), xid(blue)
    log(f"red XID={red_xid}; blue XID={blue_xid}")
    retry("red mapped and painted", lambda: ready(red, red_xid))
    retry("blue mapped and painted", lambda: ready(blue, blue_xid))
    blue.present()
    retry("blue active/in-focus", lambda: blue_is_focused(blue, blue_xid), timeout=30)

    connection = Gio.DBusConnection.new_for_address_sync(
        os.environ["DBUS_SESSION_BUS_ADDRESS"],
        Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT |
        Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None,
    )
    red_id = lookup(connection, pid, red_xid, "red")
    assert lookup(connection, pid, red_xid, "red-confirmation") == red_id

    focus_samples, measuring = [], True

    def sample_focus():
        if not measuring:
            return GLib.SOURCE_REMOVE
        try:
            state = (blue.is_active(), active_xid())
        except (OSError, subprocess.SubprocessError, ValueError) as error:
            state = (blue.is_active(), f"error: {error}")
        focus_samples.append(state)
        log(f"measured focus: gdk-active={state[0]} net-active-xid={state[1]}")
        return GLib.SOURCE_CONTINUE

    focus_source = GLib.timeout_add(50, sample_focus)
    captures = {"red": capture(connection, red_id, "red",
                               lambda: blue_is_focused(blue, blue_xid))}
    # This candidate is genuinely fresh: red has already been looked up and imaged. It cannot
    # accept focus, so blue remains the decoy throughout the measured red/green interval.
    green = window("fresh green", (0, 1, 0))
    green_xid = xid(green)
    log(f"fresh green XID={green_xid}")
    retry("fresh green mapped and painted", lambda: ready(green, green_xid))
    green_id = lookup(connection, pid, green_xid, "fresh-green")
    captures["green"] = capture(connection, green_id, "green",
                                lambda: blue_is_focused(blue, blue_xid))
    measuring = False
    GLib.source_remove(focus_source)
    assert focus_samples, "focus watcher collected no samples"
    assert all(active and observed == blue_xid for active, observed in focus_samples), focus_samples
    assert blue_is_focused(blue, blue_xid)

    evidence = {
        "pid": pid, "redXid": red_xid, "blueXid": blue_xid,
        "freshGreenXid": green_xid, "focusSamples": len(focus_samples),
        "focusObservedXids": sorted({x for _, x in focus_samples}), "captures": captures,
    }
    (ARTIFACTS / "identity-binding.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, sort_keys=True), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"identity smoke failed closed: {error}", file=sys.stderr, flush=True)
        raise

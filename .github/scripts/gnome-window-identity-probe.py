#!/usr/bin/env python3
"""Capture an owned GTK3/X11 window through Mutter's public ScreenCast API."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gio, GLib, Gtk  # noqa: E402
from PIL import Image  # noqa: E402


ARTIFACTS = Path(os.environ["SMOKE_ARTIFACTS"])
BUS = "dev.sebastiano.Spectre.WindowIdentity"
IDENTITY_PATH = "/dev/sebastiano/Spectre/WindowIdentity"
SCREENCAST = "org.gnome.Mutter.ScreenCast"
SCREENCAST_PATH = "/org/gnome/Mutter/ScreenCast"
TITLE = "Spectre identity collision"
WIDTH, HEIGHT = 320, 240


def window(color):
    win = Gtk.Window(title=TITLE)
    win.set_default_size(WIDTH, HEIGHT)
    area = Gtk.DrawingArea()
    area.connect("draw", lambda _widget, cr: (cr.set_source_rgb(*color), cr.paint(), False)[-1])
    win.add(area)
    win.show_all()
    return win


def xid(win):
    value = win.get_window().get_xid()
    assert value > 0, value
    return value


def focus():
    return int(subprocess.check_output(["xdotool", "getwindowfocus"], text=True).strip())


def call(connection, destination, path, interface, method, parameters, reply_type):
    return connection.call_sync(
        destination, path, interface, method, parameters, reply_type,
        Gio.DBusCallFlags.NONE, 10_000, None
    ).unpack()


def wait_signal(connection, path, interface, signal, start):
    loop = GLib.MainLoop()
    answer = []

    def received(_connection, _sender, _path, _interface, _signal, parameters):
        answer.extend(parameters.unpack())
        loop.quit()

    subscription = connection.signal_subscribe(
        SCREENCAST, interface, signal, path, None, Gio.DBusSignalFlags.NONE, received
    )
    GLib.timeout_add_seconds(15, lambda: (loop.quit(), GLib.SOURCE_REMOVE)[1])
    start()
    loop.run()
    connection.signal_unsubscribe(subscription)
    assert len(answer) == 1, f"no {signal} from {path}"
    return answer[0]


def main():
    red = window((1, 0, 0))
    blue = window((0, 0, 1))
    blue.present()
    while Gtk.events_pending():
        Gtk.main_iteration()
    time.sleep(1)
    red_xid, blue_xid = xid(red), xid(blue)
    # Add a fresh third candidate before the measured interval; from this point until capture the
    # blue decoy must remain focused without even a transient focus-changing window creation.
    green = window((0, 1, 0))
    while Gtk.events_pending():
        Gtk.main_iteration()
    green_xid = xid(green)
    subprocess.run(["xdotool", "windowactivate", "--sync", str(blue_xid)], check=True)
    before = focus()
    assert before == blue_xid, (before, blue_xid)
    focus_samples = []
    stop_focus_watch = threading.Event()

    def watch_focus():
        while not stop_focus_watch.wait(0.05):
            focus_samples.append(focus())

    focus_watch = threading.Thread(target=watch_focus, daemon=True)
    focus_watch.start()

    # A private connection is intentional: Mutter authorizes the actual candidate-owner process.
    address = os.environ["DBUS_SESSION_BUS_ADDRESS"]
    connection = Gio.DBusConnection.new_for_address_sync(
        address,
        Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT |
        Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
        None, None,
    )
    pid = os.getpid()
    (window_id,) = call(
        connection, BUS, IDENTITY_PATH, BUS, "LookupWindow",
        GLib.Variant("(ut)", (pid, red_xid)), GLib.VariantType.new("(u)"),
    )
    # Repeating normal LookupWindow proves the exact PID/XID binding remains deterministic with all
    # three ordinary same-process windows present.
    (confirmed_id,) = call(
        connection, BUS, IDENTITY_PATH, BUS, "LookupWindow",
        GLib.Variant("(ut)", (pid, red_xid)), GLib.VariantType.new("(u)"),
    )
    assert confirmed_id == window_id

    (session_path,) = call(
        connection, SCREENCAST, SCREENCAST_PATH, SCREENCAST, "CreateSession",
        GLib.Variant("(a{sv})", ({},)), GLib.VariantType.new("(o)"),
    )
    (stream_path,) = call(
        connection, SCREENCAST, session_path, f"{SCREENCAST}.Session", "RecordWindow",
        GLib.Variant("(a{sv})", ({"window-id": GLib.Variant("t", window_id)},)),
        GLib.VariantType.new("(o)"),
    )
    node = wait_signal(
        connection, stream_path, f"{SCREENCAST}.Stream", "PipeWireStreamAdded",
        lambda: call(connection, SCREENCAST, session_path, f"{SCREENCAST}.Session", "Start", None, None),
    )
    after = focus()
    stop_focus_watch.set()
    focus_watch.join()
    assert focus_samples, "focus watcher collected no samples"
    assert after == before == blue_xid and set(focus_samples) == {blue_xid}, {
        "before": before, "after": after, "blue": blue_xid, "samples": sorted(set(focus_samples))
    }

    frame_pattern = str(ARTIFACTS / "identity-frame-%02d.png")
    gst = subprocess.run([
        "gst-launch-1.0", "-v", "pipewiresrc", f"path={node}", "num-buffers=12", "do-timestamp=true",
        "!", "videoconvert", "!", "video/x-raw,format=RGB", "!", "pngenc", "snapshot=false",
        "!", "multifilesink", f"location={frame_pattern}",
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    (ARTIFACTS / "identity-gstreamer.log").write_text(gst.stdout)
    assert gst.returncode == 0, gst.stdout
    frames = sorted(ARTIFACTS.glob("identity-frame-*.png"))
    assert frames, "GStreamer produced no frames"
    rates = re.findall(r"framerate=\(fraction\)(\d+/\d+)", gst.stdout)
    assert rates, "GStreamer did not negotiate a framerate"
    negotiated_fps = rates[-1]
    image = Image.open(frames[-1]).convert("RGB")
    # Mutter includes server decorations. Inspect an inset crop so title bars and shadows do not
    # dilute the deliberately solid client color, while retaining counts for diagnosis.
    inset = max(8, min(image.size) // 12)
    crop = image.crop((inset, inset, image.width - inset, image.height - inset))
    pixels = list(crop.getdata())
    counts = {
        "red": sum(r > 200 and g < 60 and b < 60 for r, g, b in pixels),
        "blue": sum(b > 200 and r < 60 and g < 60 for r, g, b in pixels),
        "green": sum(g > 200 and r < 60 and b < 60 for r, g, b in pixels),
        "total": len(pixels),
    }
    assert counts["red"] > counts["total"] * 0.70, counts
    assert counts["blue"] < counts["total"] * 0.01, counts
    assert counts["green"] < counts["total"] * 0.01, counts
    evidence = {
        "pid": pid, "redXid": red_xid, "blueXid": blue_xid, "freshGreenXid": green_xid,
        "mutterWindowId": window_id, "sessionPath": session_path, "streamPath": stream_path,
        "pipeWireNode": node, "frameCount": len(frames), "frameSize": image.size,
        "negotiatedFps": negotiated_fps,
        "cropInset": inset, "cropColorCounts": counts, "focusBefore": before, "focusAfter": after,
        "focusSamples": len(focus_samples), "focusObservedXids": sorted(set(focus_samples)),
    }
    (ARTIFACTS / "identity-binding.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, sort_keys=True), flush=True)
    call(connection, SCREENCAST, session_path, f"{SCREENCAST}.Session", "Stop", None, None)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"identity smoke failed closed: {error}", file=sys.stderr)
        raise

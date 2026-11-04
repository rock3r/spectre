#!/usr/bin/env python3
"""Query GNOME Mutter monitors, or hold a Mutter virtual monitor, over the session D-Bus.

The release smoke uses this on a GNOME Wayland seat that has no display connected. With
no monitor, Mutter has nothing to screen-cast: the portal stream fails and X11 captures
are black. Mutter's own ScreenCast API can add a virtual monitor (gnome-remote-desktop
does the same for headless and "extend" sessions). Mutter removes it when the session
stops or when this process's D-Bus connection closes.

Usage:
  mutter_virtual_monitor.py query
      Print one JSON object: {"monitors": [{"connector", "vendor", "product", "serial"}]}.
  mutter_virtual_monitor.py hold [--width W] [--height H] [--timeout S] [--until-signal]
      Create a virtual monitor and print one JSON line, {"ready": true, "monitor": {...}}
      or {"ready": false, "error": "..."}. Then keep the monitor until stdin reaches EOF
      or the process gets SIGTERM / SIGINT. The parent keeps stdin open as a pipe, so the
      monitor also goes away when the parent dies. With --until-signal, stdin is ignored
      (for a long-running user service whose stdin is /dev/null).

Needs PyGObject (python3-gi) and DBUS_SESSION_BUS_ADDRESS for the seat's session bus.
This file is a CLI only: smoke_lib.py runs it in a child process and parses its output.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import threading
import time
from typing import Any

DISPLAY_CONFIG_BUS = "org.gnome.Mutter.DisplayConfig"
DISPLAY_CONFIG_PATH = "/org/gnome/Mutter/DisplayConfig"
SCREEN_CAST_BUS = "org.gnome.Mutter.ScreenCast"
SCREEN_CAST_PATH = "/org/gnome/Mutter/ScreenCast"
SESSION_IFACE = "org.gnome.Mutter.ScreenCast.Session"
STREAM_IFACE = "org.gnome.Mutter.ScreenCast.Stream"
CURSOR_MODE_HIDDEN = 0


def _gio() -> tuple[Any, Any]:
    import gi  # noqa: PLC0415 - optional system dependency, imported only when used

    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib  # noqa: PLC0415

    return Gio, GLib


def _call(bus: Any, dest: str, path: str, iface: str, method: str, args: Any, reply: str | None):
    Gio, GLib = _gio()
    return bus.call_sync(
        dest,
        path,
        iface,
        method,
        args,
        GLib.VariantType(reply) if reply else None,
        Gio.DBusCallFlags.NONE,
        10_000,
        None,
    )


def current_monitors(bus: Any) -> list[dict[str, str]]:
    """Physical and virtual monitors Mutter has now (GetCurrentState), in its order."""
    state = _call(
        bus,
        DISPLAY_CONFIG_BUS,
        DISPLAY_CONFIG_PATH,
        DISPLAY_CONFIG_BUS,
        "GetCurrentState",
        None,
        None,
    ).unpack()
    monitors = state[1]
    result = []
    for monitor in monitors:
        connector, vendor, product, serial = monitor[0]
        result.append(
            {"connector": connector, "vendor": vendor, "product": product, "serial": serial}
        )
    return result


def _emit(payload: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def _session_bus() -> Any:
    Gio, _ = _gio()
    return Gio.bus_get_sync(Gio.BusType.SESSION, None)


def cmd_query() -> int:
    try:
        bus = _session_bus()
        _emit({"monitors": current_monitors(bus)})
    except Exception as error:  # noqa: BLE001 - report every D-Bus / import failure as text
        _emit({"error": f"{type(error).__name__}: {error}"})
        return 1
    return 0


def cmd_hold(width: int, height: int, timeout: float, until_signal: bool) -> int:
    try:
        Gio, GLib = _gio()
        bus = _session_bus()
        before = {monitor["connector"] for monitor in current_monitors(bus)}
        session = _call(
            bus,
            SCREEN_CAST_BUS,
            SCREEN_CAST_PATH,
            SCREEN_CAST_BUS,
            "CreateSession",
            GLib.Variant("(a{sv})", ({},)),
            "(o)",
        ).unpack()[0]
        mode = {
            "size": GLib.Variant("(uu)", (width, height)),
            "refresh-rate": GLib.Variant("d", 60.0),
            "is-preferred": GLib.Variant("b", True),
        }
        properties = {
            "cursor-mode": GLib.Variant("u", CURSOR_MODE_HIDDEN),
            # Treat it like a real monitor (not a shared screen), as headless
            # gnome-remote-desktop does.
            "is-platform": GLib.Variant("b", True),
            # Fixed modes make Mutter create the monitor at once, with no PipeWire consumer.
            "modes": GLib.Variant("aa{sv}", [mode]),
        }
        _call(
            bus,
            SCREEN_CAST_BUS,
            session,
            SESSION_IFACE,
            "RecordVirtual",
            GLib.Variant("(a{sv})", (properties,)),
            "(o)",
        )
        _call(bus, SCREEN_CAST_BUS, session, SESSION_IFACE, "Start", None, None)
    except Exception as error:  # noqa: BLE001
        _emit({"ready": False, "error": f"{type(error).__name__}: {error}"})
        return 1

    added: list[dict[str, str]] = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            added = [m for m in current_monitors(bus) if m["connector"] not in before]
        except Exception as error:  # noqa: BLE001
            _emit({"ready": False, "error": f"{type(error).__name__}: {error}"})
            return 1
        if added:
            break
        time.sleep(0.1)
    if not added:
        _stop(bus, session)
        _emit(
            {
                "ready": False,
                "error": f"Mutter accepted the virtual monitor but no new monitor appeared "
                f"in GetCurrentState within {timeout:g}s",
            }
        )
        return 1

    _emit({"ready": True, "monitor": added[0], "session": session})

    done = threading.Event()

    def on_signal(_signum: int, _frame: Any) -> None:
        done.set()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    def wait_stdin_eof() -> None:
        try:
            while sys.stdin.buffer.read(4096):
                pass
        except OSError:
            pass
        done.set()

    if not until_signal:
        threading.Thread(target=wait_stdin_eof, daemon=True).start()
    while not done.wait(0.5):
        pass
    _stop(bus, session)
    return 0


def _stop(bus: Any, session: str) -> None:
    try:
        _call(bus, SCREEN_CAST_BUS, session, SESSION_IFACE, "Stop", None, None)
    except Exception:  # noqa: BLE001 - closing the connection on exit also removes it
        pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("query")
    hold = sub.add_parser("hold")
    hold.add_argument("--width", type=int, default=1920)
    hold.add_argument("--height", type=int, default=1080)
    hold.add_argument("--timeout", type=float, default=10.0)
    hold.add_argument("--until-signal", action="store_true")
    args = parser.parse_args(argv)
    if not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
        _emit({"ready": False, "error": "DBUS_SESSION_BUS_ADDRESS is not set"})
        return 1
    if args.command == "query":
        return cmd_query()
    return cmd_hold(args.width, args.height, args.timeout, args.until_signal)


if __name__ == "__main__":
    raise SystemExit(main())

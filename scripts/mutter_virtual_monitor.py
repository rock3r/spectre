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
  mutter_virtual_monitor.py follow [--width W] [--height H] [--timeout S] [--poll S]
      For a session-long user service. Keep a virtual monitor only while the seat has no
      physical monitor: add one after the seat has had none for a few polls, remove it as
      soon as a physical monitor appears, and add it back when the last one goes. Prints
      one JSON line per change. Runs until SIGTERM / SIGINT, then removes the monitor.

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
from typing import Any, Callable

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


def is_virtual_monitor(monitor: dict[str, str]) -> bool:
    """True for a Mutter virtual monitor: ours, or one gnome-remote-desktop adds for RDP."""
    return monitor.get("vendor") == "MetaVendor" or monitor.get("connector", "").startswith("Meta-")


def _start_virtual(bus: Any, width: int, height: int, timeout: float) -> tuple[str, dict[str, str]]:
    """Add a virtual monitor. Returns (ScreenCast session, monitor) or raises RuntimeError."""
    _, GLib = _gio()
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
    try:
        return session, _record_virtual(bus, GLib, session, before, width, height, timeout)
    except BaseException:
        # `follow` retries on the same connection, so do not leave a half-made monitor behind.
        # If Mutter will not stop it, StopFailed tells `follow` to exit instead of retrying.
        _stop_or_raise(bus, session)
        raise


def _record_virtual(
    bus: Any, GLib: Any, session: str, before: set[str], width: int, height: int, timeout: float
) -> dict[str, str]:
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

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        added = [m for m in current_monitors(bus) if m["connector"] not in before]
        if added:
            return added[0]
        time.sleep(0.1)
    raise RuntimeError(
        f"Mutter accepted the virtual monitor but no new monitor appeared "
        f"in GetCurrentState within {timeout:g}s"
    )


def cmd_hold(width: int, height: int, timeout: float, until_signal: bool) -> int:
    try:
        bus = _session_bus()
        session, monitor = _start_virtual(bus, width, height, timeout)
    except Exception as error:  # noqa: BLE001
        _emit({"ready": False, "error": f"{type(error).__name__}: {error}"})
        return 1

    _emit({"ready": True, "monitor": monitor, "session": session})

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


def run_follow(
    *,
    list_monitors: Callable[[], list[dict[str, str]]],
    create: Callable[[], dict[str, str]],
    remove: Callable[[], None],
    should_stop: Callable[[], bool],
    sleep: Callable[[float], None],
    settle_polls: int,
    poll_seconds: float = 2.0,
    emit: Callable[[dict[str, Any]], None] = _emit,
) -> None:
    """The `follow` loop, with Mutter behind plain callables so tests can drive it.

    A virtual monitor exists only while no physical monitor does. It is added after
    `settle_polls` polls in a row with no physical monitor, so a display that is still
    coming up at login does not get a virtual neighbour. It is removed on the first poll
    that sees a physical monitor, and once more when the loop stops.

    StopFailed from `create` or `remove` is never retried: it ends the loop, and the caller
    exits so that closing its D-Bus connection makes Mutter remove the monitor.
    """
    holding = False
    empty_polls = 0
    try:
        while not should_stop():
            physical = [m for m in list_monitors() if not is_virtual_monitor(m)]
            if physical:
                empty_polls = 0
                if holding:
                    remove()  # StopFailed ends the loop: the monitor is still there
                    holding = False
                    emit({"event": "removed", "physical": physical})
            elif not holding:
                empty_polls += 1
                if empty_polls >= settle_polls:
                    empty_polls = 0
                    try:
                        monitor = create()
                    except StopFailed:
                        raise
                    except Exception as error:  # noqa: BLE001 - retry after the next settle
                        emit({"event": "error", "error": f"{type(error).__name__}: {error}"})
                    else:
                        holding = True
                        emit({"event": "added", "monitor": monitor})
            sleep(poll_seconds)
    finally:
        if holding:
            remove()


def cmd_follow(width: int, height: int, timeout: float, poll: float) -> int:
    try:
        bus = _session_bus()
    except Exception as error:  # noqa: BLE001
        _emit({"event": "error", "error": f"{type(error).__name__}: {error}"})
        return 1

    done = threading.Event()

    def on_signal(_signum: int, _frame: Any) -> None:
        done.set()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    state: dict[str, str | None] = {"session": None}

    def create() -> dict[str, str]:
        session, monitor = _start_virtual(bus, width, height, timeout)
        state["session"] = session
        return monitor

    def remove() -> None:
        session = state["session"]
        if session is not None:
            _stop_or_raise(bus, session)
            state["session"] = None

    try:
        run_follow(
            list_monitors=lambda: current_monitors(bus),
            create=create,
            remove=remove,
            should_stop=done.is_set,
            sleep=done.wait,
            settle_polls=3,
            poll_seconds=poll,
        )
    except Exception as error:  # noqa: BLE001 - systemd restarts the service on failure
        _emit({"event": "error", "error": f"{type(error).__name__}: {error}"})
        return 1
    return 0


class StopFailed(RuntimeError):
    """Mutter would not stop a virtual monitor session that this process still holds."""


def _stop_or_raise(bus: Any, session: str) -> None:
    try:
        _call(bus, SCREEN_CAST_BUS, session, SESSION_IFACE, "Stop", None, None)
    except Exception as error:  # noqa: BLE001
        raise StopFailed(f"could not stop {session}: {type(error).__name__}: {error}") from error


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
    follow = sub.add_parser("follow")
    follow.add_argument("--width", type=int, default=1920)
    follow.add_argument("--height", type=int, default=1080)
    follow.add_argument("--timeout", type=float, default=10.0)
    follow.add_argument("--poll", type=float, default=2.0)
    args = parser.parse_args(argv)
    if not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
        _emit({"ready": False, "error": "DBUS_SESSION_BUS_ADDRESS is not set"})
        return 1
    if args.command == "query":
        return cmd_query()
    if args.command == "follow":
        return cmd_follow(args.width, args.height, args.timeout, args.poll)
    return cmd_hold(args.width, args.height, args.timeout, args.until_signal)


if __name__ == "__main__":
    raise SystemExit(main())

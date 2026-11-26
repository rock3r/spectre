#!/usr/bin/env python3
"""Grant the GNOME 42 or 46 RemoteDesktop portal through its real GTK dialog.

This is verification-VM tooling, not a portal mock. ``--mode portal`` proves
public D-Bus flow with a tiny client. ``--mode helper`` starts the candidate's
real ``spectre-wayland-helper --session`` and leaves it alive for the smoke.
Both accept the grant only through AT-SPI nodes owned by the exact portal PID.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from typing import NoReturn

import dbus
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib
import pyatspi
import gnome_portal_dialog_semantics as semantics

PORTAL = "org.freedesktop.portal.Desktop"
PORTAL_PATH = "/org/freedesktop/portal/desktop"
BACKEND = "org.freedesktop.impl.portal.desktop.gnome"
TITLE = "Remote Desktop"
MONITOR = 1
KEYBOARD_POINTER = 3


def fail(message: str) -> NoReturn:
    raise SystemExit(f"portal GUI verification failed: {message}")


def descendants(node):
    yield node
    for child in node:
        yield from descendants(child)


def backend_pid(bus: dbus.SessionBus) -> int:
    proxy = bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus")
    pid = int(proxy.GetConnectionUnixProcessID(BACKEND, dbus_interface="org.freedesktop.DBus"))
    executable = Path(f"/proc/{pid}/exe").resolve()
    if executable.name != "xdg-desktop-portal-gnome":
        fail(f"{BACKEND} PID {pid} has unexpected executable {executable}")
    return pid


def state_names(node) -> list[str]:
    state_set = node.getState()
    return sorted(pyatspi.stateToString(state) for state in range(int(pyatspi.STATE_LAST_DEFINED) + 1)
                  if state_set.contains(state))


def actions(node) -> list[str]:
    try:
        action = node.queryAction()
    except Exception:
        return []
    return [action.getName(index) for index in range(action.nActions)]


def clean(value: str) -> str:
    """Keep useful GTK labels while removing host-specific/control data."""
    value = re.sub(r"[\x00-\x1f\x7f]", " ", value or "").strip()
    for secret in filter(None, (os.environ.get("HOME"), os.environ.get("USER"))):
        value = value.replace(secret, "<redacted>")
    return value[:200]


def parent_indices(nodes) -> list[int | None]:
    """Recover preorder parents without comparing unstable PyGObject wrappers."""
    parents = []
    stack = []
    for index, node in enumerate(nodes):
        while stack and stack[-1][1] == 0:
            stack.pop()
        parents.append(stack[-1][0] if stack else None)
        if stack:
            stack[-1][1] -= 1
        child_count = int(getattr(node, "childCount", 0))
        if child_count:
            stack.append([index, child_count])
    return parents


def snapshot(nodes, pid: int) -> dict:
    parents = parent_indices(nodes)
    return {
        "backend": BACKEND,
        "executable": "xdg-desktop-portal-gnome",
        "pid": pid,
        "nodes": [{
            "index": index,
            "parent": parents[index],
            "role": clean(node.getRoleName()),
            "name": clean(node.name),
            "states": state_names(node),
            "actions": actions(node),
        } for index, node in enumerate(nodes)],
    }


def facts(nodes) -> list[dict]:
    parents = parent_indices(nodes)
    return [{"index": index, "parent": parents[index],
             "role": clean(node.getRoleName()), "name": clean(node.name),
             "states": state_names(node), "actions": actions(node)}
            for index, node in enumerate(nodes)]


def save_snapshot(nodes, pid: int, artifact: Path, **extra) -> None:
    tree = snapshot(nodes, pid)
    tree.update(extra)
    artifact.parent.mkdir(parents=True, exist_ok=True)
    artifact.write_text(json.dumps(tree, indent=2, sort_keys=True) + "\n")


def invoke(node, accepted=("click", "press", "activate", "toggle")) -> None:
    try:
        interface = node.queryAction()
    except Exception as error:
        fail(f"{node.getRoleName()} {node.name!r} has no action: {error}")
    matching = [i for i in range(interface.nActions)
                if interface.getName(i).lower() in accepted]
    if len(matching) != 1 or not interface.doAction(matching[0]):
        fail(f"could not uniquely activate {node.getRoleName()} {node.name!r}; actions={actions(node)}")


def expected_disappearance(error: BaseException) -> bool:
    message = str(error).lower()
    if isinstance(error, GLib.GError):
        return ("atspi_error" in message and
                any(text in message for text in ("no longer exists", "does not exist")))
    return isinstance(error, (AttributeError, TypeError)) and "nonetype" in message


def wait_for_dialog(pid: int, artifact: Path, timeout: float = 45):
    deadline = time.monotonic() + timeout
    last = "portal application absent from AT-SPI"
    last_nodes = []
    last_counts = {"applications": 0, "matchingDialogs": 0}
    while time.monotonic() < deadline:
        apps = [app for app in pyatspi.Registry.getDesktop(0) if app.get_process_id() == pid]
        last_counts["applications"] = len(apps)
        if len(apps) == 1:
            nodes = list(descendants(apps[0]))
            last_nodes = nodes
            dialogs = [node for node in nodes
                       if semantics.is_dialog(node.getRoleName(), node.name)]
            last_counts["matchingDialogs"] = len(dialogs)
            if len(dialogs) == 1:
                return nodes, dialogs[0]
            last = f"found {len(dialogs)} case-folded {TITLE!r} dialog/frame nodes"
        elif len(apps) > 1:
            last_nodes = [node for app in apps for node in descendants(app)]
            last = f"backend PID owns {len(apps)} AT-SPI applications (expected exactly one)"
        time.sleep(0.2)
    save_snapshot(last_nodes, pid, artifact, timedOut=True, counts=last_counts,
                  relevantTitles=[{"role": clean(node.getRoleName()), "name": clean(node.name)}
                                  for node in last_nodes if clean(node.name)])
    fail(f"timed out waiting for native GTK dialog from PID {pid}: {last}; "
         f"complete sanitized backend subtree and counts saved at {artifact}")


def accept_dialog(pid: int, artifact: Path, *, approve_remember: bool = False) -> dict:
    nodes, dialog = wait_for_dialog(pid, artifact)
    dialog_nodes = list(descendants(dialog))
    dialog_facts = facts(dialog_nodes)
    matched = semantics.selection(dialog_facts)
    matched_profile = matched["profile"] if matched else None
    structure_counts = {
        "shareButtons": len(semantics.share_buttons(dialog_facts)),
        "remoteInteractionControls": len(semantics.remote_interaction_controls(dialog_facts)),
        "jammyMonitorRows": len(semantics.jammy_monitor_rows(dialog_facts)),
        "nobleSelectedSources": len(semantics.selected_noble_sources(dialog_facts)),
    }
    save_snapshot(nodes, pid, artifact, matchedProfile=matched_profile,
                  matchingDialogCount=1, structureCounts=structure_counts)
    if matched_profile is None:
        fail(f"native dialog did not uniquely match GNOME 42 or GNOME 46; see {artifact}")

    # Never guess by coordinates or select a fixture window: MONITOR was the
    # only advertised source type. GNOME 46 auto-selects it; GNOME 42 exposes
    # the physical logical monitor as the sole non-virtual GtkListBox row.
    by_index = {item["index"]: node for node, item in zip(dialog_nodes, dialog_facts)}
    share = by_index[matched["share"]]
    remote_input = by_index[matched["interaction"]]
    selected_source = by_index[matched["source"]]
    if "checked" not in state_names(remote_input):
        invoke(remote_input, accepted=("toggle",))
    deadline = time.monotonic() + 5
    while "checked" not in state_names(remote_input) and time.monotonic() < deadline:
        time.sleep(0.1)
    if "checked" not in state_names(remote_input):
        fail("GNOME did not acknowledge the requested remote interaction")
    remember_facts = semantics.remember_controls(dialog_facts)
    remember = [by_index[item["index"]] for item in remember_facts]
    if approve_remember and len(remember) != 1:
        fail(f"expected exactly one showing Remember checkbox; found {len(remember)}; see {artifact}")
    if approve_remember and "checked" not in state_names(remember[0]):
        invoke(remember[0], accepted=("toggle",))
        deadline = time.monotonic() + 5
        while "checked" not in state_names(remember[0]) and time.monotonic() < deadline:
            time.sleep(0.1)
        if "checked" not in state_names(remember[0]):
            fail("GNOME did not acknowledge Remember this selection")
    if not (set(state_names(selected_source)) & {"checked", "selected", "pressed"}):
        fail(f"expected one selected physical monitor for {matched_profile}; see {artifact}")
    if "sensitive" not in state_names(share):
        fail(f"Share is not uniquely enabled after singular monitor selection; see {artifact}")

    selected = {"profile": matched_profile, "role": selected_source.getRoleName(),
                "name": clean(selected_source.name), "states": state_names(selected_source),
                "remoteInteractionApproved": "checked" in state_names(remote_input),
                "rememberAvailable": len(remember) == 1,
                "rememberApproved": len(remember) == 1 and
                "checked" in state_names(remember[0])}
    invoke(share, accepted=("click",))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            if "showing" not in state_names(dialog):
                return selected
        except (GLib.GError, AttributeError, TypeError) as error:
            # A successful close can remove the remote AT-SPI object between polls.
            if expected_disappearance(error):
                return selected
            raise
        time.sleep(0.1)
    fail(f"Share action did not close the portal dialog; see {artifact}")


def request(bus, interface: str, method: str, *args):
    """Call a request method and require its Response signal acknowledgement."""
    call = getattr(dbus.Interface(bus.get_object(PORTAL, PORTAL_PATH), interface), method)
    loop = GLib.MainLoop()
    received = {}

    # Subscribe before the method call. CreateSession/Select* may acknowledge so
    # quickly that registering against the returned path afterwards loses the
    # one-shot signal. This client owns the fresh bus connection and issues only
    # one request at a time, then verifies the signal's path against the return.
    def response(code, results, path=None):
        received.update(code=int(code), results=dict(results), path=str(path))
        loop.quit()

    match = bus.add_signal_receiver(response, signal_name="Response",
                                    dbus_interface="org.freedesktop.portal.Request",
                                    path_keyword="path")
    request_path = str(call(*args))
    timer = None
    if not received:
        timer = GLib.timeout_add_seconds(60, lambda: (loop.quit(), True)[1])
        loop.run()
    if timer is not None:
        GLib.source_remove(timer)
    match.remove()
    if not received:
        fail(f"{interface}.{method} emitted no Response for {request_path}")
    if received["path"] != request_path:
        fail(f"{interface}.{method} response path {received['path']} did not match {request_path}")
    if received["code"] != 0:
        fail(f"{interface}.{method} was denied (response {received['code']})")
    return received["results"]


def portal_client(result_path: Path) -> None:
    DBusGMainLoop(set_as_default=True)
    bus = dbus.SessionBus()
    token = uuid.uuid4().hex
    created = request(bus, "org.freedesktop.portal.RemoteDesktop", "CreateSession", {
        "handle_token": f"create{token}", "session_handle_token": f"session{token}"})
    session = str(created.get("session_handle", ""))
    if not session.startswith("/org/freedesktop/portal/desktop/session/"):
        fail(f"CreateSession returned invalid session handle {session!r}")
    try:
        request(bus, "org.freedesktop.portal.RemoteDesktop", "SelectDevices",
                dbus.ObjectPath(session), {"handle_token": f"devices{token}",
                                           "types": dbus.UInt32(KEYBOARD_POINTER)})
        request(bus, "org.freedesktop.portal.ScreenCast", "SelectSources",
                dbus.ObjectPath(session), {"handle_token": f"sources{token}",
                                           "types": dbus.UInt32(MONITOR),
                                           "multiple": dbus.Boolean(False)})
        started = request(bus, "org.freedesktop.portal.RemoteDesktop", "Start",
                          dbus.ObjectPath(session), "", {"handle_token": f"start{token}"})
        streams = list(started.get("streams", []))
        devices = int(started.get("devices", 0))
        if devices != KEYBOARD_POINTER or len(streams) != 1:
            fail(f"Start grant mismatch: devices={devices}, streams={len(streams)}")
        result_path.write_text(json.dumps({"devices": devices, "streamCount": len(streams),
                                          "granted": True}, sort_keys=True) + "\n")
    finally:
        dbus.Interface(bus.get_object(PORTAL, session), "org.freedesktop.portal.Session").Close()


def run_portal_mode(args) -> None:
    result = args.artifacts / "portal-public-grant.json"
    child = subprocess.Popen([sys.executable, __file__, "--internal-portal-client", str(result)])
    selected = accept_dialog(backend_pid(dbus.SessionBus()),
                             args.artifacts / "portal-atspi-tree.json")
    if child.wait(timeout=30) != 0 or not result.is_file():
        fail(f"public portal client failed with exit {child.returncode}")
    proof = json.loads(result.read_text())
    proof["selectedSource"] = selected
    result.write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n")
    print(f"public RemoteDesktop grant confirmed and closed: {proof}")


def run_helper_mode(args) -> None:
    if not args.helper or not args.helper.is_file() or not os.access(args.helper, os.X_OK):
        fail(f"candidate helper is not executable: {args.helper}")
    log_path = args.artifacts / "candidate-helper.log"
    log = log_path.open("w")
    child = subprocess.Popen([str(args.helper), "--session"], stdout=log,
                             stderr=subprocess.STDOUT, start_new_session=True)
    selected = accept_dialog(backend_pid(dbus.SessionBus()),
                             args.artifacts / "helper-atspi-tree.json")
    session_dir = Path(os.environ.get("SPECTRE_WAYLAND_SESSION_DIR", ""))
    socket = session_dir / "wayland-session.sock"
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and not socket.is_socket() and child.poll() is None:
        time.sleep(0.1)
    if child.poll() is not None or not socket.is_socket():
        fail(f"candidate helper did not keep a granted session/socket (exit={child.poll()}); see {log_path}")
    args.pid_file.write_text(f"{child.pid}\n")
    (args.artifacts / "helper-grant.json").write_text(json.dumps({
        "granted": True, "helperPid": child.pid, "selectedSource": selected,
        "sessionSocket": str(socket)}, indent=2, sort_keys=True) + "\n")
    print(f"candidate helper RemoteDesktop grant confirmed; PID {child.pid} remains alive")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("portal", "helper"))
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--helper", type=Path)
    parser.add_argument("--pid-file", type=Path)
    parser.add_argument("--internal-portal-client", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.internal_portal_client:
        return args
    if not args.mode or not args.artifacts:
        parser.error("--mode and --artifacts are required")
    if args.mode == "helper" and not args.pid_file:
        parser.error("--pid-file is required in helper mode")
    return args


def main() -> None:
    args = parse_args()
    if args.internal_portal_client:
        portal_client(args.internal_portal_client)
    elif args.mode == "portal":
        run_portal_mode(args)
    else:
        run_helper_mode(args)


if __name__ == "__main__":
    main()

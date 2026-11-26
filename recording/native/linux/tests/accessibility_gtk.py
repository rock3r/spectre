"""Snapshot a real GTK dialog, then activate the captured button through AT-SPI."""
import json
import subprocess
import sys
import time

import dbus
import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi


def walk(node):
    yield node
    for index in range(node.get_child_count()):
        yield from walk(node.get_child_at_index(index))


def direct_native_role(accessible, pid):
    session_bus = dbus.SessionBus()
    address = session_bus.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
        dbus_interface="org.a11y.Bus"
    )
    accessibility_bus = dbus.bus.BusConnection(address)
    daemon = dbus.Interface(
        accessibility_bus.get_object(
            "org.freedesktop.DBus", "/org/freedesktop/DBus", introspect=False
        ),
        "org.freedesktop.DBus",
    )
    desktop = dbus.Interface(
        accessibility_bus.get_object(
            "org.a11y.atspi.Registry",
            "/org/a11y/atspi/accessible/root",
            introspect=False,
        ),
        "org.a11y.atspi.Accessible",
    )
    matching_names = [
        str(name)
        for name, _ in desktop.GetChildren()
        if int(daemon.GetConnectionUnixProcessID(name)) == pid
    ]
    assert len(matching_names) == 1, (pid, matching_names)
    unique_name, = matching_names
    native = dbus.Interface(
        accessibility_bus.get_object(unique_name, accessible.path, introspect=False),
        "org.a11y.atspi.Accessible",
    )
    raw_role = int(native.GetRole())
    native_role_name = str(native.GetRoleName())
    assert raw_role == 43, (unique_name, accessible.path, raw_role)
    assert native_role_name == "button", (unique_name, accessible.path, native_role_name)
    return unique_name, accessible.path, raw_role, native_role_name


def wait_for_button(pid):
    desktop = Atspi.get_desktop(0)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        apps = (desktop.get_child_at_index(i) for i in range(desktop.get_child_count()))
        for app in apps:
            if app.get_process_id() == pid:
                for node in walk(app):
                    if node.get_role() == Atspi.Role.PUSH_BUTTON and node.get_name() == "Complete proof":
                        return node
        time.sleep(0.05)
    raise AssertionError("Zenity did not publish Complete proof on AT-SPI")


dialog = subprocess.Popen(["zenity", "--question", "--title=Spectre GTK regression",
                           "--text=Verify the native button", "--ok-label=Complete proof"])
try:
    button = wait_for_button(dialog.pid)
    request = {"target": {"type": "process-id", "value": dialog.pid},
               "maximumDepth": 16, "maximumNodeCount": 100, "timeoutMs": 5000,
               "allowAccessibilityEnable": True}
    result = subprocess.run([sys.argv[1], "accessibility-snapshot"], input=json.dumps(request),
                            capture_output=True, text=True, check=True, timeout=10)
    response = json.loads(result.stdout)
    assert response["ok"], response
    node, = (n for n in response["snapshot"]["nodes"]
             if n["name"] == "Complete proof" and n["role"] == "button")

    unique_name, object_path, raw_role, native_role_name = direct_native_role(button, dialog.pid)
    assert raw_role == 43, raw_role
    assert node["platformRole"] == native_role_name == "button", node
    assert button.get_state_set().contains(Atspi.StateType.SENSITIVE)
    assert "sensitive" in node["states"], node
    assert {"disabled", "selected", "enabled"}.isdisjoint(node["states"]), node
    assert button.do_action(0)
    assert dialog.wait(timeout=5) == 0
    print("accessibility-gtk: activated", unique_name, object_path, node["platformRole"], node["states"])
finally:
    if dialog.poll() is None:
        dialog.kill()
    dialog.wait()

#!/usr/bin/env python3
"""Live AT-SPI fixture assertion. This is a fixture probe, not Spectre's future reader."""
import argparse, json, subprocess, sys, time
import pyatspi

def status_enabled():
    out = subprocess.check_output(["gdbus", "call", "--session", "--dest", "org.a11y.Bus", "--object-path", "/org/a11y/bus", "--method", "org.freedesktop.DBus.Properties.Get", "org.a11y.Status", "IsEnabled"], text=True)
    return "true" in out.lower()

def set_enabled(value):
    subprocess.check_call(["gdbus", "call", "--session", "--dest", "org.a11y.Bus", "--object-path", "/org/a11y/bus", "--method", "org.freedesktop.DBus.Properties.Set", "org.a11y.Status", "IsEnabled", f"<{'true' if value else 'false'}>"])

def walk(node):
    yield node
    for child in node:
        yield from walk(child)

def attr(node, name):
    if name == "id" and hasattr(node, "get_accessible_id"):
        value = node.get_accessible_id()
        if value: return value
    attrs = node.get_attributes()
    needle = name + ":"
    return next((x[len(needle):] for x in attrs if x.startswith(needle)), None)

def find_window(deadline):
    while time.time() < deadline:
        for app in pyatspi.Registry.getDesktop(0):
            for node in walk(app):
                if node.name == "Spectre AccessKit Fixture": return node
        time.sleep(.2)
    raise RuntimeError("fixture window did not register with AT-SPI")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--enable", action="store_true")
    parser.add_argument("--golden", required=True)
    args = parser.parse_args()
    original = status_enabled()
    if not original and not args.enable:
        print("accessibility-disabled", file=sys.stderr)
        return 2
    try:
        if not original: set_enabled(True)
        golden = json.load(open(args.golden, encoding="utf-8"))
        window = find_window(time.time() + 15)
        nodes = list(walk(window))
        by_id = {}
        for node in nodes: by_id.setdefault(attr(node, "id"), []).append(node)
        assert window.get_role_name() == golden["windowRole"]
        assert by_id["primary-button"][0].get_role_name() == golden["buttonRole"]
        assert by_id["newsletter"][0].get_role_name() == golden["checkboxRole"]
        assert by_id["newsletter"][0].get_state_set().contains(pyatspi.STATE_CHECKED)
        assert by_id["account-name"][0].get_role_name() == golden["textRole"]
        try:
            by_id["account-name"][0].queryText()
            linux_value = by_id["account-name"][0].queryText().getText(0, -1)
        except NotImplementedError:
            linux_value = None
        assert linux_value == golden["textValue"]
        assert len(by_id["repeated"]) == 3
        assert not by_id["disabled-button"][0].get_state_set().contains(pyatspi.STATE_ENABLED)
        assert by_id["offscreen-button"][0].queryComponent().get_extents(pyatspi.DESKTOP_COORDS).y > 480
        by_id["popover-trigger"][0].queryAction().doAction(0)
        deadline = time.time() + 5
        while time.time() < deadline:
            nodes = list(walk(window))
            if any(attr(node, "id") == "details-popover" and node.get_role_name() == golden["popoverRole"] for node in nodes): break
            time.sleep(.1)
        else: raise AssertionError("popover did not open")
        print("accesskit-fixture-linux: pass")
        return 0
    finally:
        if not original: set_enabled(False)

if __name__ == "__main__": raise SystemExit(main())

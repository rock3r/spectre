#!/usr/bin/env python3
"""Live AT-SPI fixture assertion; not Spectre's production reader."""
import argparse
import json
import subprocess
import sys
import time

import pyatspi


def status_enabled():
    out = subprocess.check_output([
        "gdbus", "call", "--session", "--dest", "org.a11y.Bus",
        "--object-path", "/org/a11y/bus", "--method",
        "org.freedesktop.DBus.Properties.Get", "org.a11y.Status", "IsEnabled",
    ], text=True)
    return "true" in out.lower()


def set_enabled(value):
    subprocess.check_call([
        "gdbus", "call", "--session", "--dest", "org.a11y.Bus",
        "--object-path", "/org/a11y/bus", "--method",
        "org.freedesktop.DBus.Properties.Set", "org.a11y.Status", "IsEnabled",
        f"<{'true' if value else 'false'}>",
    ], stdout=subprocess.DEVNULL)


def walk(node):
    yield node
    for index in range(node.childCount):
        yield from walk(node.getChildAtIndex(index))


def attr(node, name):
    if name == "id" and hasattr(node, "get_accessible_id"):
        value = node.get_accessible_id()
        if value:
            return value
    attributes = node.get_attributes()
    if isinstance(attributes, dict):
        return attributes.get(name)
    needle = name + ":"
    return next((value[len(needle):] for value in attributes if value.startswith(needle)), None)


def find_window(deadline):
    while time.monotonic() < deadline:
        for app in pyatspi.Registry.getDesktop(0):
            for node in walk(app):
                if node.name == "Spectre AccessKit Fixture":
                    return node
        time.sleep(0.2)
    raise RuntimeError("fixture window did not register with AT-SPI")


def require(by_id, identifier, count=1):
    matches = by_id.get(identifier, [])
    if len(matches) != count:
        raise AssertionError(f"expected {count} node(s) with id {identifier!r}, got {len(matches)}")
    return matches[0]


def assert_role(node, expected, label):
    observed_id = int(node.getRole())
    observed_name = node.getRoleName()
    expected_id = expected["id"]
    expected_name = expected["name"]
    if observed_id != expected_id:
        raise AssertionError(
            f"{label} role mismatch: expected {expected_name!r} (id {expected_id}), "
            f"observed {observed_name!r} (id {observed_id})"
        )


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
        if not original:
            set_enabled(True)
        with open(args.golden, encoding="utf-8") as golden_file:
            golden = json.load(golden_file)
        window = find_window(time.monotonic() + 15)
        nodes = list(walk(window))
        by_id = {}
        for node in nodes:
            by_id.setdefault(attr(node, "id"), []).append(node)
        button = require(by_id, "primary-button")
        checkbox = require(by_id, "newsletter")
        text = require(by_id, "account-name")
        disabled = require(by_id, "disabled-button")
        offscreen = require(by_id, "offscreen-button")
        nested = require(by_id, "nested-group")
        nested_label = require(by_id, "nested-label")
        trigger = require(by_id, "popover-trigger")
        repeated = by_id.get("repeated", [])
        assert_role(window, golden["windowRole"], "window")
        assert_role(button, golden["buttonRole"], "button")
        assert_role(checkbox, golden["checkboxRole"], "checkbox")
        assert checkbox.get_state_set().contains(pyatspi.STATE_CHECKED)
        assert golden["checkedState"] == "checked"
        assert_role(text, golden["textRole"], "text")
        try:
            linux_value = text.queryText().getText(0, -1)
        except NotImplementedError:
            linux_value = None
        assert linux_value == golden["textValue"]
        assert len(repeated) == 3
        assert len({node.parent for node in repeated}) == 2
        assert nested_label.parent == nested
        try:
            disabled_actions = disabled.queryAction().nActions
        except NotImplementedError:
            disabled_actions = 0
        native_disabled = "disabled" if disabled_actions == 0 else "enabled"
        assert native_disabled == golden["disabledState"]
        assert offscreen.queryComponent().getExtents(pyatspi.DESKTOP_COORDS).y > 480
        trigger.queryAction().doAction(0)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            nodes = list(walk(window))
            popovers = [node for node in nodes if attr(node, "id") == "details-popover"]
            if popovers:
                assert_role(popovers[0], golden["popoverRole"], "popover")
                break
            time.sleep(0.1)
        else:
            raise AssertionError("popover did not open with expected role")
        print("accesskit-fixture-linux: pass")
        return 0
    finally:
        if not original:
            set_enabled(False)


if __name__ == "__main__":
    raise SystemExit(main())

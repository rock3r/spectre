#!/usr/bin/env python3
"""Prove native AT-SPI owner selection with two same-title GTK4 providers."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import dbus


TITLE = "Spectre distinct GTK ownership"
CONTROLS = ("Orchard saffron", "Harbor indigo")
QUERY_TIMEOUT_MS = 5000


def fixture(control):
    import gi

    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk

    app = Gtk.Application(application_id=f"dev.sebastiano.SpectreDistinct{control.split()[0]}")

    def activate(application):
        window = Gtk.ApplicationWindow(application=application, title=TITLE)
        window.set_default_size(360, 120)
        window.set_child(Gtk.Button(label=control))
        window.present()
        print(f"SPECTRE_GTK_FIXTURE_READY pid={os.getpid()} control={control}", flush=True)

    app.connect("activate", activate)
    return app.run([])


def provider_inventory():
    """Inventory providers directly through AT-SPI and the session bus, never the helper."""
    session = dbus.SessionBus()
    address = session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
        dbus_interface="org.a11y.Bus")
    bus = dbus.bus.BusConnection(str(address))
    accessible = "org.a11y.atspi.Accessible"
    registry = dbus.Interface(
        bus.get_object("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root"),
        accessible)
    daemon = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
                            "org.freedesktop.DBus")
    found = []
    for service, path in registry.GetChildren():
        bus_name = str(service)
        try:
            pid = int(daemon.GetConnectionUnixProcessID(bus_name))
        except dbus.DBusException:
            continue
        application = dbus.Interface(bus.get_object(service, path), accessible)
        for child_service, child_path in application.GetChildren():
            obj = bus.get_object(child_service, child_path)
            child = dbus.Interface(obj, accessible)
            properties = dbus.Interface(obj, "org.freedesktop.DBus.Properties")
            title = str(properties.Get(accessible, "Name"))
            if title != TITLE:
                continue
            names = []
            pending = list(child.GetChildren())
            while pending:
                node_service, node_path = pending.pop()
                node_obj = bus.get_object(node_service, node_path)
                node = dbus.Interface(node_obj, accessible)
                node_properties = dbus.Interface(node_obj, "org.freedesktop.DBus.Properties")
                name = str(node_properties.Get(accessible, "Name"))
                if name:
                    names.append(name)
                pending.extend(node.GetChildren())
            found.append({"pid": pid, "busName": bus_name, "title": title,
                          "childNames": sorted(set(names))})
    return found


def wait_for_providers(expected_pids, evidence_path):
    deadline = time.monotonic() + 20
    latest = []
    while time.monotonic() < deadline:
        latest = provider_inventory()
        matching = [item for item in latest if item["pid"] in expected_pids]
        by_pid = {item["pid"]: item for item in matching}
        if (len(latest) == 2 and len(matching) == 2 and set(by_pid) == set(expected_pids)
                and all(CONTROLS[index] in by_pid[pid]["childNames"]
                        for index, pid in enumerate(expected_pids))):
            evidence_path.write_text(json.dumps(matching, indent=2) + "\n")
            return matching
        time.sleep(0.1)
    evidence_path.write_text(json.dumps(latest, indent=2) + "\n")
    raise RuntimeError(f"AT-SPI did not expose exactly two expected providers: {latest}")


def invoke(helper, payload, stem):
    encoded = json.dumps(payload, separators=(",", ":")).encode()
    stem.with_suffix(".request.json").write_bytes(encoded + b"\n")
    started = time.monotonic_ns()
    try:
        run = subprocess.run([str(helper), "accessibility-snapshot"], input=encoded,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, check=False)
        elapsed = (time.monotonic_ns() - started) / 1_000_000
        stem.with_suffix(".response.json").write_bytes(run.stdout)
        stem.with_suffix(".stderr").write_bytes(run.stderr)
        try:
            reply = json.loads(run.stdout)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            return elapsed, run.returncode, None, f"invalid helper JSON: {error}"
        if run.returncode:
            return elapsed, run.returncode, reply, f"helper exited {run.returncode}"
        return elapsed, run.returncode, reply, None
    except subprocess.TimeoutExpired as error:
        elapsed = (time.monotonic_ns() - started) / 1_000_000
        stem.with_suffix(".response.json").write_bytes(error.stdout or b"")
        stem.with_suffix(".stderr").write_bytes(error.stderr or b"")
        return elapsed, None, None, "helper exceeded the 10s harness bound"


def scoped_valid(reply, own, other):
    if not reply or reply.get("ok") is not True:
        return False, "expected successful typed reply"
    snapshot = reply.get("snapshot", {})
    windows = snapshot.get("windows", [])
    names = {str(node.get("name", "")) for node in snapshot.get("nodes", [])}
    valid = (len(windows) == 1 and windows[0].get("title") == TITLE
             and own in names and other not in names)
    return valid, f"expected title and only {own!r}, observed names={sorted(names)!r}"


def ambiguous_valid(reply):
    error = (reply or {}).get("error", {})
    message = str(error.get("message", ""))
    valid = ((reply or {}).get("ok") is False and error.get("code") == "target-not-found"
             and TITLE in message and "ambiguous" in message.lower() and "2" in message)
    return valid, f"expected typed ambiguity identifying 2 roots, observed {error!r}"


def snapshot_request(pid=None):
    target = {"type": "window-title", "value": TITLE}
    if pid is not None:
        target["processId"] = pid
    return {"target": target, "maximumDepth": 64, "maximumNodeCount": 10000,
            "timeoutMs": QUERY_TIMEOUT_MS, "allowAccessibilityEnable": False}


def run_probe(args):
    output = args.artifacts / "atspi-distinct-content"
    output.mkdir(parents=True, exist_ok=True)
    properties = dbus.Interface(dbus.SessionBus().get_object("org.a11y.Bus", "/org/a11y/bus"),
                                "org.freedesktop.DBus.Properties")
    initial_status = bool(properties.Get("org.a11y.Status", "IsEnabled"))
    children = []
    logs = []
    results = []
    started = time.monotonic_ns()
    failure = None
    try:
        if "DISPLAY" in os.environ:
            raise RuntimeError(f"native Wayland probe requires DISPLAY unset, got {os.environ['DISPLAY']!r}")
        properties.Set("org.a11y.Status", "IsEnabled", dbus.Boolean(True))
        for index, control in enumerate(CONTROLS):
            log = (output / f"fixture-{index}.log").open("wb")
            fixture_environment = {**os.environ, "GTK_A11Y": "atspi", "GDK_BACKEND": "wayland"}
            fixture_environment.pop("DISPLAY", None)
            child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()),
                                      "--fixture-control", control], stdout=log,
                                     stderr=subprocess.STDOUT,
                                     env=fixture_environment)
            children.append(child)
            logs.append(log)
        providers = wait_for_providers([child.pid for child in children], output / "providers.json")
        cases = [("owner-a-first", 0), ("owner-b-first", 1),
                 ("owner-b-second", 1), ("owner-a-second", 0)]
        for name, index in cases:
            if any(child.poll() is not None for child in children):
                raise RuntimeError("a GTK provider exited after readiness")
            payload = snapshot_request(children[index].pid)
            elapsed, code, reply, error = invoke(args.helper, payload, output / name)
            valid, expectation = scoped_valid(reply, CONTROLS[index], CONTROLS[1 - index])
            result = {"case": name, "pid": children[index].pid, "elapsedMs": elapsed,
                      "helperReturnCode": code, "reply": reply, "passed": error is None and valid,
                      "failure": error or (None if valid else expectation)}
            results.append(result)
        payload = snapshot_request()
        elapsed, code, reply, error = invoke(args.helper, payload, output / "ambiguous-title")
        valid, expectation = ambiguous_valid(reply)
        results.append({"case": "ambiguous-title", "elapsedMs": elapsed,
                        "helperReturnCode": code, "reply": reply,
                        "passed": error is None and valid,
                        "failure": error or (None if valid else expectation)})
        if any(child.poll() is not None for child in children):
            raise RuntimeError("a GTK provider was not simultaneously live through all requests")
    except Exception as error:  # Preserve evidence and emit reports for harness/readiness failures.
        failure = f"{type(error).__name__}: {error}"
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
        for child in children:
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        for log in logs:
            log.close()
        properties.Set("org.a11y.Status", "IsEnabled", dbus.Boolean(initial_status))

    summary = {"title": TITLE, "controls": list(CONTROLS),
               "fixturePids": [child.pid for child in children],
               "providers": locals().get("providers", []), "initialAccessibilityEnabled": initial_status,
               "runtimeMs": (time.monotonic_ns() - started) / 1_000_000,
               "results": results, "harnessFailure": failure,
               "passed": failure is None and len(results) == 5 and all(r["passed"] for r in results)}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    suite = ET.Element("testsuite", name="gnome-atspi-distinct-content",
                       tests=str(len(results) + (failure is not None)),
                       failures=str(sum(not r["passed"] for r in results) + (failure is not None)))
    for result in results:
        case = ET.SubElement(suite, "testcase", classname="atspi.distinct-content",
                             name=result["case"], time=f'{result["elapsedMs"] / 1000:.6f}')
        ET.SubElement(case, "system-out").text = json.dumps(result, separators=(",", ":"))
        if not result["passed"]:
            ET.SubElement(case, "failure", message=result["failure"] or "failed")
    if failure:
        case = ET.SubElement(suite, "testcase", classname="atspi.distinct-content", name="harness")
        ET.SubElement(case, "failure", message=failure).text = failure
    ET.ElementTree(suite).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)
    return 0 if summary["passed"] else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-control", choices=CONTROLS)
    parser.add_argument("--helper", type=Path)
    parser.add_argument("--artifacts", type=Path)
    args = parser.parse_args()
    if args.fixture_control:
        raise SystemExit(fixture(args.fixture_control))
    if not args.helper or not args.artifacts:
        parser.error("--helper and --artifacts are required in probe mode")
    raise SystemExit(run_probe(args))


if __name__ == "__main__":
    main()

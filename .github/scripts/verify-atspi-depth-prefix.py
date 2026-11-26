#!/usr/bin/env python3
"""Wire-level proof that cached AT-SPI enrichment stops at the depth prefix."""

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

import dbus


ACCESSIBLE = "org.a11y.atspi.Accessible"
COMPONENT = "org.a11y.atspi.Component"
PROPERTIES = "org.freedesktop.DBus.Properties"
ENRICHMENT_METHODS = {
    (ACCESSIBLE, "GetAttributes"),
    (ACCESSIBLE, "GetRoleName"),
    (COMPONENT, "GetExtents"),
    ("org.a11y.atspi.Text", "GetText"),
}
CALL = re.compile(
    r"method call .* sender=([^ ]+) -> destination=([^ ]+).* "
    r"path=([^;]+); interface=([^;]+); member=([^ ]+)"
)


def payload(pid, depth):
    return json.dumps({
        "target": {"type": "process-id", "value": pid},
        "maximumDepth": depth,
        "maximumNodeCount": 10000,
        "timeoutMs": 5000,
        "allowAccessibilityEnable": True,
    })


def children(bus, owner, path):
    obj = bus.get_object(owner, path)
    return [(str(name), str(child)) for name, child in dbus.Interface(obj, ACCESSIBLE).GetChildren()]


def raw_node(bus, owner, path):
    obj = bus.get_object(owner, path)
    accessible = dbus.Interface(obj, ACCESSIBLE)
    attributes = {str(key): str(value) for key, value in accessible.GetAttributes().items()}
    name = str(dbus.Interface(obj, PROPERTIES).Get(ACCESSIBLE, "Name"))
    return {
        "path": path,
        "id": attributes.get("id"),
        "name": name,
        "children": [child_path for _, child_path in children(bus, owner, path)],
    }


def raw_shape(bus, owner, window_path):
    root = raw_node(bus, owner, window_path)
    immediate = [raw_node(bus, owner, path) for _, path in children(bus, owner, window_path)]
    assert len(immediate) == 38, f"expected the real fixture's 38 immediate children: {len(immediate)}"
    expected = [
        ("primary-button", "Save changes"),
        ("newsletter", "Receive updates"),
        ("account-name", "Account name"),
        ("group-a", "First group"),
    ]
    assert [(node["id"], node["name"]) for node in immediate[:4]] == expected, immediate[:4]
    assert len(immediate[3]["children"]) == 2, immediate[3]
    assert [node["id"] for node in immediate[4:6]] == ["group-b", "disabled-button"]
    assert [node["id"] for node in immediate[8:]] == [f"large-branch-{i}" for i in range(30)]
    for node in immediate[8:]:
        assert len(node["children"]) == 50, node

    first_nonleaf = next(index for index, node in enumerate(immediate) if node["children"])
    prefix = [root, *immediate[: first_nonleaf + 1]]
    assert len(prefix) == 5 and first_nonleaf == 3, prefix
    return {"root": root, "immediate": immediate, "prefix": prefix,
            "afterNonleaf": immediate[first_nonleaf + 1:]}


def names_for_pid(admin, pid):
    matches = set()
    for name in admin.ListNames():
        name = str(name)
        if not name.startswith(":"):
            continue
        try:
            if int(admin.GetConnectionUnixProcessID(name)) == pid:
                matches.add(name)
        except dbus.DBusException:
            pass
    return matches


def monitored_request(label, helper, target_pid, depth, address, owner, admin, artifacts):
    trace = artifacts / f"depth-prefix-{label}.monitor.log"
    expression = f"type='method_call',destination='{owner}'"
    boundary = {"monitorStartedNs": time.monotonic_ns()}
    response = None
    helper_names = set()
    proc = None
    with trace.open("w") as output:
        monitor = subprocess.Popen(
            ["dbus-monitor", "--address", address, expression],
            stdout=output, stderr=subprocess.STDOUT, text=True,
        )
        try:
            time.sleep(0.25)
            assert monitor.poll() is None, f"{label}: monitor did not start"
            boundary["requestStartedNs"] = time.monotonic_ns()
            proc = subprocess.Popen(
                [str(helper), "accessibility-snapshot"], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            proc.stdin.write(payload(target_pid, depth))
            proc.stdin.close()
            proc.stdin = None
            while proc.poll() is None:
                helper_names.update(names_for_pid(admin, proc.pid))
            stdout, stderr = proc.communicate(timeout=15)
            boundary["requestFinishedNs"] = time.monotonic_ns()
            boundary["helperPid"] = proc.pid
            boundary["helperReturnCode"] = proc.returncode
            assert proc.returncode == 0, f"{label}: helper failed: {stderr}"
            response = json.loads(stdout)
        finally:
            monitor.send_signal(signal.SIGINT)
            monitor.wait(timeout=5)
            output.flush()
            os.fsync(output.fileno())
            boundary["monitorFlushedNs"] = time.monotonic_ns()

    calls = []
    for line in trace.read_text().splitlines():
        match = CALL.search(line)
        if match:
            sender, destination, path, interface, member = match.groups()
            calls.append({"sender": sender, "destination": destination, "path": path,
                          "interface": interface, "member": member})
    enrichment = [call for call in calls
                  if (call["interface"], call["member"]) in ENRICHMENT_METHODS]
    traced_senders = {call["sender"] for call in enrichment}
    helper_senders = traced_senders & helper_names
    assert len(helper_senders) == 1, (
        f"{label}: could not tie enrichment sender to helper pid {proc.pid}: "
        f"traced={traced_senders}, pidNames={helper_names}"
    )
    sender = next(iter(helper_senders))
    enrichment = [call for call in enrichment if call["sender"] == sender]
    assert enrichment, f"{label}: helper made no per-node enrichment calls"
    assert all(call["destination"] == owner for call in enrichment)
    artifact = {"label": label, "depth": depth, "helperSender": sender,
                "traceBoundary": boundary, "response": response, "calls": calls,
                "enrichmentCalls": enrichment}
    (artifacts / f"depth-prefix-{label}.json").write_text(json.dumps(artifact, indent=2))
    return response, enrichment


def response_nodes(response):
    if response["ok"]:
        return response["snapshot"]["nodes"]
    return response["error"]["partialSnapshot"]["nodes"]


def assert_protocol_shape(limited, full):
    assert set(limited) == {"ok", "error"} and limited["ok"] is False
    error = limited["error"]
    assert set(error) == {"code", "message", "partialSnapshot"}
    assert error["code"] == "limit-reached"
    assert error["message"] == "maximumDepth 1 reached"
    assert set(full) == {"ok", "snapshot"} and full["ok"] is True
    expected_snapshot = {"schemaVersion", "source", "capturedAt", "windows", "nodes", "notes"}
    assert set(error["partialSnapshot"]) == expected_snapshot
    assert set(full["snapshot"]) == expected_snapshot


def paths(calls):
    return {call["path"] for call in calls}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--pre-cutoff", required=True, type=Path)
    parser.add_argument("--artifacts", required=True, type=Path)
    args = parser.parse_args()
    args.artifacts.mkdir(parents=True, exist_ok=True)

    session = dbus.SessionBus()
    address = str(session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
        dbus_interface="org.a11y.Bus"))
    bus = dbus.bus.BusConnection(address)
    admin = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
                           "org.freedesktop.DBus")
    registry = dbus.Interface(bus.get_object("org.a11y.atspi.Registry",
                                             "/org/a11y/atspi/accessible/root"), ACCESSIBLE)
    owner, app_path = next((str(name), str(path)) for name, path in registry.GetChildren()
                           if int(admin.GetConnectionUnixProcessID(name)) == args.pid)
    assert owner.startswith(":") and int(admin.GetConnectionUnixProcessID(owner)) == args.pid
    windows = children(bus, owner, app_path)
    assert len(windows) == 1, f"expected fixture's sole window, got {windows}"

    before = raw_shape(bus, owner, windows[0][1])
    (args.artifacts / "depth-prefix-provider-before.json").write_text(json.dumps(
        {"pid": args.pid, "owner": owner, "application": app_path, "shape": before}, indent=2))
    old_response, old_calls = monitored_request("pre-cutoff", args.pre_cutoff, args.pid, 1,
                                                address, owner, admin, args.artifacts)
    limited, limited_calls = monitored_request("candidate-depth-1", args.candidate, args.pid, 1,
                                               address, owner, admin, args.artifacts)
    full, full_calls = monitored_request("candidate-unrestricted", args.candidate, args.pid, 64,
                                         address, owner, admin, args.artifacts)
    after = raw_shape(bus, owner, windows[0][1])
    assert before == after, "provider tree changed across the monitored proof"
    (args.artifacts / "depth-prefix-provider-after.json").write_text(json.dumps(after, indent=2))

    prefix_paths = [node["path"] for node in before["prefix"]]
    late_sibling_paths = {node["path"] for node in before["afterNonleaf"]}
    forbidden_paths = late_sibling_paths | set(before["prefix"][-1]["children"])
    old_paths, limited_paths, full_paths = map(paths, (old_calls, limited_calls, full_calls))
    assert set(prefix_paths) <= old_paths
    assert late_sibling_paths <= old_paths, "exact 5f reader did not exhibit whole-depth enrichment"
    assert set(prefix_paths) <= limited_paths, "candidate did not enrich every prefix node"
    assert not (limited_paths & forbidden_paths), f"candidate made late calls: {limited_paths & forbidden_paths}"
    assert forbidden_paths <= full_paths, "full positive control omitted nodes beyond the prefix"

    assert_protocol_shape(limited, full)
    limited_nodes, full_nodes = response_nodes(limited), response_nodes(full)
    assert len(limited_nodes) == len(prefix_paths) == 5
    stable = ("id", "key", "parentKey", "name")
    assert [{key: node.get(key) for key in stable} for node in limited_nodes] == [
        {key: node.get(key) for key in stable} for node in full_nodes[:len(limited_nodes)]
    ]
    assert [node.get("id") for node in limited_nodes] == [node["id"] for node in before["prefix"]]
    full_ids = {node.get("id") for node in full_nodes}
    assert {"group-b", "disabled-button", "large-branch-29"} <= full_ids
    assert old_response, "retain the exact pre-cutoff response beside its wire trace"

    summary = {"pid": args.pid, "owner": owner, "prefix": before["prefix"],
               "preCutoffUniquePaths": sorted(old_paths),
               "candidateUniquePaths": sorted(limited_paths),
               "positiveControlUniquePaths": sorted(full_paths),
               "candidateMethods": sorted({call["member"] for call in limited_calls})}
    (args.artifacts / "depth-prefix-summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()

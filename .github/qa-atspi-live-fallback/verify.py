#!/usr/bin/python3
"""Acceptance assertions for the real, cache-less AT-SPI fixture."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import time

import dbus

ACC = "org.a11y.atspi.Accessible"


def request(helper, pid, depth, output, timeout=12):
    body = json.dumps({"target": {"type": "process-id", "value": pid},
                       "maximumDepth": depth, "maximumNodeCount": 100,
                       "timeoutMs": 5000, "allowAccessibilityEnable": True})
    stderr = output.with_suffix(".stderr")
    with output.open("w") as stdout, stderr.open("w") as errors:
        process = subprocess.Popen([str(helper), "accessibility-snapshot"], stdin=subprocess.PIPE,
                                   stdout=stdout, stderr=errors, text=True)
        process.communicate(body, timeout=timeout)
    assert process.returncode == 0, (process.returncode, stderr.read_text())
    return process.pid, json.loads(output.read_text())


def nodes(response):
    return (response["snapshot"] if response["ok"] else response["error"]["partialSnapshot"])["nodes"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", required=True, type=Path)
    parser.add_argument("--ready", required=True, type=Path)
    parser.add_argument("--journal", required=True, type=Path)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--label", required=True)
    parser.add_argument("--expect", choices=("prefetch", "cutoff", "full"), required=True)
    args = parser.parse_args()
    args.artifacts.mkdir(parents=True, exist_ok=True)
    ready = json.loads(args.ready.read_text())

    session = dbus.SessionBus()
    address = str(session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus"))
    bus = dbus.bus.BusConnection(address)
    admin = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
                           "org.freedesktop.DBus")
    registry = dbus.Interface(bus.get_object("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root"), ACC)
    matches = [(str(owner), str(path)) for owner, path in registry.GetChildren()
               if int(admin.GetConnectionUnixProcessID(owner)) == ready["pid"]]
    assert matches == [(ready["owner"], ready["application"])], matches
    assert int(admin.GetConnectionUnixProcessID(ready["owner"])) == ready["pid"]

    cache_path = "/org/a11y/atspi/cache"
    raw_cache = {"owner": ready["owner"], "path": cache_path}
    try:
        cache = dbus.Interface(bus.get_object(ready["owner"], cache_path, introspect=False),
                               "org.a11y.atspi.Cache")
        cache.GetItems(timeout=2)
        raise AssertionError("cache unexpectedly succeeded")
    except dbus.DBusException as error:
        raw_cache["errorName"] = error.get_dbus_name()
        raw_cache["message"] = str(error)
    assert raw_cache["errorName"] in {"org.freedesktop.DBus.Error.UnknownMethod",
                                      "org.freedesktop.DBus.Error.UnknownInterface",
                                      "org.freedesktop.DBus.Error.UnknownObject"}, raw_cache
    (args.artifacts / f"{args.label}-raw-cache.json").write_text(json.dumps(raw_cache, indent=2) + "\n")

    before = args.journal.stat().st_size
    depth = 64 if args.expect == "full" else 1
    started = time.monotonic()
    helper_pid, response = request(args.helper, ready["pid"], depth,
                                   args.artifacts / f"{args.label}-native.json")
    elapsed_ms = round((time.monotonic() - started) * 1000)
    time.sleep(.1)
    with args.journal.open() as stream:
        stream.seek(before)
        calls = [json.loads(line) for line in stream if line.strip()]
    own = [call for call in calls if call["senderPid"] == helper_pid]
    assert own, (helper_pid, calls)
    paths = [call["path"] for call in own]
    late = "/org/a11y/atspi/accessible/late_sibling"
    grandchild = "/org/a11y/atspi/accessible/grandchild"
    if args.expect == "prefetch":
        assert late in paths, paths
        if ready["lateMode"] == "error":
            assert not response["ok"] and response["error"]["code"] == "no-accessibility-tree", response
            assert any(call["outcome"] == "org.a11y.atspi.Error.NotAvailable" for call in own), own
        elif ready["lateMode"] == "slow":
            assert elapsed_ms >= 600, elapsed_ms
    elif args.expect == "cutoff":
        assert response == {"ok": False, "error": response["error"]}, response
        assert response["error"]["code"] == "limit-reached", response
        assert response["error"]["message"] == "maximumDepth 1 reached", response
        captured = nodes(response)
        assert len(captured) == 2, captured
        assert [node["id"] for node in captured] == ["qa-window", "first-group"], captured
        assert captured[0]["parentKey"] is None and captured[1]["parentKey"] == captured[0]["key"], captured
        assert late not in paths and grandchild not in paths, paths
    else:
        assert response["ok"], response
        captured = nodes(response)
        assert [node["id"] for node in captured] == ["qa-window", "first-group", "grandchild", "late-sibling"], captured
        assert late in paths and grandchild in paths
        for node in captured:
            assert node["name"] and node["key"] and node["bounds"]["width"] > 0, node
    receipt = {"label": args.label, "expect": args.expect, "provider": ready,
               "registryMatch": matches, "rawCache": raw_cache, "helperPid": helper_pid,
               "elapsedMs": elapsed_ms, "response": response, "orderedProviderCalls": own}
    path = args.artifacts / f"{args.label}-receipt.json"
    path.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()

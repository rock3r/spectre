#!/usr/bin/python3
"""Acceptance assertions for the real, cache-less AT-SPI fixture."""

import argparse
import json
from pathlib import Path
import statistics
import subprocess
import time

import dbus

ACC = "org.a11y.atspi.Accessible"
WINDOW = "/org/a11y/atspi/accessible/window"
LATE = "/org/a11y/atspi/accessible/late_sibling"
GRANDCHILD = "/org/a11y/atspi/accessible/grandchild"


def request(helper, pid, depth, maximum, output, timeout=12):
    body = json.dumps({"target": {"type": "process-id", "value": pid},
                       "maximumDepth": depth, "maximumNodeCount": maximum,
                       "timeoutMs": 5000, "allowAccessibilityEnable": True})
    stderr = output.with_suffix(".stderr")
    started = time.monotonic()
    with output.open("w") as stdout, stderr.open("w") as errors:
        process = subprocess.Popen([str(helper), "accessibility-snapshot"], stdin=subprocess.PIPE,
                                   stdout=stdout, stderr=errors, text=True)
        try:
            process.communicate(body, timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise AssertionError(f"helper exceeded {timeout}s: {helper}")
    elapsed_ms = round((time.monotonic() - started) * 1000)
    assert process.returncode == 0, (process.returncode, stderr.read_text())
    return process.pid, elapsed_ms, json.loads(output.read_text())


def nodes(response):
    return (response["snapshot"] if response["ok"] else response["error"]["partialSnapshot"])["nodes"]


def journal_since(journal, before, helper_pid):
    time.sleep(.1)
    with journal.open() as stream:
        stream.seek(before)
        calls = [json.loads(line) for line in stream if line.strip()]
    own = [call for call in calls if call["senderPid"] == helper_pid]
    assert own, (helper_pid, calls)
    return own


def run_request(args, helper, tag, depth, maximum):
    before = args.journal.stat().st_size
    helper_pid, elapsed_ms, response = request(
        helper, args.ready_data["pid"], depth, maximum,
        args.artifacts / f"{args.label}-{tag}-native.json")
    own = journal_since(args.journal, before, helper_pid)
    return {"tag": tag, "helperPid": helper_pid, "elapsedMs": elapsed_ms,
            "response": response, "orderedProviderCalls": own}


def assert_three_node_partial(response):
    assert not response["ok"], response
    error = response["error"]
    assert error["code"] == "limit-reached", response
    # This spelling and casing is the serialized product contract.
    assert error["message"] == "maximumNodeCount 3 reached", response
    captured = nodes(response)
    assert [node["id"] for node in captured] == ["qa-window", "first-group", "grandchild"], captured
    assert captured[0]["parentKey"] is None, captured
    assert captured[1]["parentKey"] == captured[0]["key"], captured
    assert captured[2]["parentKey"] == captured[1]["key"], captured


def count_prefetch_observation(response, allow_native_failure=False):
    if not response["ok"] and response["error"]["code"] == "no-accessibility-tree":
        assert allow_native_failure, response
        assert response["error"]["message"] == (
            "failed to read an AT-SPI child: AT-SPI role reads failed"), response
        return "native-role-read-failure"
    assert_three_node_partial(response)
    return "typed-three-node-partial"


def canonical_snapshot(response):
    assert response["ok"], response
    captured = nodes(response)
    assert len(captured) == 4, captured
    assert [node["id"] for node in captured] == [
        "qa-window", "first-group", "grandchild", "late-sibling"], captured
    # Preserve every published node field rather than silently selecting a convenient subset.
    # Sorting mapping keys removes JSON object-order noise only; node order is topology.
    return json.loads(json.dumps(captured, sort_keys=True))


def verify_comparison(args):
    assert args.compare_helper is not None
    baseline, candidate = [], []
    for name, helper, destination in (("baseline", args.helper, baseline),
                                      ("candidate", args.compare_helper, candidate)):
        for index in range(1, 6):
            result = run_request(args, helper, f"{name}-{index}", 64, 100)
            result["canonicalNodes"] = canonical_snapshot(result["response"])
            destination.append(result)
    expected = baseline[0]["canonicalNodes"]
    discrepancy = None
    for result in baseline + candidate:
        if result["canonicalNodes"] != expected:
            discrepancy = {"reference": expected, "differentTag": result["tag"],
                           "different": result["canonicalNodes"]}
            break
    comparison = {
        "runsPerHelper": 5,
        "sameProviderPid": args.ready_data["pid"],
        "nodeFieldNames": sorted(expected[0]),
        "baselineElapsedMs": [result["elapsedMs"] for result in baseline],
        "candidateElapsedMs": [result["elapsedMs"] for result in candidate],
        "baselineMedianMs": statistics.median(result["elapsedMs"] for result in baseline),
        "candidateMedianMs": statistics.median(result["elapsedMs"] for result in candidate),
        "discrepancy": discrepancy,
    }
    (args.artifacts / f"{args.label}-comparison.json").write_text(
        json.dumps(comparison, indent=2) + "\n")
    assert discrepancy is None, discrepancy
    return baseline + candidate, comparison


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", required=True, type=Path)
    parser.add_argument("--compare-helper", type=Path)
    parser.add_argument("--ready", required=True, type=Path)
    parser.add_argument("--journal", required=True, type=Path)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--label", required=True)
    parser.add_argument("--expect", choices=("prefetch", "cutoff", "full", "count-prefetch",
                                             "count-cutoff", "comparison"), required=True)
    args = parser.parse_args()
    args.artifacts.mkdir(parents=True, exist_ok=True)
    args.ready_data = json.loads(args.ready.read_text())
    ready = args.ready_data

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

    comparison = None
    if args.expect == "comparison":
        requests, comparison = verify_comparison(args)
    else:
        depth = 64 if args.expect in {"full", "count-prefetch", "count-cutoff"} else 1
        maximum = 3 if args.expect in {"count-prefetch", "count-cutoff"} else 100
        result = run_request(args, args.helper, "request", depth, maximum)
        requests = [result]
        response, own = result["response"], result["orderedProviderCalls"]
        paths = [call["path"] for call in own]
        if args.expect == "prefetch":
            assert LATE in paths, paths
            if ready["lateMode"] == "error":
                assert not response["ok"] and response["error"]["code"] == "no-accessibility-tree", response
                assert any(call["outcome"] == "org.a11y.atspi.Error.NotAvailable" for call in own), own
            elif ready["lateMode"] == "slow":
                assert result["elapsedMs"] >= 600, result["elapsedMs"]
        elif args.expect == "cutoff":
            assert not response["ok"] and response["error"]["code"] == "limit-reached", response
            assert response["error"]["message"] == "maximumDepth 1 reached", response
            captured = nodes(response)
            assert [node["id"] for node in captured] == ["qa-window", "first-group"], captured
            assert captured[0]["parentKey"] is None and captured[1]["parentKey"] == captured[0]["key"], captured
            assert LATE not in paths and GRANDCHILD not in paths, paths
        elif args.expect == "full":
            canonical_snapshot(response)
            assert LATE in paths and GRANDCHILD in paths, paths
        elif args.expect == "count-prefetch":
            # Public fe9 reads the late sibling before descending into the first subtree.
            late_calls = [call for call in own if call["path"] == LATE]
            assert late_calls, own
            if ready["lateMode"] == "error":
                assert not response["ok"] and response["error"]["code"] == "no-accessibility-tree", response
                assert any(call["outcome"] == "org.a11y.atspi.Error.NotAvailable"
                           for call in late_calls), late_calls
                result["countPrefetchObservation"] = "native-late-sibling-failure"
            else:
                result["countPrefetchObservation"] = count_prefetch_observation(
                    response, allow_native_failure=ready["lateMode"] == "slow")
                if ready["lateMode"] == "slow":
                    assert any(call["outcome"] == "return" for call in late_calls), late_calls
                    assert result["elapsedMs"] >= 600, result["elapsedMs"]
        else:
            assert_three_node_partial(response)
            assert any(call["path"] == WINDOW and call["member"] == "GetChildren"
                       for call in own), own
            assert LATE not in paths, paths

    all_calls = [call for result in requests for call in result["orderedProviderCalls"]]
    receipt = {"label": args.label, "expect": args.expect, "provider": ready,
               "registryMatch": matches, "rawCache": raw_cache, "requests": requests,
               "comparison": comparison, "orderedProviderCalls": all_calls}
    if len(requests) == 1:
        receipt.update({key: requests[0][key] for key in ("helperPid", "elapsedMs", "response")})
    path = args.artifacts / f"{args.label}-receipt.json"
    path.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/python3
"""Exercise only the native snapshot helper and retain independently auditable evidence."""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path
import dbus

HEADER = re.compile(
    r"^(method call|method return|error) time=([0-9.]+).*?sender=([^ ]+).*?destination=([^ ]+).*?serial=(\d+)(?: reply_serial=(\d+))?(?: path=([^;]+); interface=([^;]+); member=(\S+))?"
)


def wire_entries(path, owner):
    entries = []
    for line in path.read_text(errors="replace").splitlines() if path.exists() else []:
        m = HEADER.match(line)
        if m and owner in (m[3], m[4]):
            entries.append(
                dict(
                    zip(
                        (
                            "kind",
                            "busTime",
                            "sender",
                            "destination",
                            "serial",
                            "replySerial",
                            "path",
                            "interface",
                            "member",
                        ),
                        m.groups(),
                    )
                )
            )
    return entries


def invoke(helper, body, out):
    raw = json.dumps(body, separators=(",", ":"))
    out.with_suffix(".request.json").write_text(raw + "\n")
    start = time.monotonic_ns()
    p = subprocess.Popen(
        [str(helper), "accessibility-snapshot"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    p.stdin.write(raw)
    p.stdin.close()
    first = p.stdout.readline()
    emitted = time.monotonic_ns()
    stdout = first + p.stdout.read()
    stderr = p.stderr.read()
    p.wait(timeout=15)
    ended = time.monotonic_ns()
    elapsed = (ended - start) // 1_000_000
    out.write_text(stdout)
    out.with_suffix(".stderr").write_text(stderr)
    try:
        response = json.loads(stdout)
    except Exception:
        response = {"unparseable": stdout, "returnCode": p.returncode}
    return {
        "request": body,
        "rawRequest": raw,
        "response": response,
        "stdout": stdout,
        "stderr": stderr,
        "returnCode": p.returncode,
        "jsonEmittedMs": (emitted - start) // 1_000_000,
        "processExitMs": elapsed,
        "processExited": p.poll() is not None,
    }


def snap(r):
    x = r["response"]
    return x.get("snapshot") or x.get("error", {}).get("partialSnapshot")


def ids(r):
    s = snap(r)
    return [] if not s else [n.get("id") for n in s.get("nodes", [])]


def expected_node(window, ident, parent, role, platform, name, bounds):
    return {
        "key": (parent + "/" + ident) if parent else window + "/" + ident,
        "parentKey": parent,
        "id": ident,
        "platformRole": platform,
        "role": role,
        "name": name,
        "states": ["disabled"],
        "value": None,
        "bounds": bounds,
        "onScreen": True,
    }


def expected_window(prefix, index):
    x = 40 if prefix == "alpha" else 420
    title = prefix.title()
    key = f"w{index}"
    root = prefix + "-root"
    group = prefix + "-group"
    interior = prefix + "-interior"
    bounds = {"x": x, "y": 60, "width": 320, "height": 180}
    nodes = [
        expected_node(key, root, None, "viewport", "frame", title, bounds),
        expected_node(
            key,
            group,
            key + "/" + root,
            "comment",
            "grouping",
            title + " group",
            {"x": x + 10, "y": 90, "width": 280, "height": 110},
        ),
        expected_node(
            key,
            interior,
            key + "/" + root + "/" + group,
            "comment",
            "grouping",
            title + " interior",
            {"x": x + 20, "y": 110, "width": 240, "height": 70},
        ),
        expected_node(
            key,
            prefix + "-marker",
            key + "/" + root + "/" + group + "/" + interior,
            "progress-bar",
            "push button",
            prefix + "-marker",
            {"x": x + 30, "y": 130, "width": 120, "height": 30},
        ),
    ]
    return {
        "key": key,
        "title": title,
        "bounds": bounds,
        "boundsSpace": "screen",
    }, nodes


def exact_snapshot(result, prefixes):
    s = snap(result) or {}
    windows = []
    nodes = []
    for index, prefix in enumerate(prefixes):
        window, descendants = expected_window(prefix, index)
        windows.append(window)
        nodes.extend(descendants)
    return (
        s.get("schemaVersion") == 2
        and s.get("source") == "os-accessibility"
        and isinstance(s.get("capturedAt"), str)
        and s.get("windows") == windows
        and s.get("nodes") == nodes
        and isinstance(s.get("notes"), list)
    )


def expected_parts(prefixes):
    expected_windows, expected_nodes = [], []
    for index, prefix in enumerate(prefixes):
        window, nodes = expected_window(prefix, index)
        expected_windows.append(window)
        expected_nodes.extend(nodes)
    return expected_windows, expected_nodes


def exact_limit(result, count):
    snapshot = snap(result) or {}
    expected_windows, expected_nodes = expected_parts(["alpha", "beta"])
    expected_nodes = expected_nodes[:count]
    keys = {node["key"] for node in expected_nodes}
    expected_emitted_windows = expected_windows[:1] if count == 4 else expected_windows
    return (
        snapshot.get("windows") == expected_emitted_windows
        and snapshot.get("nodes") == expected_nodes
        and all(
            node["parentKey"] is None or node["parentKey"] in keys
            for node in expected_nodes
        )
    )


def topology(result):
    nodes = (snap(result) or {}).get("nodes", [])
    by_key = {node.get("key"): node for node in nodes}
    return [
        {
            "id": node.get("id"),
            "key": node.get("key"),
            "parentKey": node.get("parentKey"),
            "parentId": by_key.get(node.get("parentKey"), {}).get("id"),
        }
        for node in nodes
    ]


def windows(result):
    return [
        {"key": window.get("key"), "title": window.get("title")}
        for window in (snap(result) or {}).get("windows", [])
    ]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--helper", type=Path, required=True)
    p.add_argument("--ready", type=Path, required=True)
    p.add_argument("--journal", type=Path, required=True)
    p.add_argument("--wire", type=Path, required=True)
    p.add_argument("--artifacts", type=Path, required=True)
    p.add_argument("--label", required=True)
    p.add_argument("--candidate", action="store_true")
    p.add_argument("--deadline-acceptance", action="store_true")
    a = p.parse_args()
    ready = json.loads(a.ready.read_text())
    a.artifacts.mkdir(parents=True, exist_ok=True)
    session = dbus.SessionBus()
    address = str(
        session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
            dbus_interface="org.a11y.Bus"
        )
    )
    bus = dbus.bus.BusConnection(address)
    admin = dbus.Interface(
        bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
        "org.freedesktop.DBus",
    )
    registry = dbus.Interface(
        bus.get_object("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root"),
        "org.a11y.atspi.Accessible",
    )
    matches = [
        (str(o), str(path))
        for o, path in registry.GetChildren()
        if int(admin.GetConnectionUnixProcessID(o)) == ready["pid"]
    ]
    cache = {"address": address}
    try:
        dbus.Interface(
            bus.get_object(ready["owner"], "/org/a11y/atspi/cache", introspect=False),
            "org.a11y.atspi.Cache",
        ).GetItems(timeout=2)
        cache["unexpectedSuccess"] = True
    except dbus.DBusException as e:
        cache.update(errorName=e.get_dbus_name(), message=str(e))
    base = {
        "maximumDepth": 64,
        "maximumNodeCount": 100,
        "timeoutMs": 5000,
        "allowAccessibilityEnable": True,
    }
    requests = []

    def run(tag, target, **kw):
        before = a.journal.stat().st_size
        body = dict(base, target=target)
        body.update(kw)
        result = invoke(a.helper, body, a.artifacts / f"{a.label}-{tag}.json")
        time.sleep(0.15)
        with a.journal.open() as journal:
            journal.seek(before)
            result["orderedProviderCalls"] = [
                json.loads(line) for line in journal if line.strip()
            ]
        provider_errors = [
            call
            for call in result["orderedProviderCalls"]
            if call.get("outcome") not in (None, "return")
        ]
        result["providerErrorDetail"] = [
            {"outcome": call.get("outcome"), "detail": call.get("detail")}
            for call in provider_errors
        ]
        result["rawWire"] = wire_entries(a.wire, ready["owner"])
        result["tag"] = tag
        requests.append(result)
        return result

    fault = ready["fault"]

    def target(name):
        return {
            "type": "window-id",
            "value": ready["owner"] + "|" + ready["windows"][name],
        }

    if fault == "none":
        pid = run("pid-both", {"type": "process-id", "value": ready["pid"]})
        alpha = run("alpha-window", target("alpha"))
        beta = run("beta-window", target("beta"))
        process_target = {"type": "process-id", "value": ready["pid"]}
        cap4 = run("pid-shared-cap-4", process_target, maximumNodeCount=4)
        cap5 = run("pid-shared-cap-5", process_target, maximumNodeCount=5)
    elif fault == "error":
        alpha = run("early-error", target("alpha"))
    else:
        alpha = run("slow-timeout", target("alpha"), timeoutMs=650)
    calls = [
        json.loads(line) for line in a.journal.read_text().splitlines() if line.strip()
    ]
    helper_pids = sorted(
        {
            c.get("senderPid")
            for c in calls
            if c.get("senderPid") and c.get("senderPid") != ready["pid"]
        }
    )
    checks = {
        "registrySingleOwner": matches == [(ready["owner"], ready["application"])],
        "ownerPid": int(admin.GetConnectionUnixProcessID(ready["owner"]))
        == ready["pid"],
        "nativeCacheFailure": cache.get("errorName")
        in (
            "org.freedesktop.DBus.Error.UnknownMethod",
            "org.freedesktop.DBus.Error.UnknownInterface",
            "org.freedesktop.DBus.Error.UnknownObject",
        ),
        "rawWireReceipt": all(r["rawWire"] for r in requests),
        "helpersExited": all(r["processExited"] for r in requests),
    }
    if fault == "none":
        alpha_chain = [
            ("alpha-root", "w0/alpha-root", None, None),
            ("alpha-group", "w0/alpha-root/alpha-group", "w0/alpha-root", "alpha-root"),
            (
                "alpha-interior",
                "w0/alpha-root/alpha-group/alpha-interior",
                "w0/alpha-root/alpha-group",
                "alpha-group",
            ),
            (
                "alpha-marker",
                "w0/alpha-root/alpha-group/alpha-interior/alpha-marker",
                "w0/alpha-root/alpha-group/alpha-interior",
                "alpha-interior",
            ),
        ]

        def compact(result):
            return [
                (node["id"], node["key"], node["parentKey"], node["parentId"])
                for node in topology(result)
            ]

        def illegal_beta_reads(result):
            return [
                call
                for call in result["orderedProviderCalls"]
                if call["path"].startswith(ready["windows"]["beta"] + "/group")
            ]

        def typed_boundary(result, count):
            return (
                result["request"]["target"]
                == {"type": "process-id", "value": ready["pid"]}
                and result["request"]["maximumNodeCount"] == count
                and not result["response"].get("ok", True)
                and result["response"].get("error", {}).get("code") == "limit-reached"
            )

        checks.update(
            processExactAllFields=exact_snapshot(pid, ["alpha", "beta"]),
            processWindows=windows(pid)
            == [{"key": "w0", "title": "Alpha"}, {"key": "w1", "title": "Beta"}],
            explicitAlphaExact=exact_snapshot(alpha, ["alpha"]),
            explicitBetaExact=exact_snapshot(beta, ["beta"]),
            cap4PidTypedBoundary=typed_boundary(cap4, 4),
            cap4ExactSnapshot=exact_limit(cap4, 4),
            cap4Windows=windows(cap4) == [{"key": "w0", "title": "Alpha"}],
            cap4RootChildChain=compact(cap4) == alpha_chain,
            cap4NoBetaDescendantReads=not illegal_beta_reads(cap4),
            cap5PidTypedBoundary=typed_boundary(cap5, 5),
            cap5ExactSnapshot=exact_limit(cap5, 5),
            cap5Windows=windows(cap5)
            == [{"key": "w0", "title": "Alpha"}, {"key": "w1", "title": "Beta"}],
            cap5RootChildChain=compact(cap5)
            == alpha_chain + [("beta-root", "w1/beta-root", None, None)],
            cap5NoBetaDescendantReads=not illegal_beta_reads(cap5),
            rootsDiscovered=all(
                any(
                    call["path"] == ready["application"]
                    and call["member"] == "GetChildren"
                    for call in result["orderedProviderCalls"]
                )
                for result in (cap4, cap5)
            ),
        )
    elif fault == "error":
        error = alpha["response"].get("error", {})
        fixture = "fixture ordinary child read rejected before deadline"
        checks.update(
            ordinaryFailure=error.get("code") == "no-accessibility-tree",
            originalMethodContext="AT-SPI GetChildren failed"
            in error.get("message", ""),
            noGenericProtocolMask="protocol" not in error.get("message", "").lower(),
            providerErrorResult=any(
                c.get("outcome") == "org.a11y.atspi.Error.NotAvailable"
                for c in alpha["orderedProviderCalls"]
            ),
            providerDetailRecorded=bool(alpha.get("providerErrorDetail")),
            noPartial="partialSnapshot" not in error
            or error.get("partialSnapshot") is None,
        )
    else:
        error = alpha["response"].get("error", {})
        elapsed = alpha["processExitMs"]
        checks.update(
            typedTimeout=error.get("code") == "timeout",
            meaningfulTypedPartial=len(ids(alpha)) >= 1,
        )
        if a.deadline_acceptance:
            checks.update(
                deadlineExitBound=500 <= elapsed <= 1050,
                jsonExitClose=abs(alpha["jsonEmittedMs"] - elapsed) <= 100,
            )
    receipt = {
        "label": a.label,
        "candidate": a.candidate,
        "deadlineAcceptance": a.deadline_acceptance,
        "provider": ready,
        "atspiAddress": address,
        "registryMatches": matches,
        "rawCache": cache,
        "helperSenderPids": helper_pids,
        "requests": requests,
        "orderedProviderCalls": calls,
        "requestTopology": {
            result["tag"]: {"windows": windows(result), "nodes": topology(result)}
            for result in requests
        },
        "checks": checks,
        "passed": all(checks.values()),
    }
    (a.artifacts / f"{a.label}-receipt.json").write_text(
        json.dumps(receipt, indent=2) + "\n"
    )
    print(json.dumps(receipt))
    if a.candidate and not receipt["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

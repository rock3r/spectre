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
        "key": window + "/" + ident,
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
    return {"key": key, "title": title, "bounds": bounds, "boundsSpace": "screen"}, nodes


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


def exact_cap(result):
    s = snap(result) or {}
    alpha_window, alpha_nodes = expected_window("alpha", 0)
    beta_window, beta_nodes = expected_window("beta", 1)
    return (
        s.get("windows") == [alpha_window, beta_window]
        and s.get("nodes") == alpha_nodes + beta_nodes[:1]
        and s.get("nodes", [])[-1].get("parentKey") is None
    )


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
        bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"), "org.freedesktop.DBus"
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
            result["orderedProviderCalls"] = [json.loads(line) for line in journal if line.strip()]
        result["rawWire"] = wire_entries(a.wire, ready["owner"])
        result["tag"] = tag
        requests.append(result)
        return result

    fault = ready["fault"]

    def target(name):
        return {"type": "window-id", "value": ready["owner"] + "|" + ready["windows"][name]}

    if fault == "none":
        pid = run("pid-both", {"type": "process-id", "value": ready["pid"]})
        alpha = run("alpha-window", target("alpha"))
        beta = run("beta-window", target("beta"))
        cap = run(
            "pid-shared-cap", {"type": "process-id", "value": ready["pid"]}, maximumNodeCount=5
        )
    elif fault == "error":
        alpha = run("early-error", target("alpha"))
    else:
        alpha = run("slow-timeout", target("alpha"), timeoutMs=650)
    calls = [json.loads(line) for line in a.journal.read_text().splitlines() if line.strip()]
    helper_pids = sorted(
        {
            c.get("senderPid")
            for c in calls
            if c.get("senderPid") and c.get("senderPid") != ready["pid"]
        }
    )
    checks = {
        "registrySingleOwner": matches == [(ready["owner"], ready["application"])],
        "ownerPid": int(admin.GetConnectionUnixProcessID(ready["owner"])) == ready["pid"],
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
        cap_calls = cap["orderedProviderCalls"]
        checks.update(
            processExactAllFields=exact_snapshot(pid, ["alpha", "beta"]),
            explicitAlphaExact=exact_snapshot(alpha, ["alpha"]),
            explicitBetaExact=exact_snapshot(beta, ["beta"]),
            sharedCapTyped=(
                not cap["response"].get("ok", True)
                and cap["response"].get("error", {}).get("code") == "limit-reached"
                and ids(cap)
                == ["alpha-root", "alpha-group", "alpha-interior", "alpha-marker", "beta-root"]
                and exact_cap(cap)
            ),
            betaDescendantsUnread=not any(
                c["path"].startswith(ready["windows"]["beta"] + "/group") for c in cap_calls
            ),
            betaRootMetadataRead=any(
                c["path"] == ready["windows"]["beta"]
                and c["member"] in ("GetAttributes", "GetExtents")
                for c in cap_calls
            ),
            rootsDiscovered=any(
                c["path"] == ready["application"] and c["member"] == "GetChildren"
                for c in cap_calls
            ),
        )
    elif fault == "error":
        error = alpha["response"].get("error", {})
        fixture = "fixture ordinary child read rejected before deadline"
        checks.update(
            ordinaryFailure=error.get("code") == "no-accessibility-tree",
            exactNativeContext=error.get("message")
            == "failed to read an AT-SPI child: AT-SPI GetChildren failed",
            fixtureRejectionRecordedSeparately=any(
                c.get("outcome") == "org.a11y.atspi.Error.NotAvailable"
                for c in alpha["orderedProviderCalls"]
            ),
            fixtureMessageNotRequired=fixture not in error.get("message", ""),
            meaningfulTypedPartial=ids(alpha) == ["alpha-root", "alpha-group", "alpha-interior"],
        )
    else:
        error = alpha["response"].get("error", {})
        elapsed = alpha["processExitMs"]
        checks.update(
            typedTimeout=error.get("code") == "timeout", meaningfulTypedPartial=len(ids(alpha)) >= 1
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
        "checks": checks,
        "passed": all(checks.values()),
    }
    (a.artifacts / f"{a.label}-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))
    if a.candidate and not receipt["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

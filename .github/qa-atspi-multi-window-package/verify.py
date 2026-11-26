#!/usr/bin/python3
"""Exercise the shipped native reader, CLI/daemon and MCP stdio without a JVM attach."""

import argparse, json, os, select, subprocess, time, hashlib
from pathlib import Path
import dbus

CONTEXT = "failed to read an AT-SPI child: AT-SPI GetChildren failed"
PROVIDER = "fixture ordinary child read rejected before deadline"


def execute(argv, stdin="", timeout=20, env=None):
    start = time.monotonic_ns()
    p = subprocess.run(
        argv, input=stdin, text=True, capture_output=True, timeout=timeout, env=env
    )
    return {
        "argv": argv,
        "stdin": stdin,
        "stdout": p.stdout,
        "stderr": p.stderr,
        "returnCode": p.returncode,
        "elapsedMs": (time.monotonic_ns() - start) // 1_000_000,
    }


def native(helper, body):
    wire = json.dumps(body, separators=(",", ":"))
    r = execute([str(helper), "accessibility-snapshot"], wire)
    try:
        r["response"] = json.loads(r["stdout"])
    except Exception:
        r["response"] = {"unparseable": r["stdout"]}
    r["request"] = body
    return r


def snapshot(r):
    x = r.get("response", {})
    return x.get("snapshot") or x.get("error", {}).get("partialSnapshot")


def node_ids(r):
    return [n.get("id") for n in (snapshot(r) or {}).get("nodes", [])]


def linked(r):
    nodes = (snapshot(r) or {}).get("nodes", [])
    keys = {n.get("key") for n in nodes}
    return (
        bool(nodes)
        and len(keys) == len(nodes)
        and all(n.get("parentKey") is None or n["parentKey"] in keys for n in nodes)
    )


def topology(r):
    nodes = (snapshot(r) or {}).get("nodes", [])
    by_key = {n.get("key"): n for n in nodes}
    return [
        {
            "id": n.get("id"),
            "key": n.get("key"),
            "parentKey": n.get("parentKey"),
            "parentId": by_key.get(n.get("parentKey"), {}).get("id"),
        }
        for n in nodes
    ]


def windows(r):
    return [
        {"key": w.get("key"), "title": w.get("title")}
        for w in (snapshot(r) or {}).get("windows", [])
    ]


def result_with_error(error):
    return {"response": {"error": error}}


def cli_result(result):
    response = result.get("response", {})
    if response.get("schemaVersion") == 2:
        return {"response": {"snapshot": response}}
    return {"response": response}


def mcp(cli, pid, maximum_node_count, timeout_ms, env):
    argv = [str(cli), "mcp"]
    start = time.monotonic_ns()
    p = subprocess.Popen(
        argv,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        env=env,
    )
    sent = []
    received = []

    def send(message):
        sent.append(message)
        p.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        p.stdin.flush()

    def receive(identifier, context):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if not select.select(
                [p.stdout], [], [], max(0, deadline - time.monotonic())
            )[0]:
                break
            line = p.stdout.readline()
            if not line:
                break
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                received.append({"unparseable": line})
                continue
            received.append(message)
            if message.get("id") == identifier:
                return message
        raise RuntimeError(f"no MCP {context} response id {identifier}")

    try:
        send(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "qa-atspi", "version": "1"},
                },
            }
        )
        initialize = receive(1, "initialize")
        send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        send(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {
                    "name": "accessibility_tree",
                    "arguments": {
                        "pid": pid,
                        "maximum_depth": 64,
                        "maximum_node_count": maximum_node_count,
                        "timeout_ms": timeout_ms,
                    },
                },
            }
        )
        call = receive(2, "tools/call")
    finally:
        if p.stdin:
            p.stdin.close()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
    stderr = p.stderr.read()
    return {
        "argv": argv,
        "messagesSent": sent,
        "messagesReceived": received,
        "initialize": initialize,
        "toolCall": call,
        "stdout": "\n".join(json.dumps(x, separators=(",", ":")) for x in received)
        + "\n",
        "stderr": stderr,
        "returnCode": p.returncode,
        "elapsedMs": (time.monotonic_ns() - start) // 1_000_000,
    }


def mcp_error(result):
    call = result.get("toolCall", {}).get("result", {})
    texts = [
        x.get("text", "") for x in call.get("content", []) if x.get("type") == "text"
    ]
    for raw in texts:
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            pass
    return {"unparseable": texts}


def drain_provider(ready, timeout=20):
    start = time.monotonic_ns()
    session = dbus.SessionBus()
    address = str(
        session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
            dbus_interface="org.a11y.Bus"
        )
    )
    bus = dbus.bus.BusConnection(address)
    application = dbus.Interface(
        bus.get_object(ready["owner"], ready["application"], introspect=False),
        "org.a11y.atspi.Accessible",
    )
    value = application.GetApplication(timeout=timeout)
    return {
        "method": "org.a11y.atspi.Accessible.GetApplication",
        "result": [str(value[0]), str(value[1])],
        "elapsedMs": (time.monotonic_ns() - start) // 1_000_000,
    }


def daemon_evidence(socket_dir):
    sockets = []
    for path in socket_dir.rglob("*") if socket_dir.exists() else []:
        if not path.is_socket():
            continue
        probe = execute(["fuser", str(path)])
        pids = [
            int(x)
            for x in (probe["stdout"] + " " + probe["stderr"]).split()
            if x.isdigit()
        ]
        owners = []
        for pid in pids:
            proc = Path(f"/proc/{pid}")
            try:
                owners.append(
                    {
                        "pid": pid,
                        "exe": str((proc / "exe").resolve()),
                        "argv": (proc / "cmdline")
                        .read_bytes()
                        .replace(b"\0", b" ")
                        .decode(errors="replace")
                        .strip(),
                    }
                )
            except OSError as error:
                owners.append({"pid": pid, "error": repr(error)})
        sockets.append(
            {
                "path": str(path),
                "mode": oct(path.stat().st_mode & 0o777),
                "fuser": probe,
                "owners": owners,
            }
        )
    return sockets


def sha256(path):
    h = hashlib.sha256()
    h.update(Path(path).read_bytes())
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--helper", type=Path, required=True)
    ap.add_argument("--cli", type=Path, required=True)
    ap.add_argument("--ready", type=Path, required=True)
    ap.add_argument("--journal", type=Path, required=True)
    ap.add_argument("--artifacts", type=Path, required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--candidate", action="store_true")
    a = ap.parse_args()
    ready = json.loads(a.ready.read_text())
    accessibility_status = execute(
        [
            "gdbus",
            "call",
            "--session",
            "--dest",
            "org.a11y.Bus",
            "--object-path",
            "/org/a11y/bus",
            "--method",
            "org.freedesktop.DBus.Properties.Get",
            "org.a11y.Status",
            "IsEnabled",
        ]
    )
    a.artifacts.mkdir(parents=True, exist_ok=True)
    drains = []
    base = {
        "maximumDepth": 64,
        "maximumNodeCount": 100,
        "timeoutMs": 5000,
        "allowAccessibilityEnable": True,
    }

    def call(tag, target, **changes):
        drains.append({"before": tag, "receipt": drain_provider(ready)})
        request = dict(base, target=target)
        request.update(changes)
        before = a.journal.stat().st_size
        result = native(a.helper, request)
        time.sleep(0.08)
        with a.journal.open() as f:
            f.seek(before)
            result["providerCalls"] = [json.loads(x) for x in f if x.strip()]
        (a.artifacts / f"{a.label}-{tag}-native.json").write_text(
            json.dumps(result, indent=2) + "\n"
        )
        return result

    window = lambda name: {
        "type": "window-id",
        "value": ready["owner"] + "|" + ready["windows"][name],
    }
    fault = ready["fault"]
    requests = {}
    if fault == "none":
        requests["pid"] = call("pid", {"type": "process-id", "value": ready["pid"]})
        requests["alpha"] = call("alpha", window("alpha"))
        requests["beta"] = call("beta", window("beta"))
        requests["cap4"] = call(
            "cap4", {"type": "process-id", "value": ready["pid"]}, maximumNodeCount=4
        )
        requests["cap"] = call(
            "cap5", {"type": "process-id", "value": ready["pid"]}, maximumNodeCount=5
        )
    elif fault == "error":
        requests["error"] = call("error", window("alpha"))
    else:
        requests["timeout"] = call("timeout", window("alpha"), timeoutMs=650)
    env = os.environ.copy()
    socket_dir = Path("/tmp") / f"spqa-{os.getpid()}-{time.monotonic_ns() % 1000000}"
    socket_dir.mkdir(mode=0o700, exist_ok=False)
    os.chmod(socket_dir, 0o700)
    env.update(
        HOME=str(a.artifacts / f"{a.label}-home"),
        XDG_RUNTIME_DIR=str(a.artifacts / f"{a.label}-runtime"),
        SPECTRE_DAEMON_SOCKET_DIR=str(socket_dir),
    )
    Path(env["HOME"]).mkdir(exist_ok=True)
    Path(env["XDG_RUNTIME_DIR"]).mkdir(mode=0o700, exist_ok=True)
    public_timeout = 650 if fault == "slow" else 5000
    public_cap = 5 if fault == "none" else 100
    drains.append({"before": "public-cli", "receipt": drain_provider(ready)})
    cli_args = [
        str(a.cli),
        "a11y",
        "tree",
        "--pid",
        str(ready["pid"]),
        "--maximum-depth",
        "64",
        "--maximum-node-count",
        str(public_cap),
        "--timeout-ms",
        str(public_timeout),
    ]
    cli = execute(cli_args, timeout=30, env=env)
    try:
        cli["response"] = json.loads(cli["stdout"])
    except json.JSONDecodeError:
        cli["response"] = {"unparseable": cli["stdout"]}
    drains.append({"before": "public-mcp", "receipt": drain_provider(ready)})
    mcp_result = mcp(a.cli, ready["pid"], public_cap, public_timeout, env)
    version = execute([str(a.cli), "version", "--json"], env=env)
    daemon = daemon_evidence(socket_dir)
    packageIdentity = {
        "cliSha256": sha256(a.cli),
        "helperSha256": sha256(a.helper),
        "socketDirectory": str(socket_dir),
        "socketDirectoryMode": oct(socket_dir.stat().st_mode & 0o777),
        "daemonSockets": daemon,
    }
    (a.artifacts / f"{a.label}-cli.json").write_text(json.dumps(cli, indent=2) + "\n")
    (a.artifacts / f"{a.label}-mcp.json").write_text(
        json.dumps(mcp_result, indent=2) + "\n"
    )
    session = dbus.SessionBus()
    addr = str(
        session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
            dbus_interface="org.a11y.Bus"
        )
    )
    bus = dbus.bus.BusConnection(addr)
    admin = dbus.Interface(
        bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
        "org.freedesktop.DBus",
    )
    all_calls = [json.loads(x) for x in a.journal.read_text().splitlines() if x.strip()]
    helper_pids = sorted(
        {
            x["senderPid"]
            for x in all_calls
            if x.get("senderPid") not in (None, ready["pid"])
        }
    )
    xprops = [
        execute(["xprop", "-id", hex(x), "_NET_WM_PID", "WM_NAME"])
        for x in ready["xids"]
    ]
    checks = {
        "accessibilityEnabled": accessibility_status["returnCode"] == 0
        and "true" in accessibility_status["stdout"].lower(),
        "realOwnedWindowIds": all(
            x["returnCode"] == 0 and f"= {ready['pid']}" in x["stdout"] for x in xprops
        ),
        "distinctXids": len({x["xid"] for x in ready["x11"]}) == 2,
        "providerOwnerPid": int(admin.GetConnectionUnixProcessID(ready["owner"]))
        == ready["pid"],
        "helperPidObserved": bool(helper_pids),
        "packagedVersion": version["returnCode"] == 0,
        "privateSocketDirectory": socket_dir.parent == Path("/tmp")
        and (socket_dir.stat().st_mode & 0o777) == 0o700,
        "packageDaemonObserved": any(x["owners"] for x in daemon),
        "drainBarriers": len(drains) == len(requests) + 2
        and all(x["receipt"]["result"][1] == ready["application"] for x in drains),
    }
    if fault == "none":
        pid, alpha, beta, cap4, cap = (
            requests[x] for x in ("pid", "alpha", "beta", "cap4", "cap")
        )
        expected = [
            "alpha-root",
            "alpha-group",
            "alpha-interior",
            "alpha-marker",
            "beta-root",
            "beta-group",
            "beta-interior",
            "beta-marker",
        ]
        alpha_top = [
            ("alpha-root", None),
            ("alpha-group", "alpha-root"),
            ("alpha-interior", "alpha-group"),
            ("alpha-marker", "alpha-interior"),
        ]
        alpha_exact = [
            ("alpha-root", "w0/alpha-root", None),
            ("alpha-group", "w0/alpha-root/alpha-group", "w0/alpha-root"),
            (
                "alpha-interior",
                "w0/alpha-root/alpha-group/alpha-interior",
                "w0/alpha-root/alpha-group",
            ),
            (
                "alpha-marker",
                "w0/alpha-root/alpha-group/alpha-interior/alpha-marker",
                "w0/alpha-root/alpha-group/alpha-interior",
            ),
        ]
        beta_root = ("beta-root", "w1/beta-root", None)
        exact = lambda result: [
            (node["id"], node["key"], node["parentKey"]) for node in topology(result)
        ]
        me = mcp_error(mcp_result)
        mcp_partial = result_with_error(me)
        cli_partial = cli_result(cli)
        checks.update(
            pidBothMarkers=node_ids(pid) == expected,
            pidTwoWindows=windows(pid)
            == [{"key": "w0", "title": "Alpha"}, {"key": "w1", "title": "Beta"}],
            pidExactKeys=exact(pid)
            == alpha_exact
            + [
                ("beta-root", "w1/beta-root", None),
                ("beta-group", "w1/beta-root/beta-group", "w1/beta-root"),
                (
                    "beta-interior",
                    "w1/beta-root/beta-group/beta-interior",
                    "w1/beta-root/beta-group",
                ),
                (
                    "beta-marker",
                    "w1/beta-root/beta-group/beta-interior/beta-marker",
                    "w1/beta-root/beta-group/beta-interior",
                ),
            ],
            alphaIsolated=node_ids(alpha) == expected[:4],
            betaIsolated=node_ids(beta) == expected[4:],
            betaRootFirst=node_ids(beta)[:1] == ["beta-root"],
            allParentsLinked=all(linked(x) for x in (pid, alpha, beta, cap4, cap)),
            cap4Typed=cap4["response"].get("error", {}).get("code") == "limit-reached",
            cap4AlphaTopology=[(x["id"], x["parentId"]) for x in topology(cap4)]
            == alpha_top,
            cap4ExactKeys=exact(cap4) == alpha_exact,
            cap4DiscoveryWindows=windows(cap4) == [{"key": "w0", "title": "Alpha"}],
            cap4NoBetaData=not any(x["id"].startswith("beta-") for x in topology(cap4)),
            cap4NoBetaTraversal=not any(
                x["path"].startswith(ready["windows"]["beta"] + "/group")
                for x in cap4["providerCalls"]
            ),
            globalCap=cap["response"].get("error", {}).get("code") == "limit-reached",
            cap5BetaRoot=[(x["id"], x["parentId"]) for x in topology(cap)]
            == alpha_top + [("beta-root", None)],
            cap5ExactKeys=exact(cap) == alpha_exact + [beta_root],
            cap5Windows=windows(cap)
            == [{"key": "w0", "title": "Alpha"}, {"key": "w1", "title": "Beta"}],
            cap5NoMixedMarkers=all(
                (x["key"].startswith("w0/") and not x["id"].startswith("beta-"))
                or (x["key"].startswith("w1/") and x["id"] == "beta-root")
                for x in topology(cap)
            ),
            laterDescendantsUntouched=not any(
                x["path"].startswith(ready["windows"]["beta"] + "/group")
                for x in cap["providerCalls"]
            ),
            cliLimit=cli["returnCode"] != 0
            and "limitreached" in cli["stderr"].lower().replace("-", "")
            and all(
                x in cli["stdout"]
                for x in (
                    "alpha-root",
                    "alpha-group",
                    "alpha-interior",
                    "alpha-marker",
                    "beta-root",
                )
            ),
            cliExactKeys=exact(cli_partial) == alpha_exact + [beta_root],
            mcpLimit=me.get("code") == "limit-reached"
            and mcp_result.get("toolCall", {}).get("result", {}).get("isError") == True
            and all(
                x in json.dumps(me.get("partialSnapshot", {}))
                for x in (
                    "alpha-root",
                    "alpha-group",
                    "alpha-interior",
                    "alpha-marker",
                    "beta-root",
                )
            ),
            mcpExactKeys=exact(mcp_partial) == alpha_exact + [beta_root],
        )
    elif fault == "error":
        error = requests["error"]["response"].get("error", {})
        me = mcp_error(mcp_result)
        generic = "Accessibility helper failed"
        checks.update(
            nativeTyped=error.get("code") == "no-accessibility-tree",
            originalContext=CONTEXT in error.get("message", ""),
            providerDetailSeparate=any(
                x.get("outcome") == "org.a11y.atspi.Error.NotAvailable"
                and x.get("detail") == PROVIDER
                for x in requests["error"]["providerCalls"]
            ),
            noForbiddenPartial=(
                "partialSnapshot" not in error or error.get("partialSnapshot") is None
            ),
            cliContext=CONTEXT in cli["stderr"] and generic not in cli["stderr"],
            cliNoPartial=(
                "partialSnapshot" not in cli.get("response", {}).get("error", {})
                or cli.get("response", {}).get("error", {}).get("partialSnapshot")
                is None
            ),
            mcpContext=CONTEXT in me.get("message", "")
            and me.get("code") == "no-accessibility-tree"
            and generic not in json.dumps(me)
            and mcp_result.get("toolCall", {}).get("result", {}).get("isError") == True,
            mcpNoPartial=(
                "partialSnapshot" not in me or me.get("partialSnapshot") is None
            ),
        )
    else:
        error = requests["timeout"]["response"].get("error", {})
        me = mcp_error(mcp_result)
        checks.update(
            realTimeout=error.get("code") == "timeout"
            and 500 <= requests["timeout"]["elapsedMs"] <= 1050,
            meaningfulLinkedPartial=len(node_ids(requests["timeout"])) >= 1
            and linked(requests["timeout"]),
            cliTimeout=cli["returnCode"] != 0
            and "timeout" in cli["stderr"].lower()
            and cli["elapsedMs"] >= 500,
            mcpTimeout=me.get("code") == "timeout"
            and mcp_result.get("toolCall", {}).get("result", {}).get("isError") == True
            and mcp_result["elapsedMs"] >= 500,
            cliPartial=cli["stdout"].lstrip().startswith("{")
            and all(
                x in cli["stdout"]
                for x in ("alpha-root", "alpha-group", "alpha-interior")
            ),
            mcpPartial=all(
                x in json.dumps(me.get("partialSnapshot", {}))
                for x in ("alpha-root", "alpha-group", "alpha-interior")
            ),
        )
    receipt = {
        "schemaVersion": 2,
        "label": a.label,
        "candidate": a.candidate,
        "provider": ready,
        "accessibilityStatus": accessibility_status,
        "xprop": xprops,
        "atspiAddress": addr,
        "helperSenderPids": helper_pids,
        "version": version,
        "packageIdentity": packageIdentity,
        "drainBarriers": drains,
        "requests": requests,
        "requestTopology": {
            k: {"windows": windows(v), "nodes": topology(v)}
            for k, v in requests.items()
        },
        "publicDiagnostics": {
            "nativeError": next(
                (
                    v["response"].get("error")
                    for v in requests.values()
                    if v["response"].get("error")
                ),
                None,
            ),
            "cliStderr": cli["stderr"],
            "mcpError": mcp_error(mcp_result),
        },
        "cli": cli,
        "mcp": mcp_result,
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

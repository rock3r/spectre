#!/usr/bin/python3
"""Black-box consent oracle for the packaged CLI/daemon and MCP stdio."""
import argparse
import json
import os
import re
import select
import subprocess
import time
from pathlib import Path

DISABLED = "accessibility-disabled"
TOOL = "accessibility_tree"
NOTE = "enabled Linux accessibility for this snapshot; restored the previous setting afterwards"
REQUEST_MS = 5000


def compact_process(argv, **kwargs):
    started = time.monotonic_ns()
    try:
        result = subprocess.run(argv, text=True, capture_output=True, **kwargs)
        return {"argv": list(map(str, argv)), "returnCode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr,
                "elapsedMs": (time.monotonic_ns() - started) // 1_000_000}
    except BaseException as error:
        return {"argv": list(map(str, argv)), "exception": repr(error),
                "elapsedMs": (time.monotonic_ns() - started) // 1_000_000}


def accessibility_flag():
    argv = ["gdbus", "call", "--session", "--dest", "org.a11y.Bus", "--object-path",
            "/org/a11y/bus", "--method", "org.freedesktop.DBus.Properties.Get",
            "org.a11y.Status", "IsEnabled"]
    result = compact_process(argv, timeout=2)
    if result.get("returnCode") != 0:
        raise AssertionError(f"IsEnabled command failed: {result!r}")
    match = re.fullmatch(r"\s*\(\s*<(true|false)>\s*,?\s*\)\s*", result["stdout"], re.I)
    if match is None:
        raise AssertionError(f"IsEnabled returned unknown/nonboolean output: {result!r}")
    return {"monotonicNs": time.monotonic_ns(), "enabled": match.group(1).lower() == "true",
            "command": result}


def start_monitor():
    # Properties.Set does not have to emit PropertiesChanged. Eavesdrop on the calls themselves.
    rule = "type='method_call',destination='org.a11y.Bus',interface='org.freedesktop.DBus.Properties',member='Set',path='/org/a11y/bus'"
    argv = ["stdbuf", "-oL", "dbus-monitor", "--session", rule]
    process = subprocess.Popen(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               bufsize=1)
    ready, _, _ = select.select([process.stdout], [], [], 2)
    if not ready:
        process.kill()
        raise AssertionError("per-call DBus monitor did not become ready")
    return process, process.stdout.readline()


def stop_monitor(process, prefix):
    process.terminate()
    try:
        stdout, stderr = process.communicate(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        stdout, stderr = process.communicate()
    return {"stdout": prefix + stdout, "stderr": stderr, "returnCode": process.returncode}


def run_call(argv, env, timeout=12):
    before = accessibility_flag()
    monitor, prefix = start_monitor()
    result = compact_process(argv, env=env, timeout=timeout)
    result.update(flagBefore=before, flagAfter=accessibility_flag(),
                  dbusMonitor=stop_monitor(monitor, prefix))
    return result


def nested(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from nested(child)
    elif isinstance(value, list):
        for child in value:
            yield from nested(child)


def json_documents(text):
    documents = []
    for line in text.splitlines():
        try:
            documents.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    if "\n" in text.strip():
        try:
            documents.append(json.loads(text))
        except json.JSONDecodeError:
            pass
    return documents


def set_values(monitor_text):
    blocks = re.split(r"(?=method call time=)", monitor_text, flags=re.I)
    values = []
    for block in blocks:
        if re.search(r'string\s+"IsEnabled"', block, re.I):
            match = re.search(r"variant\s+boolean\s+(true|false)", block, re.I)
            if match:
                values.append(match.group(1).lower() == "true")
    return values


def assert_transition(record, expected, label):
    if record["flagBefore"]["enabled"] or record["flagAfter"]["enabled"]:
        raise AssertionError(f"{label}: IsEnabled baseline was not false before and after")
    values = set_values(record["dbusMonitor"]["stdout"])
    if expected and values != [True, False]:
        raise AssertionError(f"{label}: expected exact Set true -> Set false calls, got {values!r}")
    if not expected and values:
        raise AssertionError(f"{label}: refusal called Set(IsEnabled): {values!r}")


def assert_disabled_payload(payload, label):
    if not isinstance(payload, dict) or payload.get("code") != DISABLED:
        raise AssertionError(f"{label}: expected exact code {DISABLED!r}, got {payload!r}")
    if payload.get("partialSnapshot") is not None or "tree" in payload or any(
            isinstance(x, dict) and isinstance(x.get("nodes"), list) for x in nested(payload)):
        raise AssertionError(f"{label}: refusal leaked partialSnapshot/tree")


def assert_cli_disabled(result, label):
    if result.get("returnCode") != 5:
        raise AssertionError(f"{label}: expected exit 5: {result!r}")
    if result.get("stdout"):
        raise AssertionError(f"{label}: refusal unexpectedly wrote stdout: {result['stdout']!r}")
    stderr = result.get("stderr", "")
    if re.fullmatch(r"Spectre daemon error: AccessibilityDisabled: [^\n]+\n(?:Hint: [^\n]+\n)?", stderr) is None:
        raise AssertionError(f"{label}: unexpected daemon refusal stderr: {stderr!r}")
    assert_transition(result, False, label)


def find_tree(value):
    candidates = [x for x in nested(value) if isinstance(x, dict) and isinstance(x.get("nodes"), list)
                  and isinstance(x.get("windows"), list)]
    if len(candidates) != 1:
        raise AssertionError(f"expected one encoded snapshot object, got {candidates!r}")
    return candidates[0]


def assert_tree(tree, requested_pid, title, wanted, forbidden_title, forbidden_marker, label):
    required = {"schemaVersion", "source", "capturedAt", "windows", "nodes", "notes"}
    if not required <= tree.keys() or tree.get("schemaVersion") != 2 or tree.get("source") != "os-accessibility":
        raise AssertionError(f"{label}: not a canonical schemaVersion 2 snapshot")
    if not isinstance(tree.get("capturedAt"), str) or not tree["capturedAt"]:
        raise AssertionError(f"{label}: capturedAt absent")
    windows, nodes = tree["windows"], tree["nodes"]
    target_windows = [window for window in windows if window.get("title") == title]
    if not target_windows or any(window.get("title") == forbidden_title for window in windows):
        raise AssertionError(f"{label}: target window absent or decoy window leaked")
    buttons = [node for node in nodes if node.get("role") == "button" and node.get("name") == wanted]
    if not buttons or any(node.get("name") == forbidden_marker for node in nodes):
        raise AssertionError(f"{label}: target button absent or decoy marker leaked")
    keys = {node.get("key") for node in nodes if isinstance(node.get("key"), str) and node["key"]}
    window_keys = {window.get("key") for window in target_windows}
    if any(button.get("parentKey") not in keys for button in buttons):
        raise AssertionError(f"{label}: target button is not linked to a returned parent node")
    if tree.get("notes") != [NOTE]:
        raise AssertionError(f"{label}: native restoration note mismatch: {tree.get('notes')!r}")
    return {"requestedPid": requested_pid, "windowKeys": sorted(window_keys),
            "buttonKeys": [button["key"] for button in buttons], "notes": tree["notes"]}


class Mcp:
    def __init__(self, cli, env):
        self.argv = [str(cli), "mcp"]
        self.p = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True, bufsize=1, env=env)
        self.sent, self.received = [], []

    def send(self, message):
        self.sent.append(message)
        self.p.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        self.p.stdin.flush()

    def call(self, identifier, method, params, monitor_call=False):
        before = accessibility_flag() if monitor_call else None
        monitor, prefix = start_monitor() if monitor_call else (None, None)
        self.send({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            ready, _, _ = select.select([self.p.stdout], [], [], max(0, deadline - time.monotonic()))
            if not ready:
                break
            line = self.p.stdout.readline()
            if not line:
                break
            response = json.loads(line)
            self.received.append(response)
            if response.get("id") == identifier:
                if monitor_call:
                    response["_qa"] = {"flagBefore": before, "flagAfter": accessibility_flag(),
                                       "dbusMonitor": stop_monitor(monitor, prefix)}
                return response
        if monitor_call:
            stop_monitor(monitor, prefix)
        raise AssertionError(f"MCP response {identifier} missing within request budget")

    def notify_initialized(self):
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

    def close(self):
        self.p.stdin.close()
        try:
            self.p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.p.kill()
            self.p.wait()
        return {"argv": self.argv, "messagesSent": self.sent, "messagesReceived": self.received,
                "stderr": self.p.stderr.read(), "returnCode": self.p.returncode}


def mcp_payload(response, label):
    if "error" in response:
        raise AssertionError(f"{label}: JSON-RPC error: {response['error']!r}")
    contents = response.get("result", {}).get("content", [])
    texts = [item["text"] for item in contents if item.get("type") == "text"]
    if len(texts) != 1:
        raise AssertionError(f"{label}: expected one MCP text content: {contents!r}")
    return json.loads(texts[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cli", type=Path, required=True)
    ap.add_argument("--target-ready", type=Path, required=True)
    ap.add_argument("--decoy-ready", type=Path, required=True)
    ap.add_argument("--artifacts", type=Path, required=True)
    args = ap.parse_args()
    env = os.environ.copy()
    target = json.loads(args.target_ready.read_text())
    decoy = json.loads(args.decoy_ready.read_text())
    evidence = {"schemaVersion": 2, "fixtures": {"target": target, "decoy": decoy}, "calls": {}}
    output = args.artifacts / "verification.json"

    def flush():
        temporary = output.with_suffix(".tmp")
        temporary.write_text(json.dumps(evidence, separators=(",", ":")) + "\n")
        temporary.replace(output)

    def record(name, value):
        evidence["calls"][name] = value
        flush()
        return value

    try:
        help_result = record("cliHelp", compact_process([str(args.cli), "a11y", "tree", "--help"], env=env, timeout=5))
        if help_result.get("returnCode") != 0:
            raise AssertionError(f"CLI help failed: {help_result!r}")
        help_text = help_result["stdout"] + help_result["stderr"]
        if "--allow-accessibility-enable" not in help_text:
            raise AssertionError("CLI does not advertise the presence-only opt-in")
        if "--no-allow-accessibility-enable" in help_text or re.search(r"--allow-accessibility-enable(?:=|\s+<)(?:true|false|bool)", help_text, re.I):
            raise AssertionError("CLI unexpectedly advertises an explicit-false spelling")
        evidence["advertisedCliBoolean"] = {"true": "--allow-accessibility-enable", "false": "omitted"}
        flush()
        base = [str(args.cli), "a11y", "tree", "--pid", str(target["pid"]), "--timeout-ms", str(REQUEST_MS)]
        omitted = record("cliOmittedFalse", run_call(base, env))
        assert_cli_disabled(omitted, "CLI omitted/false")
        positive = record("cliTrue", run_call(base + ["--allow-accessibility-enable"], env))
        if positive.get("returnCode") != 0 or positive.get("stderr", "").splitlines() != [f"notice: {NOTE}"]:
            raise AssertionError(f"CLI positive failed: {positive!r}")
        assert_transition(positive, True, "CLI true")
        tree = find_tree(json_documents(positive["stdout"]))
        evidence["cliOracle"] = assert_tree(tree, target["pid"], target["title"], target["marker"], decoy["title"], decoy["marker"], "CLI true")
        flush()

        mcp = Mcp(args.cli, env)
        try:
            record("mcpInitialize", mcp.call(1, "initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "packaged-disabled-atspi-qa", "version": "1"}}))
            mcp.notify_initialized()
            listing = record("mcpToolsList", mcp.call(2, "tools/list", {}))
            tools = [tool for tool in listing.get("result", {}).get("tools", []) if tool.get("name") == TOOL]
            if len(tools) != 1:
                raise AssertionError(f"runtime tools/list missing exact {TOOL!r}")
            properties = tools[0].get("inputSchema", {}).get("properties", {})
            if properties.get("allow_accessibility_enable", {}).get("type") != "boolean" or "allowAccessibilityEnable" in properties:
                raise AssertionError(f"{TOOL} must expose only snake_case boolean allow_accessibility_enable")
            evidence["advertisedMcpBoolean"] = "allow_accessibility_enable"
            flush()
            common = {"pid": target["pid"], "timeout_ms": REQUEST_MS}
            cases = ((3, "Omitted", common, False),
                     (4, "False", dict(common, allow_accessibility_enable=False), False),
                     (5, "True", dict(common, allow_accessibility_enable=True), True))
            for identifier, suffix, arguments, enabled in cases:
                response = record("mcp" + suffix, mcp.call(identifier, "tools/call", {"name": TOOL, "arguments": arguments}, monitor_call=True))
                payload = mcp_payload(response, "MCP " + suffix.lower())
                assert_transition(response["_qa"], enabled, "MCP " + suffix.lower())
                if enabled:
                    if response.get("result", {}).get("isError") is True:
                        raise AssertionError(f"MCP true failed: {payload!r}")
                    evidence["mcpOracle"] = assert_tree(find_tree(payload), target["pid"], target["title"], target["marker"], decoy["title"], decoy["marker"], "MCP true")
                else:
                    if response.get("result", {}).get("isError") is not True:
                        raise AssertionError("MCP refusal did not set isError=true")
                    assert_disabled_payload(payload, "MCP " + suffix.lower())
                flush()
        finally:
            evidence["mcpProcess"] = mcp.close()
            flush()
    except BaseException as error:
        evidence["failure"] = {"type": type(error).__name__, "message": str(error)}
        flush()
        raise


if __name__ == "__main__":
    main()

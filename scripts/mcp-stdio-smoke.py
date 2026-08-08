#!/usr/bin/env python3
"""Fail-closed smoke for a packaged Spectre MCP stdio server."""
import argparse
import json
import subprocess
import sys


def fail(message: str, process: subprocess.Popen[str]) -> None:
    process.kill()
    print(f"MCP SMOKE FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("executable command required after --")
    process = subprocess.Popen(
        [*command, "mcp"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, bufsize=1,
    )
    assert process.stdin and process.stdout

    def request(identifier: int, method: str, params: dict) -> dict:
        process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params}) + "\n")
        process.stdin.flush()
        line = process.stdout.readline()
        try:
            response = json.loads(line)
        except json.JSONDecodeError:
            fail(f"non-JSON stdout before {method}: {line.rstrip()!r}", process)
        if response.get("id") != identifier or "result" not in response:
            fail(f"invalid {method} response: {response}", process)
        return response["result"]

    initialized = request(1, "initialize", {
        "protocolVersion": "2025-03-26", "capabilities": {},
        "clientInfo": {"name": "spectre-release-smoke", "version": "1"},
    })
    actual = initialized.get("serverInfo", {}).get("version")
    if actual != args.expected_version:
        fail(f"server version {actual!r}, expected {args.expected_version!r}", process)
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}) + "\n")
    process.stdin.flush()
    tools = request(2, "tools/list", {}).get("tools", [])
    names = {tool.get("name") for tool in tools}
    if "list_processes" not in names:
        fail(f"tools/list missing list_processes: {sorted(names)}", process)
    if "detach" not in names:
        fail(f"tools/list missing detach: {sorted(names)}", process)
    request(3, "tools/call", {"name": "list_processes", "arguments": {}})
    # Unknown / already-detached session must fail closed (not silent success).
    process.stdin.write(json.dumps({
        "jsonrpc": "2.0", "id": 4, "method": "tools/call",
        "params": {"name": "detach", "arguments": {"session_id": "mcp-smoke-missing-session"}},
    }) + "\n")
    process.stdin.flush()
    detach_line = process.stdout.readline()
    try:
        detach_response = json.loads(detach_line)
    except json.JSONDecodeError:
        fail(f"non-JSON stdout for detach: {detach_line.rstrip()!r}", process)
    if detach_response.get("id") != 4 or "result" not in detach_response:
        fail(f"invalid detach response: {detach_response}", process)
    detach_result = detach_response["result"]
    if not detach_result.get("isError"):
        fail(f"detach of unknown session must set isError: {detach_result}", process)
    process.stdin.close()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        fail("server did not exit after stdin closed", process)
    print(f"MCP SMOKE PASS: version={actual} tools={len(tools)}")


if __name__ == "__main__":
    main()

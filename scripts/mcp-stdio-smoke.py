#!/usr/bin/env python3
"""Fail-closed smoke for a packaged Spectre MCP stdio server.

Always proves:
  - initialize serverInfo.version
  - tools/list includes list_processes + detach
  - unknown/already-detached detach returns isError (not silent success)

When --attach-pid is provided (live fixture/target), also proves:
  - attach → cheap session op (tree and/or windows) → detach
  - subsequent op on that session fails closed (session gone)
  - second detach on the same id is isError
  - teardown via `daemon kill` (no leaked idle daemon for the smoke user)

Release-smoke hard cells require the full lifecycle via DaemonFixture e2e (#414);
this script's fixture path is the portable stdio leg when a PID is available.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


def fail(message: str, process: subprocess.Popen[str] | None = None) -> None:
    if process is not None and process.poll() is None:
        process.kill()
    print(f"MCP SMOKE FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def _read_json_line(process: subprocess.Popen[str], context: str) -> dict[str, Any]:
    assert process.stdout is not None
    line = process.stdout.readline()
    if not line:
        fail(f"empty stdout while waiting for {context}", process)
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        fail(f"non-JSON stdout for {context}: {line.rstrip()!r}", process)
        raise  # unreachable; fail() exits


def _tool_call(
    process: subprocess.Popen[str],
    identifier: int,
    name: str,
    arguments: dict[str, Any],
) -> dict[str, Any]:
    assert process.stdin is not None
    process.stdin.write(
        json.dumps(
            {
                "jsonrpc": "2.0",
                "id": identifier,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            }
        )
        + "\n"
    )
    process.stdin.flush()
    response = _read_json_line(process, f"tools/call {name}")
    if response.get("id") != identifier or "result" not in response:
        fail(f"invalid tools/call {name} response: {response}", process)
    return response["result"]


def _assert_tool_ok(result: dict[str, Any], name: str) -> dict[str, Any]:
    if result.get("isError"):
        fail(f"{name} must succeed, got isError: {result}")
    return result


def _text_content(result: dict[str, Any]) -> str:
    parts: list[str] = []
    for item in result.get("content") or []:
        if isinstance(item, dict) and item.get("type") == "text":
            parts.append(str(item.get("text") or ""))
        elif isinstance(item, dict) and "text" in item:
            parts.append(str(item["text"]))
    return "\n".join(parts)


def _daemon_kill(command: list[str], env: dict[str, str] | None) -> None:
    """Best-effort teardown so smoke does not leave an idle daemon for the isolation user."""
    try:
        subprocess.run(
            [*command, "daemon", "kill"],
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument(
        "--attach-pid",
        type=int,
        default=None,
        help="When set, prove attach → tree/windows → detach → session-gone on this PID",
    )
    parser.add_argument(
        "--daemon-user",
        default=None,
        help="Isolate daemon via JAVA_TOOL_OPTIONS -Duser.name (recommended with --attach-pid)",
    )
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("executable command required after --")

    env: dict[str, str] | None = None
    if args.daemon_user:
        # Match DaemonFixture isolation: Unix prefers /tmp (macOS symlink exception);
        # Windows uses the process temp directory.
        if sys.platform.startswith("win"):
            home = Path(tempfile.gettempdir()) / f"spectre-mcp-smoke-home-{args.daemon_user}"
        else:
            home = Path("/tmp") / f"spectre-mcp-smoke-home-{args.daemon_user}"
        home.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        existing = env.get("JAVA_TOOL_OPTIONS", "").strip()
        isolation = (
            f"-Duser.name={args.daemon_user} "
            f"-Duser.home={home} "
            f"-Djava.awt.headless=false"
        )
        env["JAVA_TOOL_OPTIONS"] = f"{existing} {isolation}".strip()

    process = subprocess.Popen(
        [*command, "mcp"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        env=env,
    )
    assert process.stdin and process.stdout

    def request(identifier: int, method: str, params: dict[str, Any]) -> dict[str, Any]:
        process.stdin.write(
            json.dumps({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
            + "\n"
        )
        process.stdin.flush()
        response = _read_json_line(process, method)
        if response.get("id") != identifier or "result" not in response:
            fail(f"invalid {method} response: {response}", process)
        return response["result"]

    try:
        initialized = request(
            1,
            "initialize",
            {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "spectre-release-smoke", "version": "1"},
            },
        )
        actual = initialized.get("serverInfo", {}).get("version")
        if actual != args.expected_version:
            fail(f"server version {actual!r}, expected {args.expected_version!r}", process)
        process.stdin.write(
            json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
            + "\n"
        )
        process.stdin.flush()
        tools = request(2, "tools/list", {}).get("tools", [])
        names = {tool.get("name") for tool in tools}
        if "list_processes" not in names:
            fail(f"tools/list missing list_processes: {sorted(names)}", process)
        if "detach" not in names:
            fail(f"tools/list missing detach: {sorted(names)}", process)
        if "attach" not in names:
            fail(f"tools/list missing attach: {sorted(names)}", process)
        if "tree" not in names and "windows" not in names:
            fail(f"tools/list missing tree/windows: {sorted(names)}", process)

        next_id = 3
        _assert_tool_ok(
            _tool_call(process, next_id, "list_processes", {}),
            "list_processes",
        )
        next_id += 1

        lifecycle = "tools+unknown-detach"
        if args.attach_pid is not None:
            attach_result = _assert_tool_ok(
                _tool_call(process, next_id, "attach", {"pid": args.attach_pid}),
                "attach",
            )
            next_id += 1
            attach_text = _text_content(attach_result)
            try:
                attach_payload = json.loads(attach_text)
                session_id = attach_payload["sessionId"]
            except (json.JSONDecodeError, KeyError, TypeError):
                fail(f"attach did not return sessionId JSON: {attach_text!r}", process)
                return

            # Cheap session op: prefer tree, fall back to windows.
            if "tree" in names:
                _assert_tool_ok(
                    _tool_call(process, next_id, "tree", {"session_id": session_id}),
                    "tree",
                )
                next_id += 1
                cheap_op = "tree"
            else:
                _assert_tool_ok(
                    _tool_call(process, next_id, "windows", {"session_id": session_id}),
                    "windows",
                )
                next_id += 1
                cheap_op = "windows"

            detach_result = _assert_tool_ok(
                _tool_call(process, next_id, "detach", {"session_id": session_id}),
                "detach",
            )
            next_id += 1
            detach_text = _text_content(detach_result)
            try:
                detach_payload = json.loads(detach_text)
            except json.JSONDecodeError:
                fail(f"detach did not return JSON summary: {detach_text!r}", process)
                return
            if detach_payload.get("sessionId") != session_id:
                fail(f"detach summary sessionId mismatch: {detach_payload}", process)

            # Session must be gone: subsequent op fails closed.
            after = _tool_call(process, next_id, cheap_op, {"session_id": session_id})
            next_id += 1
            if not after.get("isError"):
                fail(f"post-detach {cheap_op} must set isError (session gone): {after}", process)

            double = _tool_call(process, next_id, "detach", {"session_id": session_id})
            next_id += 1
            if not double.get("isError"):
                fail(f"already-detached detach must set isError: {double}", process)

            lifecycle = f"attach/{cheap_op}/detach/session-gone"

        # Unknown / never-attached session must fail closed (not silent success).
        unknown = _tool_call(
            process,
            next_id,
            "detach",
            {"session_id": "mcp-smoke-missing-session"},
        )
        if not unknown.get("isError"):
            fail(f"detach of unknown session must set isError: {unknown}", process)

        process.stdin.close()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            fail("server did not exit after stdin closed", process)
        print(
            f"MCP SMOKE PASS: version={actual} tools={len(tools)} lifecycle={lifecycle}",
            flush=True,
        )
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        # Tear down any daemon started by list_processes / attach for the isolation user.
        _daemon_kill(command, env)


if __name__ == "__main__":
    main()

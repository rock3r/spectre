#!/usr/bin/env python3
"""Repeat the #716 native AT-SPI ownership contracts in a real GNOME session."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

import dbus


TITLE = "Spectre AccessKit Fixture"
REPETITIONS = 8
QUERY_TIMEOUT_MS = 5000


def status_property():
    proxy = dbus.SessionBus().get_object("org.a11y.Bus", "/org/a11y/bus")
    return dbus.Interface(proxy, "org.freedesktop.DBus.Properties")


def get_enabled(properties):
    return bool(properties.Get("org.a11y.Status", "IsEnabled"))


def set_enabled(properties, enabled):
    properties.Set("org.a11y.Status", "IsEnabled", dbus.Boolean(enabled))
    actual = get_enabled(properties)
    if actual != enabled:
        raise RuntimeError(f"AT-SPI status requested={enabled} actual={actual}")


def request(target, timeout_ms=QUERY_TIMEOUT_MS):
    return {
        "target": target,
        "maximumDepth": 64,
        "maximumNodeCount": 10000,
        "timeoutMs": timeout_ms,
        "allowAccessibilityEnable": True,
    }


def success(reply):
    windows = reply.get("snapshot", {}).get("windows", [])
    return (
        reply.get("ok") is True
        and len(windows) == 1
        and windows[0].get("title") == TITLE
    ), "expected a successful snapshot of the fixture title"


def ambiguous(reply):
    error = reply.get("error", {})
    expected = f'window title "{TITLE}" is ambiguous across 2 AT-SPI window roots owned by the requested process'
    return (
        reply.get("ok") is False
        and error.get("code") == "target-not-found"
        and error.get("message") == expected
    ), f"expected target-not-found with exact ambiguity message {expected!r}"


def wrong_owner(reply, verification_pid):
    error = reply.get("error", {})
    expected = f'timed out waiting for window title "{TITLE}" owned by process {verification_pid} to register with AT-SPI'
    return (
        reply.get("ok") is False
        and error.get("code") == "target-not-found"
        and error.get("message") == expected
    ), f"expected target-not-found with exact owned-PID message {expected!r}"


def deadline(reply):
    error = reply.get("error", {})
    return (
        reply.get("ok") is False and error.get("code") == "timeout"
    ), "expected the typed timeout result for the explicit 1ms deadline"


def launch_fixture(binary, log_path):
    log = log_path.open("wb")
    process = subprocess.Popen([str(binary)], stdout=log, stderr=subprocess.STDOUT)
    deadline_at = time.monotonic() + 15
    while time.monotonic() < deadline_at and process.poll() is None:
        log.flush()
        match = re.search(rb"SPECTRE_ACCESSKIT_FIXTURE_READY pid=(\d+)", log_path.read_bytes())
        if match:
            ready_pid = int(match.group(1))
            if ready_pid != process.pid:
                process.terminate()
                process.wait(timeout=5)
                log.close()
                raise RuntimeError(f"fixture ready PID {ready_pid} does not match child PID {process.pid}")
            return process, log
        time.sleep(0.05)
    log.flush()
    detail = log_path.read_text(errors="replace")
    process.terminate()
    process.wait(timeout=5)
    log.close()
    raise RuntimeError(f"fixture did not publish its ready marker: {detail}")


def invoke(helper, payload, response_path):
    request_bytes = json.dumps(payload, separators=(",", ":")).encode()
    response_path.with_suffix(".request.json").write_bytes(request_bytes + b"\n")
    started = time.monotonic_ns()
    try:
        run = subprocess.run(
            [str(helper), "accessibility-snapshot"],
            input=request_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=10,
            check=False,
        )
        elapsed_ms = (time.monotonic_ns() - started) / 1_000_000
        response_path.write_bytes(run.stdout)
        response_path.with_suffix(".stderr").write_bytes(run.stderr)
        try:
            reply = json.loads(run.stdout)
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            return elapsed_ms, run.returncode, None, f"invalid helper JSON bytes: {error}"
        if run.returncode != 0:
            return elapsed_ms, run.returncode, reply, f"helper exited {run.returncode}"
        return elapsed_ms, run.returncode, reply, None
    except subprocess.TimeoutExpired as error:
        elapsed_ms = (time.monotonic_ns() - started) / 1_000_000
        response_path.write_bytes(error.stdout or b"")
        response_path.with_suffix(".stderr").write_bytes(error.stderr or b"")
        return elapsed_ms, None, None, "helper process exceeded the 10s harness bound"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    output = args.artifacts / "atspi-ownership"
    output.mkdir(parents=True, exist_ok=True)
    properties = status_property()
    initial_status = get_enabled(properties)
    fixtures = []
    logs = []
    results = []
    verification_pid = os.getpid()

    if "DISPLAY" in os.environ:
        raise RuntimeError(f"native Wayland ownership probe requires DISPLAY unset, got {os.environ['DISPLAY']!r}")

    try:
        # Establish both real roots while accessibility is already enabled. Disabled-state cases
        # still exercise AccessKit's authoritative asynchronous re-registration after the native
        # helper enables accessibility; this warm-up is not substituted for that edge.
        set_enabled(properties, True)
        for name in ("a", "b"):
            process, log = launch_fixture(args.fixture, output / f"fixture-{name}.log")
            fixtures.append(process)
            logs.append(log)
        fixture_pids = [process.pid for process in fixtures]
        for index, fixture_pid in enumerate(fixture_pids):
            established = False
            establishment = output / f"establish-{index}.json"
            for _ in range(3):
                _, _, reply, invocation_error = invoke(
                    args.helper, request({"type": "process-id", "value": fixture_pid}), establishment
                )
                if invocation_error is None and reply is not None and success(reply)[0]:
                    established = True
                    break
            if not established:
                raise RuntimeError(f"could not establish initial AT-SPI root for fixture PID {fixture_pid}")

        cases = [
            ("owner-a", request({"type": "window-title", "value": TITLE, "processId": fixture_pids[0]}), success),
            ("owner-b", request({"type": "window-title", "value": TITLE, "processId": fixture_pids[1]}), success),
            ("ambiguous-title", request({"type": "window-title", "value": TITLE}), ambiguous),
            ("wrong-owner", request({"type": "window-title", "value": TITLE, "processId": verification_pid}), lambda reply: wrong_owner(reply, verification_pid)),
            ("deadline-1ms", request({"type": "process-id", "value": fixture_pids[0]}, 1), deadline),
        ]
        for desired_status in (False, True):
            status_name = "fresh-disabled" if not desired_status else "already-enabled"
            for repetition in range(1, REPETITIONS + 1):
                for case_name, payload, validate in cases:
                    set_enabled(properties, desired_status)
                    before = get_enabled(properties)
                    stem = f"{status_name}-{repetition:02d}-{case_name}"
                    elapsed_ms, returncode, reply, invocation_error = invoke(
                        args.helper, payload, output / f"{stem}.json"
                    )
                    after = get_enabled(properties)
                    expected_after = desired_status
                    if invocation_error is None and reply is not None:
                        valid_reply, expectation = validate(reply)
                    else:
                        valid_reply, expectation = False, invocation_error or "missing reply"
                    passed = before == desired_status and after == expected_after and valid_reply
                    failures = []
                    if before != desired_status:
                        failures.append(f"setting before={before}, expected {desired_status}")
                    if after != expected_after:
                        failures.append(f"setting after={after}, expected {expected_after}")
                    if not valid_reply:
                        failures.append(expectation)
                    result = {
                        "case": case_name,
                        "mode": status_name,
                        "repetition": repetition,
                        "settingsBefore": before,
                        "settingsAfter": after,
                        "elapsedMs": elapsed_ms,
                        "helperReturnCode": returncode,
                        "reply": reply,
                        "fixturePids": fixture_pids,
                        "verificationPid": verification_pid,
                        "passed": passed,
                        "failures": failures,
                    }
                    results.append(result)
                    print(json.dumps(result, separators=(",", ":")), flush=True)
    finally:
        for process in fixtures:
            if process.poll() is None:
                process.terminate()
        for process in fixtures:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()
        set_enabled(properties, initial_status)

    summary = {
        "repetitions": REPETITIONS,
        "queryTimeoutMs": QUERY_TIMEOUT_MS,
        "explicitDeadlineMs": 1,
        "initialAccessibilityEnabled": initial_status,
        "results": results,
        "comparison": {
            "baseline": {
                "sha": "f4044ce86653ba2f60153e2759d208e7e759e3ae",
                "case": "wrong-owner",
                "requestTimeoutMs": 5000,
                "typedTimeoutOccurrences": 1,
                "observationRun": 10,
            },
            "candidate": {
                "wrongOwnerRuns": sum(result["case"] == "wrong-owner" for result in results),
                "typedTimeoutOccurrences": sum(
                    result["case"] == "wrong-owner"
                    and (result["reply"] or {}).get("error", {}).get("code") == "timeout"
                    for result in results
                ),
                "elapsedMs": [
                    result["elapsedMs"] for result in results if result["case"] == "wrong-owner"
                ],
            },
        },
        "passed": all(result["passed"] for result in results),
    }
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    suite = ET.Element(
        "testsuite",
        name="gnome-atspi-ownership",
        tests=str(len(results)),
        failures=str(sum(not result["passed"] for result in results)),
    )
    for result in results:
        testcase = ET.SubElement(
            suite,
            "testcase",
            classname=f"atspi.{result['mode']}",
            name=f"{result['case']}[{result['repetition']}]",
            time=f"{result['elapsedMs'] / 1000:.6f}",
        )
        ET.SubElement(testcase, "system-out").text = json.dumps(result, separators=(",", ":"))
        if not result["passed"]:
            ET.SubElement(testcase, "failure", message="; ".join(result["failures"])).text = json.dumps(result, indent=2)
    ET.ElementTree(suite).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)
    if not summary["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/python3
"""Record typed baseline/candidate differences for the optional-fields fixture."""
import argparse
import json
import subprocess
import time
from pathlib import Path


def invoke(helper, request):
    started = time.monotonic_ns()
    process = subprocess.run([str(helper), "accessibility-snapshot"], input=json.dumps(request), text=True, capture_output=True, timeout=15)
    try: response = json.loads(process.stdout)
    except json.JSONDecodeError: response = {"unparseable": process.stdout}
    return {"response": response, "returnCode": process.returncode, "stdout": process.stdout, "stderr": process.stderr, "elapsedMs": (time.monotonic_ns() - started) // 1_000_000}


def snapshot(result):
    return result["response"].get("snapshot") or result["response"].get("error", {}).get("partialSnapshot")


def normalized(result):
    response = result["response"]; error = response.get("error") or {}; snap = snapshot(result) or {}
    nodes = []
    for node in snap.get("nodes", []):
        nodes.append({field: node.get(field) for field in ("id", "platformRole", "name", "states", "value", "bounds", "interfaces")})
    return {"ok": response.get("ok"), "errorCode": error.get("code"), "hasPartial": error.get("partialSnapshot") is not None, "windows": snap.get("windows", []), "nodes": nodes}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True); parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--ready", type=Path, required=True); parser.add_argument("--journal", type=Path, required=True); parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); ready = json.loads(args.ready.read_text())
    request = {"target": {"type": "process-id", "value": ready["pid"]}, "maximumDepth": 16, "maximumNodeCount": 32, "timeoutMs": 5000, "allowAccessibilityEnable": True}
    baseline = invoke(args.baseline, request); middle = args.journal.stat().st_size; candidate = invoke(args.candidate, request)
    raw = args.journal.read_text(); baseline_calls = [json.loads(line) for line in raw[:middle].splitlines() if line]; candidate_calls = [json.loads(line) for line in raw[middle:].splitlines() if line]
    mode = ready["mode"]; bn, cn = normalized(baseline), normalized(candidate)
    required_error = lambda value: value["errorCode"] == "no-accessibility-tree" and not value["hasPartial"]
    checks = {"actualBusCallsObserved": bool(baseline_calls) and bool(candidate_calls), "candidateNoForbiddenStructuralPartial": not cn["hasPartial"]}
    if mode in ("missing-role", "missing-children"):
        checks["candidateStructuralNoTree"] = required_error(cn)
    elif mode == "optional-absent":
        fields = ("id", "name", "states", "value", "bounds", "interfaces")
        baseline_defaults = {field: bn["nodes"][1].get(field) for field in fields} if len(bn["nodes"]) > 1 else None
        candidate_defaults = {field: cn["nodes"][1].get(field) for field in fields} if len(cn["nodes"]) > 1 else None
        checks.update(candidateSucceeded=cn["ok"] is True, optionalDefaultsMatchBaseline=baseline_defaults is not None and candidate_defaults == baseline_defaults)
    else:
        alpha = next((node for node in cn["nodes"] if node["id"] == "alpha-target"), {})
        checks.update(candidateSucceeded=cn["ok"] is True, twoWindows=len(cn["windows"]) == 2, positiveName=alpha.get("name") == "Alpha positive name", positiveText=alpha.get("value") == "positive text value", positiveStates=bool(alpha.get("states")), positiveRole=alpha.get("platformRole") == "label", offscreenBounds=(alpha.get("bounds") or {}).get("x") == -240)
    receipt = {"schemaVersion": 1, "mode": mode, "fixture": ready, "request": request, "baseline": {"raw": baseline, "typed": bn}, "candidate": {"raw": candidate, "typed": cn}, "typedDifferences": {key: {"baseline": bn[key], "candidate": cn[key]} for key in bn if bn[key] != cn[key]}, "providerEvidence": {"baseline": baseline_calls, "candidate": candidate_calls}, "checks": checks, "passed": all(checks.values())}
    args.output.write_text(json.dumps(receipt, indent=2) + "\n"); print(json.dumps(receipt))
    if not receipt["passed"]: raise SystemExit(1)


if __name__ == "__main__": main()

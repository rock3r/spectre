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


FIELDS = ("platformRole", "id", "name", "value", "bounds", "states", "parentKey", "onScreen", "interfaces")


def normalized(result):
    response = result["response"]; error = response.get("error") or {}; snap = snapshot(result) or {}
    nodes = []
    for node in snap.get("nodes", []):
        nodes.append({field: node.get(field) for field in FIELDS})
    return {"ok": response.get("ok"), "errorCode": error.get("code"), "hasPartial": error.get("partialSnapshot") is not None, "windows": snap.get("windows", []), "nodes": nodes}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True); parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--ready", type=Path, required=True); parser.add_argument("--journal", type=Path, required=True); parser.add_argument("--wire", type=Path, required=True); parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--early-typed-timeout-acceptance", action="store_true")
    args = parser.parse_args(); ready = json.loads(args.ready.read_text())
    mode = ready["mode"]
    target = ({"type": "window-id", "value": ready["owner"] + "|" + ready["windows"]["alpha"]}
              if mode.startswith("early-") else {"type": "process-id", "value": ready["pid"]})
    request = {"target": target, "maximumDepth": 16, "maximumNodeCount": 32, "timeoutMs": 5000, "allowAccessibilityEnable": True}
    baseline = invoke(args.baseline, request); middle = args.journal.stat().st_size; candidate = invoke(args.candidate, request)
    raw = args.journal.read_text(); baseline_calls = [json.loads(line) for line in raw[:middle].splitlines() if line]; candidate_calls = [json.loads(line) for line in raw[middle:].splitlines() if line]
    bn, cn = normalized(baseline), normalized(candidate)
    required_error = lambda value: value["errorCode"] == "no-accessibility-tree" and not value["hasPartial"]
    checks = {"actualBusCallsObserved": bool(baseline_calls) and bool(candidate_calls), "candidateNoForbiddenStructuralPartial": not cn["hasPartial"]}
    if mode == "missing-children":
        checks["candidateStructuralNoTree"] = required_error(cn)
    elif mode == "missing-role":
        alpha_b = next((node for node in bn["nodes"] if node["id"] == "alpha-target"), None)
        alpha_c = next((node for node in cn["nodes"] if node["id"] == "alpha-target"), None)
        checks.update(baselineSucceeded=bn["ok"] is True, candidateSucceeded=cn["ok"] is True,
                      unsupportedRoleFallbackParity=alpha_b is not None and alpha_c == alpha_b and alpha_c["platformRole"] is None)
    elif mode == "optional-absent":
        baseline_defaults = next((node for node in bn["nodes"] if node["id"] == "alpha-target"), None)
        candidate_defaults = next((node for node in cn["nodes"] if node["id"] == "alpha-target"), None)
        checks.update(candidateSucceeded=cn["ok"] is True, optionalDefaultsMatchBaseline=baseline_defaults is not None and candidate_defaults == baseline_defaults)
    elif mode.startswith("early-"):
        # Baseline is observation only. The opt-in gate turns the pending final candidate behavior
        # into acceptance without pretending the old contextual mapping was already correct.
        checks = {"actualBusCallsObserved": bool(baseline_calls) and bool(candidate_calls)}
        if args.early_typed_timeout_acceptance:
            checks["candidateTypedTimeout"] = cn["errorCode"] == "timeout"
            if cn["hasPartial"]:
                nodes = cn["nodes"]
                keys = {node["id"] for node in nodes}
                checks["candidatePartialIsLinkedPrefix"] = bool(nodes) and all(
                    node["parentKey"] is None or node["parentKey"] in keys for node in nodes
                )
    else:
        alpha = next((node for node in cn["nodes"] if node["id"] == "alpha-target"), {})
        checks.update(candidateSucceeded=cn["ok"] is True, twoWindows=len(cn["windows"]) == 2, positiveName=alpha.get("name") == "Alpha positive name", positiveText=alpha.get("value") == "positive text value", positiveStates=bool(alpha.get("states")), positiveRole=alpha.get("platformRole") == "label", offscreenBounds=(alpha.get("bounds") or {}).get("x") == -240)
    alpha = lambda value: next((node for node in value["nodes"] if node["id"] == "alpha-target"), None)
    beta = lambda value: [node for node in value["nodes"] if (node.get("id") or "").startswith("beta-")]
    matched_alpha = {field: {"baseline": (alpha(bn) or {}).get(field), "candidate": (alpha(cn) or {}).get(field)} for field in FIELDS}
    receipt = {"schemaVersion": 2, "mode": mode, "fixture": ready, "request": request, "baseline": {"raw": baseline, "typed": bn}, "candidate": {"raw": candidate, "typed": cn}, "matchedAlphaFields": matched_alpha, "alphaTypedDifferences": {field: values for field, values in matched_alpha.items() if values["baseline"] != values["candidate"]}, "typedDifferences": {key: {"baseline": bn[key], "candidate": cn[key]} for key in bn if bn[key] != cn[key]}, "betaNewlyAdded": [node for node in beta(cn) if node not in beta(bn)], "providerEvidence": {"baseline": baseline_calls, "candidate": candidate_calls}, "rawWireEvidence": args.wire.read_text(errors="replace"), "checks": checks, "passed": all(checks.values())}
    args.output.write_text(json.dumps(receipt, indent=2) + "\n"); print(json.dumps(receipt))
    if not receipt["passed"]: raise SystemExit(1)


if __name__ == "__main__": main()

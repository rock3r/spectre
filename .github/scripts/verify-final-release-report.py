#!/usr/bin/env python3
"""Fail closed on the canonical release report produced by the final Linux seat."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
import re
from pathlib import Path
import sys

LINUX_NA = {
    "macos-tcc": "Linux does not use macOS Screen Recording / Accessibility TCC",
    "a11y-accesskit-macos": "requires a native Darwin desktop",
    "a11y-accesskit-windows": "requires a native Windows desktop",
}

REQUIRED_LIVE_ROWS = {"portal-token-warmup", "host-native-recording"}
INPUT_ROWS = {
    "input-coord-contention",
    "input-coord-cancellation",
    "input-coord-quarantine",
    "input-coord-revoke",
    "input-coord-forced-recovery",
    "input-coord-junit-pertest",
    "input-coord-headed-robot",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--console", type=Path, required=True)
    parser.add_argument("--consent-summary", type=Path, required=True)
    parser.add_argument("--gnome-profile", choices=("gnome-42", "gnome-46"), default="gnome-46")
    args = parser.parse_args()
    sys.path.insert(0, str(args.repo / "scripts"))
    from smoke_lib import REQUIRED_SCENARIO_IDS, validate_report  # noqa: PLC0415

    report = json.loads(args.report.read_text())
    errors = validate_report(report, required_ids=list(REQUIRED_SCENARIO_IDS))
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        errors.append(f"expected SHA is not 40 lowercase hex: {args.sha!r}")
    if report.get("sha") != args.sha:
        errors.append(f"report SHA is {report.get('sha')!r}, expected {args.sha}")
    if report.get("dirty") is not False:
        errors.append(f"report dirty must be false, got {report.get('dirty')!r}")
    display_mode = report.get("environment", {}).get("displayMode")
    if display_mode != "seat-forced":
        errors.append(
            f"displayMode must be genuine supported seat-forced (never xvfb/none/headless), "
            f"got {display_mode!r}"
        )
    rows = report.get("scenarios", [])
    by_id = {row.get("id"): row for row in rows if isinstance(row, dict)}
    extra = set(by_id) - set(REQUIRED_SCENARIO_IDS)
    if extra:
        errors.append(f"unexpected scenario ids: {sorted(extra)}")
    if set(INPUT_ROWS) - set(by_id):
        errors.append(f"missing explicit input rows: {sorted(INPUT_ROWS - set(by_id))}")
    for scenario_id in sorted(INPUT_ROWS & set(by_id)):
        row = by_id[scenario_id]
        if row.get("result") != "pass" or row.get("hard") is not True:
            errors.append(f"{scenario_id} is not a hard pass: {row!r}")
    for scenario_id, reason in LINUX_NA.items():
        row = by_id.get(scenario_id)
        if row and (row.get("hard") is not True or row.get("result") != "n/a"
                    or row.get("reason") != reason):
            errors.append(f"{scenario_id} must be the truthful hard Linux n/a: {row!r}")
    for scenario_id in set(REQUIRED_SCENARIO_IDS) - set(LINUX_NA):
        row = by_id.get(scenario_id)
        if row and (row.get("hard") is not True or row.get("result") != "pass"):
            errors.append(f"applicable required row is not a hard pass: {scenario_id}: {row!r}")
    if not args.console.is_file() or args.console.parent != args.consent_summary.parent:
        errors.append("console and consent proof must exist in the same artifact path")
    try:
        consent = json.loads(args.consent_summary.read_text())
        grants = consent["grants"]
        if consent["count"] != len(grants) or not grants:
            raise ValueError("no counted grants")
        started = datetime.fromisoformat(report["startedAt"].replace("Z", "+00:00"))
        finished = datetime.fromisoformat(report["finishedAt"].replace("Z", "+00:00"))
        previous = started
        for grant in grants:
            observed = datetime.fromisoformat(grant["observedAt"])
            accepted = datetime.fromisoformat(grant["acceptedAt"])
            remember_matches_backend = (grant["rememberApproved"] is True if args.gnome_profile == "gnome-46"
                                        else grant.get("rememberAvailable") is False and grant["rememberApproved"] is False)
            if not (previous <= observed <= accepted <= finished
                    and grant["profile"] == args.gnome_profile
                    and grant["remoteInteractionApproved"] is True
                    and remember_matches_backend):
                raise ValueError(f"grant is not genuine {args.gnome_profile} GUI proof: {grant!r}")
            previous = accepted
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        errors.append(f"invalid genuine GUI grant proof: {error}")
    summary = {"candidateSha": args.sha, "dirty": report.get("dirty"),
               "requiredIds": list(REQUIRED_SCENARIO_IDS),
               "inputRows": {key: by_id.get(key) for key in sorted(INPUT_ROWS)},
               "errors": errors}
    output = args.report.parent / "final-linux-qa-summary.json"
    output.write_text(json.dumps(summary, indent=2) + "\n")
    if errors:
        raise SystemExit("\n".join(errors))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

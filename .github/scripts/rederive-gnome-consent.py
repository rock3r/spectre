#!/usr/bin/env python3
"""Re-derive GNOME consent from immutable historical AT-SPI evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

import gnome_portal_dialog_semantics as semantics

BACKEND = "org.freedesktop.impl.portal.desktop.gnome"
EXECUTABLE = "xdg-desktop-portal-gnome"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tree", type=Path, required=True)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--derived", type=Path, required=True)
    parser.add_argument("--differences", type=Path, required=True)
    args = parser.parse_args()

    tree = json.loads(args.tree.read_text())
    original = json.loads(args.original.read_text())
    if (tree.get("backend") != BACKEND or tree.get("executable") != EXECUTABLE
            or tree.get("matchedProfile") != "gnome-46"
            or tree.get("matchingDialogCount") != 1
            or not isinstance(tree.get("pid"), int)):
        raise SystemExit("raw tree has inconsistent native GNOME backend provenance")
    control = semantics.remember_checkbox(tree.get("nodes", []))
    if control is None:
        raise SystemExit("raw tree does not contain exactly one checked, showing Remember checkbox")
    grants = original.get("grants")
    if original.get("count") != 1 or not isinstance(grants, list) or len(grants) != 1:
        raise SystemExit("original summary must contain exactly one grant")
    grant = dict(grants[0])
    if (grant.get("portalBackendPid") != tree["pid"] or grant.get("profile") != "gnome-46"
            or grant.get("remoteInteractionApproved") is not True
            or not grant.get("observedAt") or not grant.get("acceptedAt")):
        raise SystemExit("original grant contradicts raw tree provenance or chronology")
    before = {"rememberAvailable": grant.get("rememberAvailable"),
              "rememberApproved": grant.get("rememberApproved")}
    grant.update(rememberAvailable=True, rememberApproved=True)
    derived = {"count": 1, "grants": [grant]}
    args.derived.write_text(json.dumps(derived, indent=2) + "\n")
    args.differences.write_text(json.dumps({
        "inputs": {"rawTree": {"basename": args.tree.name, "sha256": digest(args.tree)},
                   "originalConsentSummary": {"basename": args.original.name,
                                              "sha256": digest(args.original)}},
        "matchedControl": {"index": control["index"], "role": control["role"],
                           "name": control["name"], "states": control["states"]},
        "changes": {"before": before,
                    "after": {"rememberAvailable": True, "rememberApproved": True}},
    }, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

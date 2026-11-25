#!/usr/bin/env python3
"""Opt-in warmup for the real xdg-desktop-portal-gnome share dialog.

This helper only accepts the known fixture dialog owned by the installed portal process. It does
not alter permission stores or synthesize portal tokens. It is deliberately not invoked by the
hosted smoke until the coordinator pins a candidate whose portal registration is corrected.
"""

import os
from pathlib import Path
import subprocess
import sys

import pyatspi


EXPECTED_TITLE = "Screen Share"
EXPECTED_PROCESS = "/usr/libexec/xdg-desktop-portal-gnome"


def output(*args):
    return subprocess.check_output(args, text=True).strip()


if os.environ.get("SPECTRE_ENABLE_PORTAL_GUI_WARMUP") != "1":
    sys.exit("refusing portal GUI warmup without SPECTRE_ENABLE_PORTAL_GUI_WARMUP=1")
fixture_title = os.environ.get("SPECTRE_PORTAL_FIXTURE_TITLE")
if not fixture_title:
    sys.exit("refusing warmup without the exact SPECTRE_PORTAL_FIXTURE_TITLE")

windows = output("xdotool", "search", "--onlyvisible", "--name", f"^{EXPECTED_TITLE}$").splitlines()
if len(windows) != 1:
    sys.exit(f"expected exactly one {EXPECTED_TITLE!r} dialog, found {windows}")
window = windows[0]
pid = int(output("xdotool", "getwindowpid", window))
executable = str(Path(f"/proc/{pid}/exe").resolve())
if executable != EXPECTED_PROCESS:
    sys.exit(f"refusing non-portal dialog owned by {pid}: {executable}")

visible_text = output("xdotool", "getwindowname", window)
if visible_text != EXPECTED_TITLE:
    sys.exit(f"portal title changed unexpectedly: {visible_text!r}")

def descendants(node):
    yield node
    for child in node:
        yield from descendants(child)


# Select only an accessibility item naming the caller's exact fixture, then invoke the stock Share
# action. This avoids coordinates and fails closed if GNOME changes the dialog hierarchy or labels.
apps = [app for app in pyatspi.Registry.getDesktop(0) if app.get_process_id() == pid]
if len(apps) != 1:
    sys.exit(f"expected one portal accessibility application for pid {pid}, found {len(apps)}")
nodes = list(descendants(apps[0]))
fixtures = [node for node in nodes if node.name == fixture_title]
shares = [node for node in nodes if node.name in ("Share", "Allow") and node.getRoleName() == "push button"]
if len(fixtures) != 1 or len(shares) != 1:
    sys.exit(f"fixture/share controls not unique: fixture={len(fixtures)}, share={len(shares)}")
fixture_action = fixtures[0].queryAction()
select_actions = [i for i in range(fixture_action.nActions)
                  if fixture_action.getName(i).lower() in ("click", "press", "select", "activate")]
if len(select_actions) != 1 or not fixture_action.doAction(select_actions[0]):
    sys.exit("could not select exact portal fixture")
share_action = shares[0].queryAction()
if share_action.nActions != 1 or not share_action.doAction(0):
    sys.exit("could not activate stock portal Share/Allow action")

print(f"activated official portal dialog {window} for fixture {fixture_title!r}, owned by pid {pid}")

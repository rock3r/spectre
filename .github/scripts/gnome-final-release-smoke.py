#!/usr/bin/env python3
"""Attend genuine GNOME grants while the unmodified final release smoke runs."""
from __future__ import annotations

import importlib.util
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time

ARTIFACTS = Path(os.environ["SMOKE_ARTIFACTS"])
REPOSITORY = Path(os.environ["SMOKE_REPO"])
PORTAL_PATH = Path(__file__).with_name("gnome-portal-dialog-warmup.py")
SPEC = importlib.util.spec_from_file_location("gnome_portal_dialog_warmup", PORTAL_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load portal attendant from {PORTAL_PATH}")
portal = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(portal)


def preserve_junit(stop: threading.Event) -> None:
    """Retain each cell XML even when a later filtered Gradle run replaces it."""
    destination = ARTIFACTS / "junit-xml"
    destination.mkdir(exist_ok=True)
    seen: set[str] = set()
    while not stop.wait(1):
        for source in REPOSITORY.glob("**/build/test-results/**/TEST-*.xml"):
            try:
                data = source.read_bytes()
            except OSError:
                continue
            digest = hashlib.sha256(data).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            relative = "--".join(source.relative_to(REPOSITORY).parts)
            shutil.copyfile(source, destination / f"{digest[:12]}--{relative}")


def complete_picker_present(pid: int) -> bool:
    """Probe without writing a timeout dump when no picker is being shown."""
    apps = [app for app in portal.pyatspi.Registry.getDesktop(0)
            if app is not None and app.get_process_id() == pid]
    try:
        for app in apps:
            for node in portal.descendants(app):
                if portal.semantics.is_dialog(node.getRoleName(), node.name):
                    facts = portal.facts(list(portal.descendants(node)))
                    if portal.semantics.profile(facts) is not None:
                        return True
    except (portal.GLib.GError, AttributeError, TypeError) as error:
        if not portal.expected_disappearance(error):
            raise
        return False
    return False


def main() -> int:
    shell_version = subprocess.check_output(["gnome-shell", "--version"], text=True).strip()
    require_remember = not shell_version.startswith("GNOME Shell 42.")
    backend = portal.backend_pid(portal.dbus.SessionBus())
    log_path = ARTIFACTS / "release-smoke-console.log"
    grants: list[dict[str, object]] = []
    command = [sys.executable, "scripts/release-smoke.py", "--version", "0.10.0",
               "--base", "v0.9.0", "--display-mode", "seat"]
    sampler_stop = threading.Event()
    sampler = threading.Thread(target=preserve_junit, args=(sampler_stop,), daemon=True)
    sampler.start()
    with log_path.open("w") as log:
        child = subprocess.Popen(command, cwd=REPOSITORY, stdout=log,
                                 stderr=subprocess.STDOUT)
        try:
            while child.poll() is None:
                if not complete_picker_present(backend):
                    time.sleep(0.2)
                    continue
                ordinal = len(grants) + 1
                observed_at = datetime.now(timezone.utc).isoformat()
                # Once a complete picker is observed, every consent/action error is
                # substantive and deliberately propagates instead of being swallowed.
                selected = portal.accept_dialog(
                    backend, ARTIFACTS / f"consent-{ordinal}-atspi-tree.json",
                    approve_remember=require_remember,
                )
                grants.append({
                    "ordinal": ordinal,
                    "portalBackendPid": backend,
                    "observedAt": observed_at,
                    "acceptedAt": datetime.now(timezone.utc).isoformat(),
                    "profile": selected["profile"],
                    "selectedRole": selected["role"],
                    "remoteInteractionApproved": selected["remoteInteractionApproved"],
                    "rememberAvailable": selected["rememberAvailable"],
                    "rememberApproved": selected["rememberApproved"],
                })
                (ARTIFACTS / "consent-summary.json").write_text(
                    json.dumps({"count": len(grants), "grants": grants}, indent=2) + "\n"
                )
        except BaseException:
            child.terminate()
            child.wait(timeout=30)
            raise
        result = child.wait()
    sampler_stop.set()
    sampler.join(timeout=5)
    return result


if __name__ == "__main__":
    raise SystemExit(main())

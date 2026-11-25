"""Report the live AT-SPI wire values for the two known proof applications."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import dbus

artifacts = Path(os.environ["SMOKE_ARTIFACTS"])
helper = Path(os.environ["ATSPI_REPO"]) / "recording/native/linux/target/debug/spectre-wayland-helper"
accessible = "org.a11y.atspi.Accessible"
session = dbus.SessionBus()
address = session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus")
bus = dbus.bus.BusConnection(str(address))
registry = bus.get_object("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root")
registry_app_list = dbus.Interface(registry, accessible).GetChildren
bus_admin = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"), "org.freedesktop.DBus")


def application_root(pid):
    for _ in range(100):
        for name, path in registry_app_list():
            if int(bus_admin.GetConnectionUnixProcessID(name)) == pid:
                return name, path
        time.sleep(0.1)
    raise RuntimeError(f"no AT-SPI root for pid {pid}")


def inspect_tree(pid):
    pending = [application_root(pid)]
    records = []
    seen = set()
    while pending:
        service, path = pending.pop()
        ident = str(service), str(path)
        if ident in seen:
            continue
        seen.add(ident)
        obj = bus.get_object(service, path)
        iface = dbus.Interface(obj, accessible)
        properties = dbus.Interface(obj, "org.freedesktop.DBus.Properties")
        attrs = dict(iface.GetAttributes())
        records.append({"path": str(path), "name": str(properties.Get(accessible, "Name")), "attributes": attrs,
                        "role": int(iface.GetRole()), "roleName": str(iface.GetRoleName()),
                        "stateWords": [int(w) for w in iface.GetState()]})
        pending.extend(iface.GetChildren())
    return records


for tag, pid in zip(("fixture", "gtk"), map(int, sys.argv[1:])):
    request = {"target": {"type": "process-id", "value": pid}, "maximumDepth": 64,
               "maximumNodeCount": 10000, "timeoutMs": 5000, "allowAccessibilityEnable": False}
    result = subprocess.run([helper, "accessibility-snapshot"], input=json.dumps(request),
                            text=True, capture_output=True, check=True)
    snapshot = json.loads(result.stdout)
    assert snapshot["ok"], snapshot
    records = inspect_tree(pid)
    (artifacts / f"{tag}-role-comparison.json").write_text(json.dumps(
        {"pid": pid, "directATSPi": records, "spectre": snapshot["snapshot"]}, indent=2))
    if tag == "fixture":
        candidates = [r for r in records if "account-name" in r["attributes"].values()]
        assert candidates, records
    else:
        candidates = [r for r in records if r["name"] == "Complete proof"]
        assert candidates, records
    for row in candidates:
        print(f"{tag} raw AT-SPI: {json.dumps(row)}", flush=True)

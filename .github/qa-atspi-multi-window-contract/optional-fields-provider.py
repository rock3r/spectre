#!/usr/bin/python3
"""Cacheless two-window AT-SPI fixture for required/optional field contracts."""
import argparse
import json
import os
import time
from pathlib import Path

import dbus
import dbus.mainloop.glib
import dbus.service
import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

A11Y = "org.a11y.atspi"
ACC = A11Y + ".Accessible"
COMP = A11Y + ".Component"
TEXT = A11Y + ".Text"
PROP = "org.freedesktop.DBus.Properties"
NULL = ("", "/org/a11y/atspi/null")


class Journal:
    def __init__(self, path, bus):
        self.output = path.open("a", buffering=1)
        self.admin = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"), "org.freedesktop.DBus")

    def write(self, sender, path, interface, member, outcome="return"):
        try:
            pid = int(self.admin.GetConnectionUnixProcessID(sender))
        except dbus.DBusException:
            pid = None
        self.output.write(json.dumps({"monotonicNs": time.monotonic_ns(), "sender": sender, "senderPid": pid, "path": path, "interface": interface, "member": member, "outcome": outcome}) + "\n")
        os.fsync(self.output.fileno())


class Node(dbus.service.Object):
    def __init__(self, bus, journal, path, role, name, ident, parent=NULL, bounds=(0, 0, 10, 10), mode="control", target=False, text=None):
        super().__init__(bus, path)
        self.bus, self.journal, self.path = bus, journal, path
        self.role, self.name, self.ident, self.parent = role, name, ident, parent
        self.bounds, self.mode, self.target, self.text = bounds, mode, target, text
        self.children, self.application = [], NULL

    def record(self, sender, interface, member, outcome="return"):
        self.journal.write(sender, self.path, interface, member, outcome)

    def reject(self, sender, interface, member):
        self.record(sender, interface, member, "org.freedesktop.DBus.Error.UnknownMethod")
        raise dbus.DBusException("fixture deliberately omitted " + member, name="org.freedesktop.DBus.Error.UnknownMethod")

    def no_reply(self, sender, interface, member):
        self.record(sender, interface, member, "org.freedesktop.DBus.Error.NoReply")
        raise dbus.DBusException(
            "fixture returned a typed early timeout while the client deadline was live",
            name="org.freedesktop.DBus.Error.NoReply",
        )

    def optional_absent(self, member):
        return self.target and self.mode == "optional-absent" and member in {"Get", "GetAttributes", "GetState", "GetInterfaces", "GetExtents"}

    @dbus.service.method(ACC, in_signature="", out_signature="a(so)", sender_keyword="sender")
    def GetChildren(self, sender=None):
        if self.target and self.mode == "early-children-timeout": self.no_reply(sender, ACC, "GetChildren")
        if self.target and self.mode == "missing-children": self.reject(sender, ACC, "GetChildren")
        self.record(sender, ACC, "GetChildren"); return self.children

    @dbus.service.method(ACC, in_signature="", out_signature="a{ss}", sender_keyword="sender")
    def GetAttributes(self, sender=None):
        if self.optional_absent("GetAttributes"): self.reject(sender, ACC, "GetAttributes")
        self.record(sender, ACC, "GetAttributes"); return {"id": self.ident, "fixture": "optional-fields", "automation-id": "positive-" + self.ident}

    @dbus.service.method(ACC, in_signature="", out_signature="u", sender_keyword="sender")
    def GetRole(self, sender=None):
        if self.target and self.mode == "early-role-timeout": self.no_reply(sender, ACC, "GetRole")
        if self.target and self.mode == "missing-role": self.reject(sender, ACC, "GetRole")
        self.record(sender, ACC, "GetRole"); return dbus.UInt32(self.role)

    @dbus.service.method(ACC, in_signature="", out_signature="s", sender_keyword="sender")
    def GetRoleName(self, sender=None):
        if self.target and self.mode == "missing-role": self.reject(sender, ACC, "GetRoleName")
        self.record(sender, ACC, "GetRoleName"); return {68: "frame", 97: "grouping", 43: "label"}.get(self.role, "unknown")

    @dbus.service.method(ACC, in_signature="", out_signature="au", sender_keyword="sender")
    def GetState(self, sender=None):
        if self.optional_absent("GetState"): self.reject(sender, ACC, "GetState")
        self.record(sender, ACC, "GetState"); return [dbus.UInt32((1 << 3) | (1 << 12)), dbus.UInt32(0)]

    @dbus.service.method(ACC, in_signature="", out_signature="as", sender_keyword="sender")
    def GetInterfaces(self, sender=None):
        if self.optional_absent("GetInterfaces"): self.reject(sender, ACC, "GetInterfaces")
        self.record(sender, ACC, "GetInterfaces"); return [ACC, COMP] + ([TEXT] if self.text is not None else [])

    @dbus.service.method(COMP, in_signature="u", out_signature="(iiii)", sender_keyword="sender")
    def GetExtents(self, _coord, sender=None):
        if self.optional_absent("GetExtents"): self.reject(sender, COMP, "GetExtents")
        self.record(sender, COMP, "GetExtents"); return tuple(dbus.Int32(value) for value in self.bounds)

    @dbus.service.method(TEXT, in_signature="ii", out_signature="s", sender_keyword="sender")
    def GetText(self, _start, _end, sender=None):
        self.record(sender, TEXT, "GetText"); return self.text or ""

    @dbus.service.method(PROP, in_signature="ss", out_signature="v", sender_keyword="sender")
    def Get(self, _interface, name, sender=None):
        if self.optional_absent("Get"): self.reject(sender, PROP, "Get")
        self.record(sender, PROP, "Get")
        if name == "Name": return self.name
        raise dbus.DBusException("unknown property", name="org.freedesktop.DBus.Error.UnknownProperty")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ready", type=Path, required=True)
    parser.add_argument("--journal", type=Path, required=True)
    parser.add_argument("--mode", choices=("control", "optional-absent", "missing-role", "missing-children", "early-children-timeout", "early-role-timeout"), required=True)
    args = parser.parse_args()
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    session = dbus.SessionBus()
    address = str(session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus"))
    bus = dbus.bus.BusConnection(address); owner = bus.get_unique_name(); journal = Journal(args.journal, bus)
    windows = []
    for title in ("Optional Alpha", "Optional Beta"):
        window = Gtk.Window(title=title); window.add(Gtk.Label(label=title)); window.set_default_size(320, 180); window.show_all(); windows.append(window)
    while Gtk.events_pending(): Gtk.main_iteration()
    app = Node(bus, journal, "/org/a11y/atspi/accessible/app", 97, "fixture application", "app")
    objects = [app]; roots = {}
    for index, title in enumerate(("alpha", "beta")):
        x = 40 + index * 400; base = "/org/a11y/atspi/accessible/" + title
        root = Node(bus, journal, base, 68, title.title() + " window", title + "-root", (owner, app.path), (x, 60, 320, 180))
        target = Node(bus, journal, base + "/target", 43, title.title() + " positive name", title + "-target", (owner, root.path), (-240 if title == "alpha" else x + 20, 110, 180, 30), args.mode, title == "alpha", "positive text value")
        root.children = [(owner, target.path)]; app.children.append((owner, root.path)); objects.extend((root, target)); roots[title] = root.path
    for obj in objects: obj.application = (owner, app.path)
    dbus.Interface(bus.get_object("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root"), A11Y + ".Socket").Embed((owner, app.path))
    args.ready.write_text(json.dumps({"pid": os.getpid(), "owner": owner, "application": app.path, "windows": roots, "mode": args.mode}) + "\n")
    GLib.MainLoop().run()


if __name__ == "__main__": main()

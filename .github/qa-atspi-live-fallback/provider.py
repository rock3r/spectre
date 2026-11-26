#!/usr/bin/python3
"""Small, deliberately cache-less AT-SPI2 provider used only by hosted QA."""

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
from gi.repository import GLib, Gtk

A11Y = "org.a11y.atspi"
ACC = A11Y + ".Accessible"
COMP = A11Y + ".Component"
PROP = "org.freedesktop.DBus.Properties"
APP = A11Y + ".Application"
NULL = ("", "/org/a11y/atspi/null")


class Journal:
    def __init__(self, path, bus):
        self.output = open(path, "a", buffering=1)
        self.admin = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
                                    "org.freedesktop.DBus")

    def write(self, sender, path, interface, member, outcome="return"):
        try:
            pid = int(self.admin.GetConnectionUnixProcessID(sender))
        except dbus.DBusException:
            pid = None
        self.output.write(json.dumps({"monotonicNs": time.monotonic_ns(), "sender": sender,
                                      "senderPid": pid, "path": path, "interface": interface,
                                      "member": member, "outcome": outcome}) + "\n")
        os.fsync(self.output.fileno())


class Node(dbus.service.Object):
    def __init__(self, bus, journal, path, role, name, ident, parent=NULL, bounds=(0, 0, 1, 1),
                 late_mode="normal"):
        super().__init__(bus, path)
        self.journal, self.path, self.role, self.name = journal, path, role, name
        self.ident, self.parent, self.bounds, self.late_mode = ident, parent, bounds, late_mode
        self.children = []
        self.application = NULL

    def note(self, sender, interface, member):
        self.journal.write(sender, self.path, interface, member)

    def maybe_late(self, sender, interface, member):
        if self.ident != "late-sibling":
            return
        if self.late_mode == "slow":
            time.sleep(0.7)
        if self.late_mode == "error":
            self.journal.write(sender, self.path, interface, member,
                               A11Y + ".Error.NotAvailable")
            raise dbus.exceptions.DBusException("fixture late sibling rejected read",
                                                name=A11Y + ".Error.NotAvailable")

    @dbus.service.method(ACC, in_signature="", out_signature="a(so)", sender_keyword="sender")
    def GetChildren(self, sender=None):
        self.note(sender, ACC, "GetChildren"); self.maybe_late(sender, ACC, "GetChildren")
        return self.children

    @dbus.service.method(ACC, in_signature="", out_signature="a{ss}", sender_keyword="sender")
    def GetAttributes(self, sender=None):
        self.note(sender, ACC, "GetAttributes"); self.maybe_late(sender, ACC, "GetAttributes")
        return {"id": self.ident, "fixture": "real-cacheless-atspi"}

    @dbus.service.method(ACC, in_signature="", out_signature="u", sender_keyword="sender")
    def GetRole(self, sender=None):
        self.note(sender, ACC, "GetRole"); return dbus.UInt32(self.role)

    @dbus.service.method(ACC, in_signature="", out_signature="s", sender_keyword="sender")
    def GetRoleName(self, sender=None):
        self.note(sender, ACC, "GetRoleName"); return {14: "application", 68: "frame", 97: "grouping", 43: "label"}.get(self.role, "unknown")

    @dbus.service.method(ACC, in_signature="", out_signature="s", sender_keyword="sender")
    def GetLocalizedRoleName(self, sender=None):
        return self.GetRoleName(sender)

    @dbus.service.method(ACC, in_signature="", out_signature="au", sender_keyword="sender")
    def GetState(self, sender=None):
        self.note(sender, ACC, "GetState"); return [dbus.UInt32(0), dbus.UInt32(0)]

    @dbus.service.method(ACC, in_signature="", out_signature="(so)", sender_keyword="sender")
    def GetApplication(self, sender=None):
        self.note(sender, ACC, "GetApplication"); return self.application

    @dbus.service.method(ACC, in_signature="", out_signature="as", sender_keyword="sender")
    def GetInterfaces(self, sender=None):
        self.note(sender, ACC, "GetInterfaces"); return [ACC, COMP]

    @dbus.service.method(COMP, in_signature="u", out_signature="(iiii)", sender_keyword="sender")
    def GetExtents(self, coord_type, sender=None):
        self.note(sender, COMP, "GetExtents"); self.maybe_late(sender, COMP, "GetExtents")
        return tuple(dbus.Int32(value) for value in self.bounds)

    @dbus.service.method(PROP, in_signature="ss", out_signature="v", sender_keyword="sender")
    def Get(self, interface, name, sender=None):
        self.note(sender, PROP, "Get")
        values = {"Name": self.name, "Description": "real cache-less QA node",
                  "Parent": self.parent, "ChildCount": dbus.Int32(len(self.children)),
                  "ToolkitName": "spectre-qa", "Version": "1", "Id": dbus.Int32(991),
                  "Locale": "C"}
        if name not in values:
            raise dbus.exceptions.DBusException("unknown property", name="org.freedesktop.DBus.Error.UnknownProperty")
        return values[name]

    @dbus.service.method(PROP, in_signature="s", out_signature="a{sv}", sender_keyword="sender")
    def GetAll(self, interface, sender=None):
        self.note(sender, PROP, "GetAll"); return {}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--journal", required=True, type=Path)
    parser.add_argument("--ready", required=True, type=Path)
    parser.add_argument("--late-mode", choices=("normal", "error", "slow"), default="normal")
    args = parser.parse_args()
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    session = dbus.SessionBus()
    address = str(session.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus"))
    bus = dbus.bus.BusConnection(address)
    journal = Journal(args.journal, bus)
    owner = str(bus.get_unique_name())

    app = Node(bus, journal, "/org/a11y/atspi/accessible/app", 14, "Cacheless QA application", "qa-app")
    window = Node(bus, journal, "/org/a11y/atspi/accessible/window", 68, "Cacheless QA window", "qa-window",
                  (owner, app.path), (40, 50, 480, 260))
    group = Node(bus, journal, "/org/a11y/atspi/accessible/first_group", 97, "First group", "first-group",
                 (owner, window.path), (55, 80, 220, 120))
    grandchild = Node(bus, journal, "/org/a11y/atspi/accessible/grandchild", 43, "Grandchild", "grandchild",
                      (owner, group.path), (65, 95, 100, 30))
    late = Node(bus, journal, "/org/a11y/atspi/accessible/late_sibling", 43, "Late sibling", "late-sibling",
                (owner, window.path), (300, 80, 120, 30), args.late_mode)
    app.children = [(owner, window.path)]; window.children = [(owner, group.path), (owner, late.path)]
    group.children = [(owner, grandchild.path)]
    for node in (app, window, group, grandchild, late):
        node.application = (owner, app.path)

    # This is the actual AT-SPI Socket.Embed registration.  No Cache interface is
    # exported anywhere, so Cache.GetItems receives the bus's native UnknownMethod.
    registry = dbus.Interface(bus.get_object("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root"),
                              A11Y + ".Socket")
    registry.Embed((owner, app.path))
    gtk_window = Gtk.Window(title="Spectre real AT-SPI cache-less QA")
    gtk_window.set_default_size(480, 260); gtk_window.move(40, 50); gtk_window.show_all()
    args.ready.write_text(json.dumps({"pid": os.getpid(), "owner": owner, "application": app.path,
                                      "window": window.path, "lateMode": args.late_mode}) + "\n")
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()

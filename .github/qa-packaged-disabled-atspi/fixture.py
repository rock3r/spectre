#!/usr/bin/python3
"""Real GTK target/decoy used by the private-bus packaged smoke."""
import argparse
import json
import os
from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk  # noqa: E402

parser = argparse.ArgumentParser()
parser.add_argument("--title", required=True)
parser.add_argument("--marker", required=True)
parser.add_argument("--ready", type=Path, required=True)
args = parser.parse_args()

window = Gtk.Window(title=args.title)
window.connect("destroy", Gtk.main_quit)
box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
box.add(Gtk.Label(label=f"{args.title} live accessible fixture"))
box.add(Gtk.Button(label=args.marker))
window.add(box)
window.set_default_size(420, 180)
window.show_all()
args.ready.write_text(
    json.dumps({"pid": os.getpid(), "title": args.title, "marker": args.marker}) + "\n"
)
Gtk.main()

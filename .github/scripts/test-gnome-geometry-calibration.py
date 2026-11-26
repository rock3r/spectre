#!/usr/bin/env python3
"""Focused contracts for GNOME geometry X11 client selection."""
import ast
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("gnome-geometry-calibration.py")


def load_selector(artifacts):
    module = ast.parse(SCRIPT.read_text())
    function = next(node for node in module.body
                    if isinstance(node, ast.FunctionDef) and node.name == "select_x11_client")
    namespace = {"A": artifacts, "json": json, "re": re, "subprocess": subprocess}
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(SCRIPT), "exec"), namespace)
    return namespace["select_x11_client"]


class SelectX11ClientTest(unittest.TestCase):
    def test_duplicate_search_lines_validate_one_distinct_numeric_xid(self):
        with tempfile.TemporaryDirectory() as directory:
            selector = load_selector(Path(directory))
            props = ("_NET_FRAME_EXTENTS(CARDINAL) = 1, 1, 1, 1\n"
                     "_NET_WM_PID(CARDINAL) = 6747\n"
                     'WM_CLASS(STRING) = "GnomeGeometryFixture"\n'
                     "_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_NORMAL\n")
            with mock.patch.object(subprocess, "check_output", return_value="6291463\n6291463\n"), \
                 mock.patch.object(subprocess, "run", side_effect=lambda command, **_:
                     subprocess.CompletedProcess(command, 0, props if command[0] == "xprop" else "geometry")) as run:
                selected = selector(6747, "Geometry Fixture", "decorated")

            self.assertEqual(6291463, selected["xidNumeric"])
            self.assertEqual(3, run.call_count)
            evidence = json.loads((Path(directory) / "decorated-selection-search.json").read_text())
            self.assertEqual(["6291463", "6291463"], evidence["rawSearchResults"])
            self.assertEqual([6291463], evidence["distinctNumericXids"])


if __name__ == "__main__":
    unittest.main()

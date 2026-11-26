#!/usr/bin/env python3
"""Regression coverage for the persisted native GNOME portal structures."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location(
    "portal_semantics", ROOT / "gnome_portal_dialog_semantics.py")
semantics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(semantics)


def fact(role, name, states=("sensitive", "visible"), actions=(), **extra):
    return {"role": role, "name": name, "states": list(states),
            "actions": list(actions), **extra}


class PortalDialogSemanticsTest(unittest.TestCase):
    def setUp(self):
        fixture = ROOT / "fixtures/gnome-42.1-portal-reduced-tree.json"
        self.jammy = json.loads(fixture.read_text())["nodes"]

    def test_gnome_42_native_tree_selects_exact_controls(self):
        selected = semantics.selection(self.jammy)
        self.assertEqual({"profile": "gnome-42", "share": 36,
                          "interaction": 23, "source": 11}, selected)
        self.assertEqual([], self.jammy[2]["actions"])

        after_toggle = copy.deepcopy(self.jammy)
        after_toggle[7]["states"].append("checked")
        self.assertIn("visible", after_toggle[7]["states"])
        self.assertEqual(selected, semantics.selection(after_toggle))

    def test_gnome_42_rejects_extra_or_mismatched_controls(self):
        extra = copy.deepcopy(self.jammy)
        extra.append(fact("list item", "Other monitor", states=("sensitive", "visible"),
                          index=40, parent=10))
        self.assertIsNone(semantics.selection(extra))
        wrong_share = copy.deepcopy(self.jammy)
        wrong_share[10]["actions"].append("press")
        self.assertIsNone(semantics.selection(wrong_share))

    def test_gnome_46_prior_native_shape_is_retained(self):
        nodes = [fact("push button", "Share", actions=["click"]),
                 fact("check box", "Allow Remote Interaction", states=("checked", "sensitive", "visible"),
                      actions=["toggle"]),
                 fact("toggle button", "1", states=("checked", "sensitive", "visible"),
                      actions=["click"])]
        self.assertTrue(semantics.is_dialog("frame", "Remote Desktop"))
        self.assertEqual("gnome-46", semantics.profile(nodes))

    def test_unrelated_or_incomplete_dialog_is_rejected(self):
        unrelated = [fact("push button", "Share", actions=["click"]),
                     fact("check box", "Share audio", states=("checked", "sensitive", "visible")),
                     fact("toggle button", "1", states=("checked",), actions=["click"])]
        self.assertIsNone(semantics.profile(unrelated))
        self.assertFalse(semantics.is_dialog("dialog", "Screen Sharing"))


if __name__ == "__main__":
    unittest.main()

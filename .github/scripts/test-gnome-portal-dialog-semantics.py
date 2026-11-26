#!/usr/bin/env python3
"""Regression coverage for the two real, versioned GNOME portal structures."""
import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).with_name("gnome_portal_dialog_semantics.py")
SPEC = importlib.util.spec_from_file_location("portal_semantics", PATH)
semantics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(semantics)


def fact(role, name, states=(), actions=()):
    return {"role": role, "name": name, "states": list(states), "actions": list(actions)}


class PortalDialogSemanticsTest(unittest.TestCase):
    def test_gnome_42_real_structure_and_case_folding(self):
        nodes = [fact("push button", "Share", actions=["click"]),
                 fact("switch", "Allow remote interaction", states=["checked"], actions=["toggle"]),
                 fact("list item", "Virtual-1 (1280 × 720)", states=["selected"],
                      actions=["activate"])]
        self.assertTrue(semantics.is_dialog("dialog", "Remote desktop"))
        self.assertEqual("gnome-42", semantics.profile(nodes))

    def test_gnome_42_separate_action_row_label_is_supported(self):
        nodes = [fact("push button", "Share", actions=["click"]),
                 fact("label", "Allow remote interaction"),
                 fact("switch", "", states=["checked"], actions=["toggle"]),
                 fact("list box row", "Virtual-1 (1280 × 720)", states=["selected"],
                      actions=["activate"])]
        self.assertEqual("gnome-42", semantics.profile(nodes))

    def test_gnome_46_real_structure_is_unchanged(self):
        nodes = [fact("push button", "Share", actions=["click"]),
                 fact("check box", "Allow Remote Interaction", states=["checked"], actions=["toggle"]),
                 fact("toggle button", "1", states=["checked"], actions=["click"])]
        self.assertTrue(semantics.is_dialog("frame", "Remote Desktop"))
        self.assertEqual("gnome-46", semantics.profile(nodes))

    def test_unrelated_or_incomplete_dialog_is_rejected(self):
        unrelated = [fact("push button", "Share", actions=["click"]),
                     fact("check box", "Share audio", states=["checked"]),
                     fact("toggle button", "1", states=["checked"], actions=["click"])]
        self.assertIsNone(semantics.profile(unrelated))
        self.assertFalse(semantics.is_dialog("dialog", "Screen Sharing"))


if __name__ == "__main__":
    unittest.main()

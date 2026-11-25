import importlib.util
import json
import pathlib
import sys
import types
import unittest

ROOT = pathlib.Path(__file__).parents[1]
FIXTURE = ROOT / "fixtures" / "accesskit"


def load_linux_reader():
    sys.modules["pyatspi"] = types.ModuleType("pyatspi")
    reader = FIXTURE / "readers" / "linux.py"
    spec = importlib.util.spec_from_file_location("accesskit_linux_reader", reader)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LinuxNode:
    def __init__(self, role_id, role_name):
        self.role_id = role_id
        self.role_name = role_name

    def getRole(self):
        return self.role_id

    def getRoleName(self):
        return self.role_name

class AccessKitFixtureContractTest(unittest.TestCase):
    def test_fixture_is_private_and_dependencies_are_pinned(self):
        manifest = (FIXTURE / "Cargo.toml").read_text()
        self.assertIn("publish = false", manifest)
        self.assertIn('accesskit = "=0.25.0"', manifest)
        self.assertNotIn('include(":accesskit', (ROOT / "settings.gradle.kts").read_text())

    def test_every_platform_golden_has_the_reader_contract(self):
        keys = {"windowRole", "buttonRole", "checkboxRole", "textRole", "textValue", "popoverRole", "checkedState", "disabledState"}
        values = {name: json.loads((FIXTURE / "golden" / f"{name}.json").read_text()) for name in ("linux", "windows", "macos")}
        for name, value in values.items(): self.assertEqual(keys, set(value), name)
        self.assertIsNone(values["linux"]["textValue"])
        self.assertEqual(
            {
                "windowRole": {"id": 23, "name": "frame"},
                "buttonRole": {"id": 43, "name": "button"},
                "checkboxRole": {"id": 7, "name": "check box"},
                "textRole": {"id": 79, "name": "entry"},
                "popoverRole": {"id": 16, "name": "dialog"},
            },
            {key: values["linux"][key] for key in keys if key.endswith("Role")},
        )
        self.assertEqual("Ada Lovelace", values["windows"]["textValue"])
        self.assertEqual("Ada Lovelace", values["macos"]["textValue"])

    def test_linux_lane_exercises_enabled_and_refusal_cells(self):
        runner = (FIXTURE / "run-linux.sh").read_text()
        self.assertIn("--enable", runner)
        self.assertIn("accessibility-disabled", runner)
        self.assertGreaterEqual(runner.count("set_enabled false"), 2)
        self.assertIn("reader did not restore org.a11y.Status.IsEnabled=false", runner)
        self.assertIn("refusal path changed org.a11y.Status.IsEnabled", runner)

    def test_native_identifiers_and_duplicate_parent_contract(self):
        source = (FIXTURE / "src" / "lib.rs").read_text()
        reader = (FIXTURE / "readers" / "macos.swift").read_text()
        self.assertIn("node.set_author_id(id)", source)
        self.assertIn('child.author_id() == Some("repeated")', source)
        self.assertIn("CFGetTypeID(value) == AXUIElementGetTypeID()", reader)
        self.assertIn("CFEqual($0, parent)", reader)
        self.assertNotIn("CFHash", reader)
        self.assertIn("kAXSubroleAttribute", reader)

    def test_linux_roles_use_wire_ids_and_report_names_and_ids(self):
        reader = (FIXTURE / "readers" / "linux.py").read_text()
        self.assertIn("node.getRole()", reader)
        self.assertNotIn("getRoleName() ==", reader)
        self.assertIn("expected", reader)
        self.assertIn("observed", reader)

    def test_old_push_button_name_has_stable_button_identity(self):
        load_linux_reader().assert_role(
            LinuxNode(43, "push button"), {"id": 43, "name": "button"}, "button"
        )

    def test_new_button_name_has_stable_button_identity(self):
        load_linux_reader().assert_role(
            LinuxNode(43, "button"), {"id": 43, "name": "button"}, "button"
        )

    def test_role_mismatch_reports_expected_and_observed_names_and_ids(self):
        with self.assertRaisesRegex(
            AssertionError,
            r"expected 'button' \(id 43\), observed 'check box' \(id 7\)",
        ):
            load_linux_reader().assert_role(
                LinuxNode(7, "check box"), {"id": 43, "name": "button"}, "button"
            )

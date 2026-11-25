import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).parents[1]
FIXTURE = ROOT / "fixtures" / "accesskit"

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

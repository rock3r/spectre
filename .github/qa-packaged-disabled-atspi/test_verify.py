#!/usr/bin/env python3
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("verify.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def transition(enabled):
    calls = ('method call time=1 path=/org/a11y/bus; interface=org.freedesktop.DBus.Properties; member=Set\n'
             '   string "org.a11y.Status"\n   string "IsEnabled"\n   variant       boolean true\n'
             'method call time=2 path=/org/a11y/bus; interface=org.freedesktop.DBus.Properties; member=Set\n'
             '   string "org.a11y.Status"\n   string "IsEnabled"\n   variant       boolean false\n') if enabled else ""
    flag = {"enabled": False, "command": {"returnCode": 0, "stdout": "(<false>,)"}}
    return {"flagBefore": flag, "flagAfter": flag, "dbusMonitor": {"stdout": calls}}


def tree():
    return {"schemaVersion": 2, "source": "os-accessibility", "capturedAt": "2026-11-26T00:00:00Z",
            "windows": [{"key": "w", "title": "target"}], "notes": [verify.NOTE],
            "nodes": [{"key": "w", "parentKey": None, "role": "window", "name": "target"},
                      {"key": "b", "parentKey": "w", "role": "button", "name": "wanted"}]}


class OracleTest(unittest.TestCase):
    def test_strict_dbus_boolean(self):
        original = verify.compact_process
        try:
            verify.compact_process = lambda *a, **k: {"returnCode": 0, "stdout": "(<'maybe'>,)", "stderr": ""}
            with self.assertRaisesRegex(AssertionError, "unknown/nonboolean"):
                verify.accessibility_flag()
            verify.compact_process = lambda *a, **k: {"returnCode": 1, "stdout": "", "stderr": "boom"}
            with self.assertRaisesRegex(AssertionError, "command failed"):
                verify.accessibility_flag()
        finally:
            verify.compact_process = original

    def test_real_cli_refusal_shape(self):
        result = {"returnCode": 5, "stdout": "", "stderr":
                  "Spectre daemon error: AccessibilityDisabled: Linux accessibility is disabled\n"
                  "Hint: run `spectre guide troubleshooting` for daemon and environment checks.\n",
                  **transition(False)}
        verify.assert_cli_disabled(result, "CLI")
        for mutation in (dict(result, returnCode=1), dict(result, stdout="{}\n"),
                         dict(result, stderr="Spectre daemon error: NoAccessibilityTree: failed\n")):
            with self.assertRaises(AssertionError):
                verify.assert_cli_disabled(mutation, "CLI")

    def test_real_mcp_refusal_shape(self):
        payload = {"code": verify.DISABLED, "message": "Linux accessibility is disabled",
                   "partialSnapshot": None}
        response = {"result": {"isError": True, "content": [{"type": "text", "text": json.dumps(payload)}]}}
        self.assertEqual(verify.mcp_payload(response, "MCP"), payload)
        verify.assert_disabled_payload(payload, "MCP")
        for bad in ({"code": verify.DISABLED, "partialSnapshot": {}},
                    {"code": verify.DISABLED, "tree": {}},
                    {"code": "prefix-accessibility-disabled"}):
            with self.assertRaises(AssertionError):
                verify.assert_disabled_payload(bad, "MCP")

    def test_each_transition_treatment(self):
        verify.assert_transition(transition(False), False, "omitted")
        verify.assert_transition(transition(True), True, "true")
        with self.assertRaises(AssertionError):
            verify.assert_transition(transition(True), False, "bad refusal")
        with self.assertRaises(AssertionError):
            verify.assert_transition(transition(False), True, "bad positive")

    def test_canonical_tree_has_no_invented_pid(self):
        result = verify.assert_tree(tree(), 12, "target", "wanted", "decoy", "unwanted", "mock")
        self.assertEqual(result["requestedPid"], 12)
        self.assertNotIn("pid", tree())
        for mutation in (dict(tree(), notes=[]), dict(tree(), source="compose-semantics")):
            with self.assertRaises(AssertionError):
                verify.assert_tree(mutation, 12, "target", "wanted", "decoy", "unwanted", "mock")

    def test_mcp_content_must_be_json_inside_text(self):
        response = {"result": {"isError": True, "content": [{"type": "text", "text": "not json"}]}}
        with self.assertRaises(json.JSONDecodeError):
            verify.mcp_payload(response, "mock")


if __name__ == "__main__":
    unittest.main()

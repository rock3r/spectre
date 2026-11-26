#!/usr/bin/env python3
"""Regression test for the depth-prefix verifier's native reply capture."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest


sys.modules.setdefault("dbus", types.SimpleNamespace(DBusException=Exception))
SCRIPT = Path(__file__).with_name("verify-atspi-depth-prefix.py")
SPEC = importlib.util.spec_from_file_location("verify_atspi_depth_prefix", SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class NativeReplyCaptureTest(unittest.TestCase):
    def test_large_reply_does_not_deadlock(self):
        with tempfile.TemporaryDirectory() as directory:
            artifacts = Path(directory)
            helper = artifacts / "fake-helper.py"
            helper.write_text(
                "#!/usr/bin/env python3\n"
                "import json, sys\n"
                "sys.stdin.read()\n"
                "print(json.dumps({'ok': True, 'padding': 'x' * (1024 * 1024)}))\n"
                "print('fake diagnostic', file=sys.stderr)\n"
            )
            helper.chmod(0o755)

            result = VERIFIER.run_helper(
                "large-fake", helper, "{}", artifacts, lambda _pid: {":1.42"}, timeout=5
            )

            self.assertEqual(result["returncode"], 0)
            self.assertEqual(result["names"], {":1.42"})
            self.assertGreater(result["stdout"].stat().st_size, 1024 * 1024)
            self.assertEqual(json.loads(result["stdout"].read_text())["ok"], True)
            self.assertEqual(result["stderr"].read_text(), "fake diagnostic\n")


if __name__ == "__main__":
    unittest.main()

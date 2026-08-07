#!/usr/bin/env python3
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MCP_SMOKE = ROOT / "scripts" / "mcp-stdio-smoke.py"
RELEASE_SMOKE = ROOT / "scripts" / "release-smoke.py"


class McpStdioSmokeTest(unittest.TestCase):
    def fake_server(self, *, banner: bool = False, version: str = "0.5.0") -> Path:
        directory = Path(tempfile.mkdtemp())
        script = directory / "fake.py"
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\n"
            f"banner={banner!r}; version={version!r}\n"
            "if banner: print('not-json', flush=True)\n"
            "for line in sys.stdin:\n"
            " r=json.loads(line); method=r.get('method')\n"
            " if method=='initialize': result={'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'spectre','version':version}}\n"
            " elif method=='tools/list': result={'tools':[{'name':'list_processes'}]}\n"
            " elif method=='tools/call': result={'content':[{'type':'text','text':'ok'}]}\n"
            " else: continue\n"
            " print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':result}), flush=True)\n"
        )
        script.chmod(0o755)
        return script

    def run_smoke(self, server: Path):
        # Launch the fake server via the same interpreter: Windows cannot exec a
        # shebang-only .py (WinError 193). Production smoke still passes a real
        # packaged spectre binary as the command.
        return subprocess.run(
            [
                sys.executable,
                str(MCP_SMOKE),
                "--expected-version",
                "0.5.0",
                "--",
                sys.executable,
                str(server),
            ],
            text=True,
            capture_output=True,
            timeout=10,
        )

    def test_clean_server_passes(self):
        result = self.run_smoke(self.fake_server())
        self.assertEqual(0, result.returncode, result.stderr)

    def test_stdout_pollution_fails(self):
        result = self.run_smoke(self.fake_server(banner=True))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("non-JSON stdout", result.stderr)

    def test_wrong_version_fails(self):
        result = self.run_smoke(self.fake_server(version="0.1.0"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("server version", result.stderr)


if __name__ == "__main__":
    unittest.main()

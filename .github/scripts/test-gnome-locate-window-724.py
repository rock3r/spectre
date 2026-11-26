#!/usr/bin/env python3
"""Static fail-closed contracts for the temporary #724 hosted QA cell."""
import ast
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LocateWindow724ContractTest(unittest.TestCase):
    def test_single_scoped_noble_job_and_exact_candidate(self):
        workflow = (ROOT / ".github/workflows/qa-724-locate-window.yml").read_text()
        for value in (
            "runs-on: ubuntu-24.04",
            "release-0.10/gnome-hosted-smoke",
            "e963afeb01991600891e8fb7bed5d0df6d860dbf",
            "recording/native/linux/src/main.rs",
            "contents: read",
            "persist-credentials: false",
        ):
            self.assertIn(value, workflow)
        self.assertEqual(workflow.count("runs-on:"), 1)

    def test_probe_owns_window_and_child_and_redacts_success_token(self):
        probe = (ROOT / ".github/scripts/gnome-locate-window-724.py").read_text()
        for value in (
            "_NET_WM_PID",
            "_NET_WM_WINDOW_TYPE_NORMAL",
            'run("xwininfo", "-id", hex(xid))',
            'run("xprop", "-id", hex(xid)',
            '"owner_pid": os.getpid()',
            "subprocess.Popen([helper]",
            "child.communicate(payload",
            'len(parsed) != 1',
            'event.get("kind") != "Helper"',
            'parsed[0].pop("binding_token", None)',
            '"bindingTokenLength": len(token)',
        ):
            self.assertIn(value, probe)
        self.assertNotIn('"binding_token": token', probe)

    def test_probe_waits_for_native_stable_same_window_before_every_request(self):
        probe = (ROOT / ".github/scripts/gnome-locate-window-724.py").read_text()
        for value in (
            '"Map State: IsViewable"',
            '"_NET_CLIENT_LIST"',
            'STABLE_PROBES = 3',
            '"requestedSize"',
            '"provider"',
            '"timeoutSeconds"',
            '"OverviewActive"',
            'stable_x11(xid,',
            'frame = stable_x11(xid,',
            'require_identity_error(stderr.decode',
            'require_identity_error(event.get("message", "")',
            '["gnome-extensions", "disable", EXTENSION]',
            '["gnome-extensions", "enable", EXTENSION]',
        ):
            self.assertIn(value, probe)

    @staticmethod
    def _probe_contracts():
        source = (ROOT / ".github/scripts/gnome-locate-window-724.py").read_text()
        tree = ast.parse(source)
        wanted = {"BUS", "INSTALL_GUIDANCE", "require_identity_error", "parse_frame"}
        nodes = []
        for node in tree.body:
            if isinstance(node, ast.Import) and any(alias.name == "re" for alias in node.names):
                nodes.append(node)
            elif isinstance(node, (ast.Assign, ast.AnnAssign)):
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                if any(isinstance(target, ast.Name) and target.id in wanted for target in targets):
                    nodes.append(node)
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in wanted:
                nodes.append(node)
        namespace = {}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "probe-contracts", "exec"), namespace)
        return namespace

    def test_observed_native_error_literal_is_accepted_without_extension_uuid_suffix(self):
        contracts = self._probe_contracts()
        observed = (
            "GNOME window identity service rejected the binding for PID 7756: "
            "The name dev.sebastiano.Spectre.WindowIdentity was not provided by any "
            ".service files. Install and enable the bundled spectre-window-identity "
            "GNOME Shell extension."
        )
        self.assertNotIn("@sebastiano.dev", observed)
        contracts["require_identity_error"](observed, "observed fixture")
        with self.assertRaisesRegex(RuntimeError, "identity service/install-enable guidance"):
            contracts["require_identity_error"](
                observed.replace("Install and enable", "Enable"), "wrong guidance")

    def test_wrong_native_frame_has_concrete_diagnostic(self):
        parse_frame = self._probe_contracts()["parse_frame"]
        wrong_frame = """Absolute upper-left X: 180
Absolute upper-left Y: 160
Width: 360
"""
        with self.assertRaisesRegex(RuntimeError, "missing Height in xwininfo native frame"):
            parse_frame(wrong_frame)

    def test_session_uses_packaged_helpers_then_restores_public_flow(self):
        session = (ROOT / ".github/scripts/gnome-hosted-smoke-session.sh").read_text()
        start = session.index('SMOKE_MODE" == locate-window-724')
        block = session[start:session.index('SMOKE_MODE" == cli-recording-live', start)]
        for value in (
            ":recording-linux:jar",
            'jar" xf',
            "chmod 0755",
            "sha256sum",
            "gnome-locate-window-724.py",
            "gnome-extensions list --enabled",
            "gdbus wait --session dev.sebastiano.Spectre.WindowIdentity",
            ":cli:shadowDistZip",
            "gnome-cli-recording-live.py",
        ):
            self.assertIn(value, block)
        self.assertLess(block.index("gnome-locate-window-724.py"),
                        block.index("gnome-cli-recording-live.py"))


if __name__ == "__main__":
    unittest.main()

import base64
import contextlib
import io
import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "central_portal_check.py"
SPEC = importlib.util.spec_from_file_location("central_portal_check", SCRIPT)
central = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = central
SPEC.loader.exec_module(central)


class CentralPortalCheckTest(unittest.TestCase):
    def test_extracts_bearer_token_from_one_password_item(self):
        item = {
            "fields": [
                {"label": "username", "value": "token-user"},
                {"label": "password", "value": "token-password"},
            ]
        }

        token = central.bearer_token_from_item(item)

        self.assertEqual(
            token,
            base64.b64encode(b"token-user:token-password").decode("ascii"),
        )

    def test_prefers_username_password_over_precomputed_base64_token_field(self):
        item = {
            "fields": [
                {"label": "username", "value": "token-user"},
                {"label": "password", "value": "token-password"},
                {"label": "username:password (base64)", "value": "stale-base64"},
            ]
        }

        self.assertEqual(
            central.bearer_token_from_item(item),
            base64.b64encode(b"token-user:token-password").decode("ascii"),
        )

    def test_uses_precomputed_base64_token_when_username_password_are_absent(self):
        item = {
            "fields": [
                {"label": "username:password (base64)", "value": "already-base64"},
            ]
        }

        self.assertEqual(central.bearer_token_from_item(item), "already-base64")

    def test_expected_files_include_artifacts_and_signatures_for_every_component(self):
        files = central.expected_files_for_version("0.2.0")

        self.assertIn(
            "dev/sebastiano/spectre/spectre-core/0.2.0/spectre-core-0.2.0.jar",
            files,
        )
        self.assertIn(
            "dev/sebastiano/spectre/spectre-core/0.2.0/spectre-core-0.2.0.jar.asc",
            files,
        )
        self.assertIn(
            "dev/sebastiano/spectre/spectre-agent-runtime/0.2.0/"
            "spectre-agent-runtime-0.2.0.module",
            files,
        )
        self.assertIn(
            "dev/sebastiano/spectre/spectre-core/0.2.0/"
            "spectre-core-0.2.0.jar.sha512",
            files,
        )
        self.assertIn(
            "dev/sebastiano/spectre/spectre-core/0.2.0/"
            "spectre-core-0.2.0.jar.asc.sha512",
            files,
        )
        self.assertIn(
            "dev/sebastiano/spectre/spectre-input-coordinator/0.2.0/"
            "spectre-input-coordinator-0.2.0.jar",
            files,
        )
        self.assertIn(
            "dev/sebastiano/spectre/spectre-input-coordinator-server/0.2.0/"
            "spectre-input-coordinator-server-0.2.0.pom.asc.sha512",
            files,
        )
        self.assertEqual(len(files), 550)

    def test_agent_runtime_manifest_validation(self):
        jar = self._jar(
            {
                "META-INF/MANIFEST.MF": (
                    "Manifest-Version: 1.0\n"
                    "Agent-Class: dev.sebastiano.spectre.agent.runtime.SpectreAgent\n"
                    "Premain-Class: dev.sebastiano.spectre.agent.runtime.SpectreAgent\n"
                    "\n"
                ),
                "dev/sebastiano/spectre/agent/runtime/SpectreAgent.class": b"class",
                "META-INF/spectre/inject-runtime.jar": b"nested-inject",
            }
        )

        errors = central.validate_jar("spectre-agent-runtime", jar)

        self.assertEqual(errors, [])

    def test_agent_runtime_rejects_forbidden_payloads(self):
        jar = self._jar(
            {
                "META-INF/MANIFEST.MF": (
                    "Manifest-Version: 1.0\n"
                    "Agent-Class: dev.sebastiano.spectre.agent.runtime.SpectreAgent\n"
                    "Premain-Class: dev.sebastiano.spectre.agent.runtime.SpectreAgent\n"
                    "\n"
                ),
                "META-INF/spectre/inject-runtime.jar": b"nested-inject",
                "kotlinx/coroutines/Job.class": b"class",
            }
        )

        errors = central.validate_jar("spectre-agent-runtime", jar)

        self.assertIn("forbidden classes", "\n".join(errors))

    def test_agent_runtime_requires_nested_inject_runtime_jar(self):
        jar = self._jar(
            {
                "META-INF/MANIFEST.MF": (
                    "Manifest-Version: 1.0\n"
                    "Agent-Class: dev.sebastiano.spectre.agent.runtime.SpectreAgent\n"
                    "Premain-Class: dev.sebastiano.spectre.agent.runtime.SpectreAgent\n"
                    "\n"
                ),
                "dev/sebastiano/spectre/agent/runtime/SpectreAgent.class": b"class",
            }
        )

        errors = central.validate_jar("spectre-agent-runtime", jar)

        self.assertIn("inject-runtime.jar", "\n".join(errors))

    def test_recording_helper_validation_linux(self):
        jar = self._jar(
            {
                "native/linux/x86_64/spectre-wayland-helper": b"x64",
                "native/linux/aarch64/spectre-wayland-helper": b"arm64",
            }
        )

        errors = central.validate_jar("spectre-recording-linux", jar)

        self.assertEqual(errors, [])

    def test_recording_helper_validation_macos_app_bundle(self):
        entries = {path: b"helper" for path in central.MACOS_HELPER_APP_ENTRIES}
        jar = self._jar(entries)

        errors = central.validate_jar("spectre-recording-macos", jar)

        self.assertEqual(errors, [])

    def test_recording_helper_rejects_bare_macos_executable(self):
        entries = {path: b"helper" for path in central.MACOS_HELPER_APP_ENTRIES}
        entries[central.MACOS_HELPER_BARE_EXECUTABLE] = b"legacy"
        jar = self._jar(entries)

        errors = central.validate_jar("spectre-recording-macos", jar)

        self.assertIn("bare", "\n".join(errors))
        self.assertIn("SpectreCaptureHelper.app", "\n".join(errors))

    def test_recording_helper_validation_windows_multi_file(self):
        entries = {
            path: b"helper" for path in central.HELPER_ENTRIES["spectre-recording-windows"]
        }
        jar = self._jar(entries)

        errors = central.validate_jar("spectre-recording-windows", jar)

        self.assertEqual(errors, [])

    def test_recording_helper_rejects_windows_exe_only(self):
        jar = self._jar(
            {
                "native/windows/x64/spectre-window-capture.exe": b"x64",
                "native/windows/arm64/spectre-window-capture.exe": b"arm64",
            }
        )

        errors = central.validate_jar("spectre-recording-windows", jar)
        joined = "\n".join(errors)

        self.assertIn("SpectreWindowCapture.deps.json", joined)
        self.assertIn("SpectreWindowCapture.dll", joined)
        self.assertGreater(len(errors), 2)

    def test_helper_entries_match_app_bundle_and_wgc_contract(self):
        self.assertEqual(
            central.HELPER_ENTRIES["spectre-recording-macos"],
            central.MACOS_HELPER_APP_ENTRIES,
        )
        self.assertIn(
            "native/macos/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
            central.HELPER_ENTRIES["spectre-recording-macos"],
        )
        self.assertNotIn(
            "native/macos/spectre-screencapture",
            central.HELPER_ENTRIES["spectre-recording-macos"],
        )
        windows = central.HELPER_ENTRIES["spectre-recording-windows"]
        self.assertEqual(
            len(windows),
            len(central.WINDOWS_HELPER_ARCHES)
            * len(central.WINDOWS_HELPER_REQUIRED_BASE_NAMES),
        )
        self.assertIn(
            "native/windows/x64/SpectreWindowCapture.deps.json",
            windows,
        )
        self.assertIn(
            "native/windows/arm64/Microsoft.WindowsAppRuntime.Bootstrap.dll",
            windows,
        )

    def test_agent_rejects_spike_class(self):
        jar = self._jar({"dev/sebastiano/spectre/agent/spike/AttachSpike.class": b"class"})

        errors = central.validate_jar("spectre-agent", jar)

        self.assertIn("AttachSpike", "\n".join(errors))

    def test_input_coordinator_artifacts_require_representative_classes(self):
        required_entries = {
            "spectre-input-coordinator": (
                "dev/sebastiano/spectre/input/LocalInputCoordinatorClient.class"
            ),
            "spectre-input-coordinator-server": (
                "dev/sebastiano/spectre/input/server/LocalCoordinatorServer.class"
            ),
        }

        for component, required_entry in required_entries.items():
            with self.subTest(component=component, payload="missing"):
                jar = self._jar({"META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n\n"})

                errors = central.validate_jar(component, jar)

                self.assertIn(required_entry, "\n".join(errors))

            with self.subTest(component=component, payload="present"):
                jar = self._jar({required_entry: b"class"})

                errors = central.validate_jar(component, jar)

                self.assertEqual(errors, [])

    def test_print_files_lists_actual_central_file_listing(self):
        expected_path = (
            "dev/sebastiano/spectre/spectre-core/0.2.0/"
            "spectre-core-0.2.0.pom"
        )

        def fake_browse(base_url, token, deployment_id):
            self.assertEqual(base_url, "https://example.test")
            self.assertEqual(token, "token")
            self.assertEqual(deployment_id, "deployment")
            return {expected_path: 42}

        original_browse = central.browse_deployment_files
        original_expected = central.expected_files_for_version
        central.browse_deployment_files = fake_browse
        central.expected_files_for_version = lambda version: [expected_path]
        stdout = io.StringIO()
        try:
            with contextlib.redirect_stdout(stdout):
                result = central.print_files(
                    "https://example.test",
                    "token",
                    "deployment",
                    "0.2.0",
                )
        finally:
            central.browse_deployment_files = original_browse
            central.expected_files_for_version = original_expected

        self.assertEqual(result, 0)
        self.assertIn(f"42  {expected_path}", stdout.getvalue())
        self.assertIn("1 files listed by Central.", stdout.getvalue())

    @staticmethod
    def _jar(entries):
        handle = tempfile.NamedTemporaryFile(suffix=".jar", delete=False)
        handle.close()
        with zipfile.ZipFile(handle.name, "w") as jar:
            for name, content in entries.items():
                data = content if isinstance(content, bytes) else content.encode("utf-8")
                jar.writestr(name, data)
        return Path(handle.name)


if __name__ == "__main__":
    unittest.main()

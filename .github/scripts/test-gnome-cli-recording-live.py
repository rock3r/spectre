#!/usr/bin/env python3
"""Structural contracts for the packaged CLI/public crop verification cell."""
import ast
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/gnome-hosted-smoke.yml").read_text()
SESSION = (ROOT / ".github/scripts/gnome-hosted-smoke-session.sh").read_text()
DRIVER = (ROOT / ".github/scripts/gnome-cli-recording-live.py").read_text()
CROP = (ROOT / ".github/scripts/gnome-public-window-crop.py").read_text()
REGION_DRIVER = (ROOT / ".github/scripts/gnome-cli-region-stills.py").read_text()
REGION_FIXTURE = (ROOT / ".github/verification/wayland-cli-region-stills/PublicWaylandRegionStills.kt").read_text()
REGION_BUILD = (ROOT / ".github/verification/wayland-cli-region-stills/build.init.gradle").read_text()


def load_permission_verifier():
    """Load the pure verifier without importing native GNOME dependencies."""
    tree = ast.parse(DRIVER)
    function = next(node for node in tree.body
                    if isinstance(node, ast.FunctionDef) and node.name == "verify_permissions")
    module = ast.Module(body=[function], type_ignores=[])
    namespace = {"RuntimeError": RuntimeError, "dict": dict, "bool": bool, "int": int}
    exec(compile(ast.fix_missing_locations(module), "gnome-cli-recording-live.py", "exec"),
         namespace)
    return namespace["verify_permissions"]


VERIFY_PERMISSIONS = load_permission_verifier()


def load_interface_version():
    """Load the version reader with a minimal fake dbus module."""
    tree = ast.parse(DRIVER)
    function = next(node for node in tree.body
                    if isinstance(node, ast.FunctionDef) and node.name == "interface_version")

    class FakeDbus:
        class DBusException(Exception):
            pass

    namespace = {"dbus": FakeDbus, "int": int}
    exec(compile(ast.fix_missing_locations(ast.Module(body=[function], type_ignores=[])),
                 "gnome-cli-recording-live.py", "exec"), namespace)
    return namespace["interface_version"], FakeDbus


INTERFACE_VERSION, FAKE_DBUS = load_interface_version()


class FakeProperties:
    def __init__(self, value=None, error=None):
        self.value = value
        self.error = error

    def Get(self, interface, property_name, *, dbus_interface):
        if self.error:
            raise self.error
        return self.value


class FakeBus:
    def __init__(self, properties):
        self.properties = properties

    def get_object(self, bus_name, path):
        return self.properties[bus_name]


def permission_result(*, stored):
    return {
        "granted": True,
        "status": "granted",
        "sessionActive": True,
        "sessionSocket": "/run/user/1001/spectre/wayland-session.sock",
        "tokenStored": stored,
        "tokenPath": "/tmp/wayland-rd-restore-token-rd-monitor-embedded",
    }


class CliRecordingLiveContractTest(unittest.TestCase):
    def test_optional_backend_zero_is_distinct_from_public_v1_and_missing(self):
        bus = FakeBus({
            "public": FakeProperties(1),
            "backend": FakeProperties(0),
            "missing": FakeProperties(error=FAKE_DBUS.DBusException("absent")),
        })
        self.assertEqual(1, INTERFACE_VERSION(bus, "public", "public.RemoteDesktop"))
        self.assertEqual(0, INTERFACE_VERSION(
            bus, "backend", "impl.RemoteDesktop", optional=True))
        self.assertIsNone(INTERFACE_VERSION(
            bus, "missing", "impl.RemoteDesktop", optional=True))
        with self.assertRaises(RuntimeError):
            INTERFACE_VERSION(bus, "backend", "public.RemoteDesktop")

    def test_native_jammy_v1_accepts_real_session_without_restore_marker(self):
        result = permission_result(stored=False)
        self.assertFalse(VERIFY_PERMISSIONS(result, result.copy(), 1, False, False))

    def test_native_noble_v2_requires_real_restore_marker(self):
        result = permission_result(stored=True)
        self.assertTrue(VERIFY_PERMISSIONS(result, result.copy(), 2, True, True))

    def test_contradictory_token_flags_or_markers_are_rejected(self):
        stored = permission_result(stored=True)
        missing = permission_result(stored=False)
        cases = (
            (stored, stored.copy(), 2, False, False),
            (missing, missing.copy(), 2, False, False),
            (missing, missing.copy(), 1, True, True),
            (stored, missing, 2, True, True),
        )
        for values in cases:
            with self.subTest(values=values), self.assertRaises(RuntimeError):
                VERIFY_PERMISSIONS(*values)

    def test_grant_session_and_request_check_agreement_are_never_waived(self):
        baseline = permission_result(stored=False)
        for key, contradictory in (
            ("granted", False), ("status", "denied"), ("sessionActive", False),
            ("sessionSocket", "/run/user/1001/spectre/other.sock"),
            ("tokenPath", "/tmp/different-marker"),
        ):
            checked = baseline.copy()
            checked[key] = contradictory
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                VERIFY_PERMISSIONS(baseline, checked, 1, False, False)

    def test_cell_is_separate_read_only_and_pinned(self):
        self.assertIn("public-wayland-cli-recording-live:", WORKFLOW)
        self.assertIn("permissions:\n  contents: read", WORKFLOW)
        self.assertIn("sha256sum -c -", WORKFLOW)
        self.assertIn("6428fa41d2ff70aee2b405a111e8ae2bb411ee05cd691d1203efc8d4bb3c0bda", WORKFLOW)
        self.assertNotIn("git push", WORKFLOW)

    def test_packaged_cli_requests_once_then_checks_same_live_session(self):
        self.assertIn(":cli:shadowDistZip", SESSION)
        self.assertNotIn(":cli:installDist", SESSION)
        self.assertIn("SPECTRE_CLI_ZIP", SESSION)
        self.assertEqual(1, DRIVER.count('\"permissions\", \"request\", \"--json\"'))
        self.assertEqual(1, DRIVER.count('\"permissions\", \"check\", \"--json\"'))
        self.assertIn('\"version\", \"--json\"', DRIVER)
        self.assertIn("portal.accept_dialog", DRIVER)
        self.assertIn("socket.is_socket()", DRIVER)
        self.assertIn("tokenPath", DRIVER)
        self.assertIn("org.freedesktop.DBus.Properties", DRIVER)
        self.assertIn("org.freedesktop.portal.RemoteDesktop", DRIVER)
        self.assertIn("org.freedesktop.portal.ScreenCast", DRIVER)
        self.assertIn("org.freedesktop.impl.portal.RemoteDesktop", DRIVER)
        self.assertNotIn('\"--session\"', DRIVER)

    def test_exact_native_proven_crop_probe_is_called_with_inherited_environment(self):
        self.assertIn('with_name("gnome-public-window-crop.py")', DRIVER)
        self.assertIn("env = os.environ.copy()", DRIVER)
        self.assertIn('env["SPECTRE_WAYLAND_CONSENT_PROMPT"] = "never"', DRIVER)
        self.assertIn("env=env", DRIVER)
        self.assertIn("crop-proof.stdout", DRIVER)
        self.assertIn("crop-proof.stderr", DRIVER)
        self.assertNotIn("BlueDecoy", DRIVER)
        self.assertNotIn("runPublicWaylandCliRecordingLive", DRIVER)
        for fact in ("runPublicWaylandCropProof", "red-decorated", "green-undecorated",
                     "decodedFrameCount", "public-window-crop-evidence.json"):
            self.assertIn(fact, CROP)

    def test_observer_records_only_real_samples_and_tolerates_removed_nodes(self):
        self.assertIn('\"--observe\"', DRIVER)
        self.assertNotIn("threading", DRIVER)
        self.assertIn("def expected_disappearance", DRIVER)
        self.assertIn("portal.semantics.profile", DRIVER)
        self.assertIn('\"kind\": \"sample\"', DRIVER)
        self.assertIn('\"kind\": \"transient-removed-node\"', DRIVER)
        self.assertNotIn('\"pickerCount\": None', DRIVER)
        self.assertIn("minimum_observation_seconds", DRIVER)
        self.assertIn("mean_sampling_interval_seconds", DRIVER)
        self.assertIn("subsequent_picker_samples", DRIVER)
        self.assertIn("nonzero_runs == 1", DRIVER)

    def test_cli_evidence_is_written_before_crop_and_crop_failure_is_retained(self):
        evidence_write = DRIVER.index('\"cli-recording-live-evidence.json\"')
        crop_run = DRIVER.index('with_name("gnome-public-window-crop.py")')
        self.assertLess(evidence_write, crop_run)
        self.assertIn("cropExitCode", DRIVER)
        self.assertIn("portalObservations", DRIVER)
        self.assertIn("pickerCountAfterGrant", DRIVER)
        self.assertIn("packagedCliVersion", DRIVER)
        self.assertIn("documentedUnsupportedLinuxApi", DRIVER)
        self.assertIn("doesNotAssertRawPeerDenial", DRIVER)
        self.assertIn("portalInterfaceVersions", DRIVER)
        self.assertIn("restoreTokenExpectation", DRIVER)
        self.assertNotIn("read_bytes", DRIVER)

    def test_region_stills_settle_frames_and_preserve_client_contract(self):
        truncated_linux_comm = "spectre-waylan"
        self.assertNotIn("helper", truncated_linux_comm)
        self.assertEqual(2, REGION_FIXTURE.count("LinuxNativeScreenshotter()"))
        self.assertEqual(1, REGION_FIXTURE.count('"Escape"'))
        self.assertNotIn("Robot", REGION_FIXTURE.replace("not Robot", ""))
        self.assertIn("settleFrame", REGION_FIXTURE)
        self.assertIn("region-$phase-full-reference.png", REGION_FIXTURE)
        self.assertIn("reference.getRGB(request.x + x, request.y + y)", REGION_FIXTURE)
        self.assertNotIn("helperPids", REGION_FIXTURE)
        self.assertNotIn('listOf("ps", "-eo", "pid,ppid,comm")', REGION_FIXTURE)
        self.assertIn('System.getenv("SPECTRE_REGION_SESSION_SOCKET")', REGION_FIXTURE)
        self.assertIn('System.getenv("SPECTRE_REGION_SESSION_OWNER_PID")', REGION_FIXTURE)
        self.assertIn('ProcessBuilder("fuser", socket.toString())', REGION_FIXTURE)
        self.assertIn('"kind":"socket-owner"', REGION_FIXTURE)
        self.assertIn('env["SPECTRE_REGION_SESSION_SOCKET"] = str(socket)', DRIVER)
        self.assertIn('env["SPECTRE_REGION_SESSION_OWNER_PID"] = str(helper_pid)', DRIVER)
        self.assertIn("environment 'SPECTRE_REGION_SESSION_SOCKET'", REGION_BUILD)
        self.assertIn("environment 'SPECTRE_REGION_SESSION_OWNER_PID'", REGION_BUILD)
        self.assertIn("GetCurrentState", REGION_FIXTURE)
        self.assertIn("xwininfo", REGION_FIXTURE)
        self.assertIn("region-stage-boundaries.jsonl", REGION_FIXTURE)
        self.assertIn("redirectOutput", REGION_FIXTURE)
        self.assertIn("image.tobytes() != ref.tobytes()", REGION_DRIVER)
        self.assertNotIn("capture_output=True", REGION_DRIVER)
        self.assertNotIn(".crop((0, 0", REGION_DRIVER)


if __name__ == "__main__":
    unittest.main()

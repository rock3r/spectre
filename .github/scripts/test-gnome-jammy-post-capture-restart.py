#!/usr/bin/env python3
"""Focused unit tests for the destructive boundary of the Jammy restart probe."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import socket as unix_socket
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("gnome-jammy-post-capture-restart.py")
SPEC = importlib.util.spec_from_file_location("post_capture_restart", SCRIPT)
assert SPEC and SPEC.loader
restart = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(restart)


class PostCaptureRestartTest(unittest.TestCase):
    def test_rejects_unrelated_pid_even_when_it_owns_the_socket(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = root / "runtime"
            runtime.mkdir(mode=0o700)
            socket = runtime / "wayland-session.sock"
            socket.touch()
            expected = root / "spectre-wayland-helper"
            expected.touch()
            proc = root / "proc"
            (proc / "41").mkdir(parents=True)
            (proc / "41" / "status").write_text(
                f"Name:\tother\nState:\tS (sleeping)\nUid:\t{restart.os.getuid()}\t{restart.os.getuid()}\t0\t0\n"
            )
            (proc / "41" / "cmdline").write_bytes(b"other-helper\0--session\0")
            (proc / "41" / "exe").symlink_to("/usr/bin/sleep")

            with self.assertRaisesRegex(RuntimeError, "executable"):
                restart.verify_helper(
                    41, socket, expected.resolve(), runtime, proc,
                    fuser=lambda _: [41],
                )

    def test_accepts_exact_uid_executable_session_and_single_socket_owner(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = root / "runtime"
            runtime.mkdir(mode=0o700)
            socket = runtime / "wayland-session.sock"
            socket.touch()
            expected = root / "spectre-wayland-helper"
            expected.touch()
            proc = root / "proc"
            (proc / "52").mkdir(parents=True)
            (proc / "52" / "status").write_text(
                f"Name:\thelper\nState:\tS (sleeping)\nUid:\t{restart.os.getuid()}\t{restart.os.getuid()}\t{restart.os.getuid()}\t{restart.os.getuid()}\n"
            )
            (proc / "52" / "cmdline").write_bytes(
                str(expected.resolve()).encode() + b"\0--session\0"
            )
            (proc / "52" / "exe").symlink_to(expected.resolve())

            identity = restart.verify_helper(
                52, socket, expected.resolve(), runtime, proc,
                fuser=lambda _: [52],
            )
            self.assertEqual(identity["pid"], 52)
            self.assertEqual(identity["socketOwners"], [52])

    def test_wait_for_disconnect_allows_legitimate_gone_process_and_stale_socket(self):
        with tempfile.TemporaryDirectory() as temporary:
            socket = Path(temporary) / "wayland-session.sock"
            listener = unix_socket.socket(unix_socket.AF_UNIX)
            listener.bind(str(socket))
            listener.close()  # Leave the real, but no longer listening, socket inode behind.
            with mock.patch.object(restart, "pid_matches", return_value=False):
                outcome = restart.wait_for_disconnect(
                    73, socket, "/helper", timeout=0.1, fuser=lambda _: []
                )
            self.assertFalse(outcome["processStillMatches"])
            self.assertEqual(outcome["socketOwners"], [])
            self.assertTrue(outcome["socketPathExists"])
            self.assertTrue(outcome["socketPathIsSocket"])
            self.assertFalse(outcome["socketListening"])

    def test_wait_for_disconnect_does_not_treat_unrelated_reused_pid_as_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            socket = Path(temporary) / "wayland-session.sock"
            socket.touch()
            with mock.patch.object(restart, "pid_matches", return_value=False):
                outcome = restart.wait_for_disconnect(
                    84, socket, "/original-helper", timeout=0.1, fuser=lambda _: [999]
                )
            self.assertFalse(outcome["processStillMatches"])
            self.assertEqual(outcome["socketOwners"], [999])
            self.assertTrue(outcome["socketListening"])


if __name__ == "__main__":
    unittest.main()

@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AttachUserPreflightTest {

    private val targetPid = 4242L

    // ---- POSIX preflight: case-sensitive username equality (ProcessHandle.user() both sides) ----

    @Test
    fun `posix preflight passes when target and current users match`() {
        preflight(posix = true, current = "rock3r", target = "rock3r").requireSameUser(targetPid)
    }

    @Test
    fun `posix preflight throws when users differ`() {
        val ex =
            assertFailsWith<AttachPermissionDeniedException> {
                preflight(posix = true, current = "rock3r", target = "root")
                    .requireSameUser(targetPid)
            }
        // message names the offending target user
        assert(ex.message!!.contains("root"))
    }

    @Test
    fun `posix preflight is case-sensitive`() {
        assertFailsWith<AttachPermissionDeniedException> {
            preflight(posix = true, current = "Rock3r", target = "rock3r")
                .requireSameUser(targetPid)
        }
    }

    // ---- Windows preflight: case-insensitive, DOMAIN\name on BOTH sides (the bug fix) ----

    @Test
    fun `windows preflight passes for same DOMAIN and user`() {
        preflight(posix = false, current = "MATTONE\\rock3r", target = "MATTONE\\rock3r")
            .requireSameUser(targetPid)
    }

    @Test
    fun `windows preflight is case-insensitive on domain and user`() {
        preflight(posix = false, current = "MATTONE\\Rock3r", target = "mattone\\rock3r")
            .requireSameUser(targetPid)
    }

    @Test
    fun `windows preflight throws for a different same-domain user`() {
        assertFailsWith<AttachPermissionDeniedException> {
            preflight(posix = false, current = "MATTONE\\rock3r", target = "MATTONE\\alice")
                .requireSameUser(targetPid)
        }
    }

    // ---- Undeterminable ownership must NOT block the attach (advisory preflight) ----

    @Test
    fun `preflight proceeds when target user is unavailable`() {
        preflight(posix = true, current = "rock3r", target = null).requireSameUser(targetPid)
    }

    @Test
    fun `preflight proceeds when current user is unavailable`() {
        preflight(posix = false, current = null, target = "MATTONE\\rock3r")
            .requireSameUser(targetPid)
    }

    // ---- Factory selects the platform impl by os.name ----

    @Test
    fun `factory returns a Windows impl on Windows and a POSIX impl elsewhere`() {
        assert(AttachUserPreflight.forOs("Windows 11") is WindowsUserPreflight)
        assert(AttachUserPreflight.forOs("Mac OS X") is PosixUserPreflight)
        assert(AttachUserPreflight.forOs("Linux") is PosixUserPreflight)
    }

    private fun preflight(posix: Boolean, current: String?, target: String?): AttachUserPreflight {
        val currentResolver = { current }
        val targetResolver = { _: Long -> target }
        return if (posix) PosixUserPreflight(currentResolver, targetResolver)
        else WindowsUserPreflight(currentResolver, targetResolver)
    }
}

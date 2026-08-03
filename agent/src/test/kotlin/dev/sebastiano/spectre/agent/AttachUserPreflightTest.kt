@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

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

    // ---- POSIX preflight: numeric UID preferred over name equality (#166) ----

    @Test
    fun `posix preflight passes when UIDs match even if usernames differ`() {
        // Enterprise / directory-service false-negative shape: same identity, different name
        // strings from ProcessHandle.info().user().
        preflight(
                posix = true,
                current = "DOMAIN\\rock3r",
                target = "rock3r",
                currentUid = 501,
                targetUid = 501,
            )
            .requireSameUser(targetPid)
    }

    @Test
    fun `posix preflight throws when UIDs differ even if usernames match`() {
        val ex =
            assertFailsWith<AttachPermissionDeniedException> {
                preflight(
                        posix = true,
                        current = "rock3r",
                        target = "rock3r",
                        currentUid = 501,
                        targetUid = 0,
                    )
                    .requireSameUser(targetPid)
            }
        assertTrue(ex.message!!.contains("uid=0") || ex.message!!.contains("uid 0"))
        assertTrue(ex.message!!.contains("501"))
    }

    @Test
    fun `posix preflight throws when UIDs differ and reports both names and uids`() {
        val ex =
            assertFailsWith<AttachPermissionDeniedException> {
                preflight(
                        posix = true,
                        current = "rock3r",
                        target = "root",
                        currentUid = 501,
                        targetUid = 0,
                    )
                    .requireSameUser(targetPid)
            }
        assertTrue(ex.message!!.contains("root"))
        assertTrue(ex.message!!.contains("501"))
        assertTrue(ex.message!!.contains("0"))
    }

    @Test
    fun `posix preflight falls back to name equality when UIDs unavailable`() {
        assertFailsWith<AttachPermissionDeniedException> {
            preflight(
                    posix = true,
                    current = "rock3r",
                    target = "root",
                    currentUid = null,
                    targetUid = null,
                )
                .requireSameUser(targetPid)
        }
    }

    @Test
    fun `posix preflight falls back to names when only one UID is available`() {
        // Partial UID resolution must not invent a same-user pass from one side alone.
        assertFailsWith<AttachPermissionDeniedException> {
            preflight(
                    posix = true,
                    current = "rock3r",
                    target = "root",
                    currentUid = 501,
                    targetUid = null,
                )
                .requireSameUser(targetPid)
        }
    }

    @Test
    fun `posix preflight proceeds when both UIDs and target name are unavailable`() {
        preflight(
                posix = true,
                current = "rock3r",
                target = null,
                currentUid = null,
                targetUid = null,
            )
            .requireSameUser(targetPid)
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

    // ---- Live POSIX UID lookup (integration; skips when lookup cannot resolve) ----

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `posix uid lookup resolves the current process uid`() {
        val lookup = ProcessUidLookup.forOs()
        val uid = lookup.uidOf(ProcessHandle.current().pid())
        assertNotNull(uid, "expected a numeric UID for the current process on this host")
        assertTrue(uid >= 0)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `default posix preflight accepts the current process as same-user`() {
        // Happy path: attacher checking itself. Uses real ProcessHandle + platform UID lookup.
        PosixUserPreflight().requireSameUser(ProcessHandle.current().pid())
    }

    /**
     * Pure unit helper. UID resolvers default to null so name-path tests do not depend on host
     * process tables or accidental collisions with [targetPid].
     */
    private fun preflight(
        posix: Boolean,
        current: String?,
        target: String?,
        currentUid: Int? = null,
        targetUid: Int? = null,
    ): AttachUserPreflight {
        val currentResolver = { current }
        val targetResolver = { _: Long -> target }
        return if (posix) {
            PosixUserPreflight(
                currentUser = currentResolver,
                targetUser = targetResolver,
                currentUid = { currentUid },
                targetUid = { targetUid },
            )
        } else {
            WindowsUserPreflight(currentResolver, targetResolver)
        }
    }
}

class PosixProcessUidLookupTest {

    @Test
    fun `parseProcStatusRealUid reads the real uid field`() {
        val status =
            """
            Name:	java
            Umask:	0022
            State:	S (sleeping)
            Uid:	501	501	501	501
            Gid:	20	20	20	20
            """
                .trimIndent()
        assertEquals(501, parseProcStatusRealUid(status))
    }

    @Test
    fun `parseProcStatusRealUid prefers real over effective when they differ`() {
        val status = "Uid:\t1000\t0\t0\t0\n"
        assertEquals(1000, parseProcStatusRealUid(status))
    }

    @Test
    fun `parseProcStatusRealUid returns null when Uid line is missing`() {
        assertEquals(null, parseProcStatusRealUid("Name:\tjava\n"))
    }

    @Test
    fun `parseProcStatusRealUid tolerates spaces instead of tabs`() {
        assertEquals(42, parseProcStatusRealUid("Uid:   42  42  42  42\n"))
    }

    @Test
    fun `uid lookup factory selects linux proc vs ps-based for other unix`() {
        assertTrue(ProcessUidLookup.forOs("Linux") is LinuxProcUidLookup)
        assertTrue(ProcessUidLookup.forOs("Mac OS X") is PsProcessUidLookup)
        assertTrue(ProcessUidLookup.forOs("Darwin") is PsProcessUidLookup)
    }
}

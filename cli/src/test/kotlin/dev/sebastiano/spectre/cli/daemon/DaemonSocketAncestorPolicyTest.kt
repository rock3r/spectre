package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DaemonSocketAncestorPolicyTest {
    @Test
    fun `sticky tmp owned by the unmapped overflow uid is trusted in a user namespace`() {
        assertNull(rejection(Path.of("/tmp"), "nobody", OVERFLOW_UID, STICKY_WORLD_WRITABLE))
        // Without a passwd entry the owner name is the bare number.
        assertNull(rejection(Path.of("/tmp"), "65534", OVERFLOW_UID, STICKY_WORLD_WRITABLE))
    }

    @Test
    fun `non-writable root owned by the unmapped overflow uid is trusted in a user namespace`() {
        assertNull(rejection(Path.of("/"), "nobody", OVERFLOW_UID, OWNER_WRITABLE_ONLY))
    }

    @Test
    fun `world-writable non-sticky ancestor owned by the overflow uid is still rejected`() {
        assertEquals(
            "Daemon socket ancestor /tmp must not be group or world writable",
            rejection(Path.of("/tmp"), "nobody", OVERFLOW_UID, WORLD_WRITABLE),
        )
        assertEquals(
            "Daemon socket ancestor / must not be group or world writable",
            rejection(Path.of("/"), "nobody", OVERFLOW_UID, GROUP_WRITABLE),
        )
    }

    @Test
    fun `overflow uid owner is trusted only for the root and system temp directories`() {
        // Every unmapped host uid appears as the overflow uid, not just host root. A host
        // user's own sticky directory would look identical, and its real owner could rename
        // the daemon's directory inside it. Only `/` and `/tmp`, which ordinary host users
        // cannot own, get the overflow-owner exception.
        for (path in listOf("/shared", "/tmp/shared", "/var/tmp", "/home/someone")) {
            assertEquals(
                "Daemon socket ancestor $path must be owned by root or user",
                rejection(Path.of(path), "nobody", OVERFLOW_UID, STICKY_WORLD_WRITABLE),
            )
            assertEquals(
                "Daemon socket ancestor $path must be owned by root or user",
                rejection(Path.of(path), "nobody", OVERFLOW_UID, OWNER_WRITABLE_ONLY),
            )
        }
    }

    @Test
    fun `overflow uid owner is not trusted outside a user namespace`() {
        assertEquals(
            "Daemon socket ancestor /tmp must be owned by root or user",
            rejection(
                Path.of("/tmp"),
                "nobody",
                OVERFLOW_UID,
                STICKY_WORLD_WRITABLE,
                unmappedOverflowUid = null,
            ),
        )
    }

    @Test
    fun `other owners stay untrusted in a user namespace`() {
        assertEquals(
            "Daemon socket ancestor /tmp must be owned by root or user",
            rejection(Path.of("/tmp"), "mallory", 1234, STICKY_WORLD_WRITABLE),
        )
        // The name alone grants nothing: only the namespace's unmapped overflow uid does.
        assertEquals(
            "Daemon socket ancestor /tmp must be owned by root or user",
            rejection(Path.of("/tmp"), "nobody", 99, STICKY_WORLD_WRITABLE),
        )
    }

    @Test
    fun `root and current user owners keep their existing rules`() {
        assertNull(rejection(Path.of("/tmp"), "root", 0, STICKY_WORLD_WRITABLE))
        assertNull(rejection(Path.of("/home/me"), CURRENT_USER, 1000, OWNER_WRITABLE_ONLY))
        assertEquals(
            "Daemon socket ancestor /home/me must not be group or world writable",
            rejection(Path.of("/home/me"), CURRENT_USER, 1000, GROUP_WRITABLE),
        )
    }

    @Test
    fun `the overflow uid counts as unmapped only inside a user namespace that leaves it unmapped`() {
        // Initial namespace: identity mapping of the whole range.
        assertNull(
            DaemonSocketAncestorPolicy.unmappedOverflowUid(
                "         0          0 4294967295\n",
                "65534\n",
            )
        )
        // Bubblewrap-style single-user mapping: host root and every other host uid show up
        // as the overflow uid, which no process in the namespace can hold.
        assertEquals(
            65534,
            DaemonSocketAncestorPolicy.unmappedOverflowUid(
                "      1000       1000          1\n",
                "65534\n",
            ),
        )
        assertEquals(
            65534,
            DaemonSocketAncestorPolicy.unmappedOverflowUid(
                "         0       1000          1\n         1     100000       1000\n",
                "65534",
            ),
        )
        // A mapping that covers the overflow uid makes it a real namespace user: ambiguous,
        // so it stays untrusted.
        assertNull(
            DaemonSocketAncestorPolicy.unmappedOverflowUid(
                "         0     100000      65536\n",
                "65534\n",
            )
        )
        // Not Linux, or unreadable / malformed procfs entries.
        assertNull(DaemonSocketAncestorPolicy.unmappedOverflowUid(null, "65534"))
        assertNull(DaemonSocketAncestorPolicy.unmappedOverflowUid("1000 1000 1", null))
        assertNull(DaemonSocketAncestorPolicy.unmappedOverflowUid("garbage", "65534"))
        assertNull(DaemonSocketAncestorPolicy.unmappedOverflowUid("1000 1000 1", "nope"))
    }

    private fun rejection(
        directory: Path,
        ownerName: String,
        ownerUid: Int,
        mode: Int,
        unmappedOverflowUid: Int? = OVERFLOW_UID,
    ): String? =
        DaemonSocketAncestorPolicy.rejection(
            directory = directory,
            ownerName = ownerName,
            ownerUid = ownerUid,
            mode = mode,
            currentUser = CURRENT_USER,
            unmappedOverflowUid = unmappedOverflowUid,
        )

    private companion object {
        const val CURRENT_USER = "me"
        const val OVERFLOW_UID = 65534
        const val STICKY_WORLD_WRITABLE = 0x3FF // 01777
        const val OWNER_WRITABLE_ONLY = 0x1ED // 0755
        const val WORLD_WRITABLE = 0x1FF // 0777
        const val GROUP_WRITABLE = 0x1FD // 0775
    }
}

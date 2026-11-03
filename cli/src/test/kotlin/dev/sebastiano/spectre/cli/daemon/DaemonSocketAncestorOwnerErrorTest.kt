package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonSocketAncestorOwnerErrorTest {
    @Test
    fun `names the directory, its owner, and the accepted owners`() {
        // Built from the Path so the expected text uses the host's separator (`\shared` on
        // Windows).
        val directory = Path.of("/shared")
        val message = socketAncestorOwnerError(directory, owner = "mallory", currentUser = "me")

        assertTrue(
            message.startsWith(
                "Daemon socket ancestor $directory is owned by mallory; it must be owned by root " +
                    "or the current user (me)."
            ),
            message,
        )
        assertTrue("user namespace" !in message, message)
        assertTrue("SPECTRE_DAEMON_SOCKET_DIR" in message, message)
    }

    @Test
    fun `explains the user namespace case for an overflow owner`() {
        for (owner in listOf("nobody", "65534")) {
            val directory = Path.of("/tmp")
            val message = socketAncestorOwnerError(directory, owner = owner, currentUser = "me")

            assertTrue(
                message.startsWith(
                    "Daemon socket ancestor $directory is owned by $owner; it must be owned by root " +
                        "or the current user (me)."
                ),
                message,
            )
            assertTrue("user namespace" in message, message)
            assertTrue("not mapped" in message, message)
            assertTrue("SPECTRE_DAEMON_SOCKET_DIR" in message, message)
            // The override can't help when `/` itself is the untrusted ancestor.
            assertTrue("including /" in message, message)
        }
    }
}

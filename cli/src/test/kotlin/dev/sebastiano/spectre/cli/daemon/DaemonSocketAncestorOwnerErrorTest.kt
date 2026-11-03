package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonSocketAncestorOwnerErrorTest {
    @Test
    fun `names the directory, its owner, and the accepted owners`() {
        val message =
            socketAncestorOwnerError(Path.of("/shared"), owner = "mallory", currentUser = "me")

        assertTrue(
            message.startsWith(
                "Daemon socket ancestor /shared is owned by mallory; it must be owned by root " +
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
            val message =
                socketAncestorOwnerError(Path.of("/tmp"), owner = owner, currentUser = "me")

            assertTrue(
                message.startsWith(
                    "Daemon socket ancestor /tmp is owned by $owner; it must be owned by root " +
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

package dev.sebastiano.spectre.cli.daemon

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EmbeddedAgentRuntimeTest {
    @Test
    fun `repairs reused Windows runtime access for the current user`() {
        val home = Files.createTempDirectory("spectre-runtime-home")
        val installed =
            requireNotNull(
                EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(1, 2)) }
            )
        val aclView = Files.getFileAttributeView(installed, AclFileAttributeView::class.java)
        if (aclView == null) return

        aclView.acl = aclView.acl.filterNot { it.type() == AclEntryType.ALLOW }
        requireNotNull(
            EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        )

        val currentUser =
            installed.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
                System.getProperty("user.name")
            )
        assertEquals(
            true,
            aclView.acl.any {
                it.type() == AclEntryType.ALLOW &&
                    it.principal() == currentUser &&
                    AclEntryPermission.READ_DATA in it.permissions()
            },
        )
    }

    @Test
    fun `installed runtime path is short enough for Windows attach`() {
        val home = Files.createTempDirectory("spectre-runtime-home")
        val installed =
            requireNotNull(
                EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(1, 2)) }
            )

        assertEquals("agent-${"0".repeat(16)}.jar".length, installed.fileName.toString().length)
    }

    @Test
    fun `installs each embedded agent version at a content-addressed path`() {
        val home = Files.createTempDirectory("spectre-runtime-home")

        val first = EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        val second = EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(3, 4)) }

        assertNotEquals(first, second)
        assertEquals(listOf<Byte>(1, 2), Files.readAllBytes(first).toList())
        assertEquals(listOf<Byte>(3, 4), Files.readAllBytes(second).toList())
    }
}

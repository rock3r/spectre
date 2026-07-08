package dev.sebastiano.spectre.agent.transport

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class SocketFileProtectionTest {
    private val cleanup = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        cleanup.asReversed().forEach { runCatching { it.deleteIfExists() } }
    }

    // ---- Platform-agnostic: factory selects the impl by supported attribute views ----

    @Test
    fun `forViews selects the POSIX impl when posix is supported`() {
        assertIs<PosixSocketFileProtection>(
            SocketFileProtection.forViews(setOf("basic", "posix", "owner", "unix"))
        )
    }

    @Test
    fun `forViews selects the Windows ACL impl when posix is absent`() {
        assertIs<WindowsAclSocketFileProtection>(
            SocketFileProtection.forViews(setOf("basic", "owner", "dos", "acl", "user"))
        )
    }

    // ---- Windows: created dir + protected file carry a single owner-only full-control ACE ----

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows createMissingParentDirectory yields an owner-only ACL and returns the created dir`() {
        val base = Files.createTempDirectory("sp-prot-").also { cleanup.add(it) }
        val uds = base.resolve("sp-a-${UUID.randomUUID().toString().take(8)}").resolve("agent.sock")
        cleanup.add(uds.parent)

        val protection = SocketFileProtection.forPath(uds)
        assertIs<WindowsAclSocketFileProtection>(protection)

        val created = protection.createMissingParentDirectory(uds)
        assertEquals(uds.parent, created, "should return the directory it created")
        assertOwnerOnlyAcl(uds.parent)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows createMissingParentDirectory returns null when the parent already exists`() {
        val base = Files.createTempDirectory("sp-prot-").also { cleanup.add(it) }
        val uds = base.resolve("agent.sock")
        assertEquals(null, SocketFileProtection.forPath(uds).createMissingParentDirectory(uds))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows protectSocketFile tightens the socket file to an owner-only ACL`() {
        val base = Files.createTempDirectory("sp-prot-").also { cleanup.add(it) }
        val socket = base.resolve("agent.sock").also { Files.createFile(it) }
        cleanup.add(socket)

        SocketFileProtection.forPath(socket).protectSocketFile(socket)
        assertOwnerOnlyAcl(socket)
    }
}

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Windows-only integration test for [IpcServer]'s trust boundary (#195): the real bind path must
 * create the private parent directory with an owner-only ACL, tighten the freshly-bound socket to
 * an owner-only ACL, serve a client, and clean both up on close — the NTFS analog of the POSIX
 * `server creates missing UDS parent owner-only and removes it on close` test in
 * [IpcRoundTripTest].
 */
@EnabledOnOs(OS.WINDOWS)
class IpcServerWindowsAclTest {
    private val cleanup = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        cleanup.asReversed().forEach { runCatching { it.deleteIfExists() } }
    }

    @Test
    fun `IpcServer creates an owner-only dir plus socket ACL, serves a client, and cleans up on close`() {
        val base = Files.createTempDirectory("sp-ipc-win-").also { cleanup.add(it) }
        val parent = base.resolve("sp-a-${UUID.randomUUID().toString().take(8)}")
        val uds = parent.resolve("agent.sock")
        cleanup.add(parent)

        IpcServer(uds, AgentRequestHandler { AgentResponse.Pong }).use {
            awaitSocket(uds)
            assertOwnerOnlyAcl(parent)
            assertOwnerOnlyAcl(uds)

            // Functional proof: the protected socket still serves a real CBOR round-trip.
            IpcClient(uds).use { client ->
                assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
            }
        }

        assertFalse(Files.exists(uds), "socket should be unlinked after close")
        assertFalse(Files.exists(parent), "private parent dir should be removed after close")
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS file $path did not appear within $timeoutMs ms")
    }
}

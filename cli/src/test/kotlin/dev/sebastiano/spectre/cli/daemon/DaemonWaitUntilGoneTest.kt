package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAgentException
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `waitUntilGone` over the **real** daemon boundary (#438 transport parity): a live [DaemonServer]
 * on a Unix domain socket, requests and responses encoded by the production [DaemonWireCodec], and
 * a [DaemonClient] on the other side — not a hand-built response object.
 *
 * The verb exists for its timeout diagnostics, so the timeout case asserts that the selector, the
 * timeout, and the still-present count all survive the CBOR round trip alongside the stable
 * `timeout` taxonomy, rather than degrading to a bare "timed out".
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonWaitUntilGoneTest {

    @Test
    fun `waitUntilGone completes over the daemon wire once nothing matches`() {
        val socketPath = temporarySocketPath()
        val calls = mutableListOf<List<Any?>>()
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                waitUntilGoneAction = { tag, text, timeoutMs, pollIntervalMs ->
                    calls += listOf(tag, text, timeoutMs, pollIntervalMs)
                }
            )
        }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(4242))
            )

            val response =
                DaemonClient(socketPath)
                    .request(
                        DaemonRequest.WaitUntilGone(
                            sessionId = "pid-4242",
                            tag = "popup.body",
                            timeoutMs = 1_500,
                            pollIntervalMs = 25,
                        )
                    )

            val completed = assertIs<DaemonResponse.Completed>(response)
            assertEquals("pid-4242", completed.sessionId)
            assertEquals(listOf(listOf<Any?>("popup.body", null, 1_500L, 25L)), calls)
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `waitUntilGone timeout keeps selector, timeout, and still-present count over the wire`() {
        val socketPath = temporarySocketPath()
        val diagnostics =
            """waitUntilGone timed out after 400ms: 2 node(s) matching tag="popup.body" """ +
                "still present in tracked windows"
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                waitUntilGoneAction = { _, _, _, _ ->
                    throw SpectreAgentException(
                        category = AgentErrorCategory.Timeout,
                        message =
                            "Agent reported timeout for waitUntilGone: " +
                                "IllegalStateException: $diagnostics",
                    )
                }
            )
        }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(4343))
            )

            val response =
                DaemonClient(socketPath)
                    .request(
                        DaemonRequest.WaitUntilGone(
                            sessionId = "pid-4343",
                            tag = "popup.body",
                            timeoutMs = 400,
                        )
                    )

            val error = assertIs<DaemonResponse.Error>(response)
            assertEquals(DaemonErrorCode.Timeout, error.code)
            assertEquals(AgentErrorCategory.Timeout.wireName, error.category)
            assertTrue(
                error.message.contains(diagnostics),
                "absence diagnostics degraded over the daemon wire to \"${error.message}\"",
            )
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `waitUntilGone relays a non-timeout taxonomy without claiming a timeout`() {
        val socketPath = temporarySocketPath()
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                waitUntilGoneAction = { _, _, _, _ ->
                    throw SpectreAgentException(
                        category = AgentErrorCategory.InvalidSelector,
                        message =
                            "Agent reported invalidSelector for waitUntilGone: " +
                                "Either tag or text must be specified",
                    )
                }
            )
        }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(4444))
            )

            val error =
                assertIs<DaemonResponse.Error>(
                    DaemonClient(socketPath)
                        .request(DaemonRequest.WaitUntilGone(sessionId = "pid-4444"))
                )
            // Not every agent failure is a timeout: only `timeout` earns the timeout code, while
            // the precise taxonomy still rides along in `category`.
            assertEquals(DaemonErrorCode.OperationFailed, error.code)
            assertEquals(AgentErrorCategory.InvalidSelector.wireName, error.category)
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `waitUntilGone on an unknown session fails closed`() {
        val socketPath = temporarySocketPath()
        val server = DaemonServer(socketPath, registry = DaemonSessionRegistry { error("unused") })

        try {
            val error =
                assertIs<DaemonResponse.Error>(
                    DaemonClient(socketPath)
                        .request(DaemonRequest.WaitUntilGone(sessionId = "pid-1", tag = "gone"))
                )
            assertEquals(DaemonErrorCode.SessionNotFound, error.code)
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    private fun temporarySocketPath(): Path =
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Path.of("/tmp", "sp-g-${UUID.randomUUID().toString().take(8)}", "daemon", "daemon.sock")
        } else {
            Files.createTempDirectory("spectre-gone-test").resolve("daemon").resolve("daemon.sock")
        }

    private fun deleteTemporarySocketPath(socketPath: Path) {
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketPath.parent)
        Files.deleteIfExists(socketPath.parent.parent)
    }
}

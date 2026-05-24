@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * In-JVM round-trip test for [IpcServer] + [IpcClient] over a real Unix Domain Socket.
 *
 * Validates plan M-3's tracer slice: client → server send/receive of CBOR-encoded frames over an
 * actual UDS, without needing a child JVM. The server's handler is a stub that maps requests to
 * canned responses — the real `ReflectiveAutomatorHandler` is exercised in M-7/M-8's child-JVM
 * integration tests where a real `ComposeAutomator` exists.
 *
 * Each test creates a unique UDS path under `java.io.tmpdir` and cleans up via @AfterTest. Paths
 * must be short enough to fit Unix's sun_path limit (~104 chars on macOS, ~108 on Linux); a short
 * UUID prefix keeps us well within bounds.
 */
class IpcRoundTripTest {
    // Use `/tmp` directly rather than `java.io.tmpdir` — on macOS the latter resolves to
    // `/var/folders/...` which can push the path past Unix's 104-char `sun_path` limit.
    // `/tmp` is symlinked to `/private/tmp` (12 chars) and works on both macOS and Linux.
    private val udsPath: Path = Path.of("/tmp", "sp-t-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `client sends Ping and receives Pong over UDS`() {
        val server = IpcServer(udsPath, stubHandler())
        server.use {
            // Server binds asynchronously on its accept thread; small spin-wait until the socket
            // file
            // shows up so the client doesn't race-fail with ConnectException.
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                val resp = client.send(AgentRequest.Ping)
                assertEquals(AgentResponse.Pong, resp)
            }
        }
    }

    @Test
    fun `client sends Windows and receives a Windows response`() {
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { req ->
                    when (req) {
                        AgentRequest.Windows ->
                            AgentResponse.Windows(
                                listOf(
                                    WindowSummaryDto(
                                        index = 0,
                                        surfaceId = "fake-surface-0",
                                        title = "Fake Window",
                                        isPopup = false,
                                        bounds = RectDto(0, 0, 800, 600),
                                    )
                                )
                            )
                        else -> AgentResponse.Error("Unexpected $req")
                    }
                },
            )
        server.use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                val resp = assertIs<AgentResponse.Windows>(client.send(AgentRequest.Windows))
                assertEquals(1, resp.windows.size)
                assertEquals("fake-surface-0", resp.windows.single().surfaceId)
            }
        }
    }

    @Test
    fun `multiple requests over one connection are framed independently`() {
        val server = IpcServer(udsPath, stubHandler())
        server.use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
                assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
                val nodes = assertIs<AgentResponse.Nodes>(client.send(AgentRequest.AllNodes))
                assertTrue(nodes.nodes.isEmpty())
            }
        }
    }

    @Test
    fun `Detach response is sent and connection closes`() {
        val detached = java.util.concurrent.CountDownLatch(1)
        val server = IpcServer(udsPath, stubHandler(), onDetach = { detached.countDown() })
        server.use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                val resp = client.send(AgentRequest.Detach)
                assertEquals(AgentResponse.Detached, resp)
            }
            assertTrue(
                detached.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "onDetach hook should have fired within 2s of the Detach request",
            )
        }
        // UDS file is unlinked by the server after detach.
        assertTrue(!Files.exists(udsPath), "UDS path should not exist after detach")
    }

    @Test
    fun `Server returns Error for an unknown request from the stub handler`() {
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { AgentResponse.Error("test-handler always errors") },
            )
        server.use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                val resp = assertIs<AgentResponse.Error>(client.send(AgentRequest.Ping))
                assertTrue(resp.message.contains("test-handler"))
            }
        }
    }

    private fun stubHandler(): AgentRequestHandler = AgentRequestHandler { request ->
        when (request) {
            AgentRequest.Ping -> AgentResponse.Pong
            AgentRequest.Windows -> AgentResponse.Windows(emptyList())
            AgentRequest.AllNodes -> AgentResponse.Nodes(emptyList())
            is AgentRequest.FindByTestTag -> AgentResponse.Nodes(emptyList())
            is AgentRequest.Click -> AgentResponse.Ok
            is AgentRequest.TypeText -> AgentResponse.Ok
            AgentRequest.Screenshot -> AgentResponse.Screenshot(ByteArray(0))
            AgentRequest.Detach -> AgentResponse.Detached
        }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS file $path did not appear within ${timeoutMs} ms")
    }
}

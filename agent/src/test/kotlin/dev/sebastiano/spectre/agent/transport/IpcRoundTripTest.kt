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
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

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
 *
 * **Disabled on Windows** via `@EnabledOnOs(OS.LINUX, OS.MAC)`. The agent transport is macOS+Linux
 * only in v1 — Windows support (named pipes via JNA/junixsocket) is a tracked follow-up. The
 * hard-coded `/tmp/...` UDS path is meaningless on Windows and the test would `SocketException` on
 * connect rather than exercising the round-trip contract.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
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

    @Test
    fun `server survives a client that closes mid-request and keeps accepting`() {
        // Regression for Bugbot MEDIUM finding on commit 4b1d777: `acceptLoop` previously
        // wrapped the entire `while` in a single try-catch, so an `IOException` from
        // `handleConnection` (e.g. broken pipe when `Framing.writeFrame` writes to a socket
        // whose client has already closed) terminated the accept thread for good. Combined
        // with `SpectreAgent.bootstrap`'s "already bootstrapped" idempotency guard, a single
        // crashed/misbehaving client would make the agent permanently unreachable for the
        // rest of the target JVM's lifetime — re-attach attempts would see the same agent
        // state, skip server creation, and hang on the dead socket.
        //
        // Test approach: open a raw `SocketChannel`, write a Ping frame, then close the
        // channel immediately without reading the response. The server will read the Ping,
        // dispatch to the handler, and try to write Pong back — at which point the OS will
        // surface either a broken-pipe `IOException` or an EOF on subsequent reads. Either
        // way, the per-connection catch must absorb it. Then open a clean `IpcClient` and
        // assert Ping/Pong still round-trips.
        val server = IpcServer(udsPath, stubHandler())
        server.use {
            awaitSocket(udsPath)

            // Repeat to make the test more likely to actually trigger a broken-pipe on the
            // server-side write (vs. the response being buffered by the OS before close
            // propagates). A single iteration sometimes races; three is enough to wedge
            // the loop reliably if the regression returns.
            repeat(3) {
                java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(udsPath))
                    .use { rawClient ->
                        val pingBytes = WireCodec.encode(AgentRequest.Ping)
                        val frame =
                            java.nio.ByteBuffer.allocate(Int.SIZE_BYTES + pingBytes.size).apply {
                                putInt(pingBytes.size)
                                put(pingBytes)
                                flip()
                            }
                        while (frame.hasRemaining()) {
                            rawClient.write(frame)
                        }
                        // Close immediately without reading the response so the server's
                        // writeFrame races against a closed client.
                    }
            }

            // The accept loop must still be alive: a clean Ping must round-trip.
            // Wrapped in `assertTimeoutPreemptively` because the regression mode is the
            // server going PERMANENTLY UNREACHABLE — without a timeout the failing test
            // would just hang the suite, hiding the regression behind a wall-clock kill.
            // 3 s is comfortably above a healthy in-JVM Ping (~ms) and finite enough that
            // CI surfaces the failure fast.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3),
                {
                    IpcClient(udsPath).use { client ->
                        assertEquals(
                            AgentResponse.Pong,
                            client.send(AgentRequest.Ping),
                            "expected the accept loop to have survived the misbehaving clients " +
                                "and still serve a clean Ping — if this fails, the per-" +
                                "connection IOException catch regressed",
                        )
                    }
                },
                "the accept loop did not respond within 3s — it likely died on the broken-pipe " +
                    "IOException from the misbehaving clients (per-connection catch regressed)",
            )
        }
    }

    @Test
    fun `Server converts a checked TimeoutException from the handler into an Error and keeps the accept loop alive`() {
        // Regression: `BlockingSuspendInvoker.await()` throws
        // `java.util.concurrent.TimeoutException`
        // (a checked `Exception`, NOT `RuntimeException`). An earlier draft of `handleOneRequest`
        // only caught `RuntimeException`, which let the timeout escape through `handleConnection` →
        // `acceptLoop` and kill the accept thread — permanently crashing the IPC server after a
        // single slow click/typeText. This test pins the contract: a checked exception from the
        // handler MUST round-trip as `AgentResponse.Error` and the server MUST stay accepting.
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { req ->
                    when (req) {
                        is AgentRequest.Click ->
                            throw java.util.concurrent.TimeoutException(
                                "synthetic suspend timeout for test"
                            )
                        AgentRequest.Ping -> AgentResponse.Pong
                        else -> AgentResponse.Error("unexpected $req")
                    }
                },
            )
        server.use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                val resp =
                    assertIs<AgentResponse.Error>(client.send(AgentRequest.Click(nodeKey = "k1")))
                assertTrue(
                    resp.message.contains("TimeoutException"),
                    "expected error message to mention TimeoutException; got: ${resp.message}",
                )
                assertTrue(
                    resp.message.contains("synthetic suspend timeout"),
                    "expected error message to include the timeout's text; got: ${resp.message}",
                )
                // The server must still be alive — a follow-up request on a fresh connection
                // proves the accept loop wasn't killed by the checked exception.
            }
            IpcClient(udsPath).use { followUp ->
                assertEquals(
                    AgentResponse.Pong,
                    followUp.send(AgentRequest.Ping),
                    "follow-up Ping after TimeoutException must succeed — the accept loop must " +
                        "survive a checked exception from the handler",
                )
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

@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * Each test creates a unique UDS path via [udsBase] and cleans up via @AfterTest. The base
 * directory is platform-aware — `/tmp` on POSIX, `%TEMP%` (`java.io.tmpdir`) on Windows, where
 * `/tmp` is meaningless — and short enough to fit the `sun_path` limit (~104 chars macOS, ~108
 * Linux/Windows); a short UUID prefix keeps us well within bounds.
 *
 * Runs on Windows too (native `AF_UNIX`, #196). Two POSIX-mode-specific cases carry a method-level
 * `@EnabledOnOs(OS.LINUX, OS.MAC)`: the parent-permissions assertion and the FD-leak probe (which
 * needs `UnixOperatingSystemMXBean`). The Windows analog — owner-only ACLs on the created dir and
 * socket plus cleanup on close — lives in [IpcServerWindowsAclTest].
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class IpcRoundTripTest {
    private val udsPath: Path =
        udsBase().resolve("sp-t-${UUID.randomUUID().toString().take(8)}.sock")

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
    @EnabledOnOs(
        OS.LINUX,
        OS.MAC,
    ) // POSIX mode assertion; Windows ACL analog: IpcServerWindowsAclTest
    fun `server creates missing UDS parent owner-only and removes it on close`() {
        val parent = Path.of("/tmp", "sp-t-${UUID.randomUUID().toString().take(8)}")
        val nestedUdsPath = parent.resolve("agent.sock")

        val server = IpcServer(nestedUdsPath, stubHandler())
        server.use {
            awaitSocket(nestedUdsPath)
            assertEquals(
                OWNER_ONLY_DIRECTORY_PERMS,
                Files.getPosixFilePermissions(parent),
                "created UDS parent should be owner-only",
            )
        }

        assertTrue(!Files.exists(nestedUdsPath), "UDS path should be removed after close")
        assertTrue(!Files.exists(parent), "private UDS parent should be removed after close")
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
                        val output = java.nio.channels.Channels.newOutputStream(rawClient)
                        val input = java.nio.channels.Channels.newInputStream(rawClient)
                        // #199: complete handshake first; the regression under test is broken-pipe
                        // after a valid session, not pre-handshake teardown.
                        Framing.writeFrame(
                            output,
                            WireCodec.encode(
                                AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT)
                            ),
                        )
                        Framing.readFrame(input) // HelloAck
                        val pingBytes =
                            WireCodec.encode(OpRequest(opId = 1L, body = AgentRequest.Ping))
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
    @EnabledOnOs(OS.LINUX, OS.MAC) // relies on UnixOperatingSystemMXBean.openFileDescriptorCount
    fun `IpcServer constructor releases the ServerSocketChannel when bind fails`() {
        // Regression for Bugbot LOW finding on commit 82be70e: in the `IpcServer` constructor,
        // `ServerSocketChannel.open()` opens a channel, but if `bind()` (or the `deleteIfExists`
        // before it, or the POSIX permission tightening after it) throws, the channel was
        // never closed. `ServerSocketChannel` has no finalizer/cleaner, so the native FD would
        // persist until JVM exit.
        //
        // Direct-evidence approach: observe `UnixOperatingSystemMXBean.openFileDescriptorCount`
        // before and after N constructor failures. A leaky constructor would grow the count by
        // ≈ N (one leaked FD per failed bind). The fixed constructor's outer `finally` closes
        // the channel, so the count should stay flat aside from a few FDs of normal JVM churn.
        val osBean =
            java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as com.sun.management.UnixOperatingSystemMXBean
        // Path is way past the `sun_path` cap (~104 bytes macOS, ~108 Linux) so bind must fail.
        val tooLongUdsPath = Path.of("/tmp", "sp-" + "x".repeat(SUN_PATH_OVERFLOW_LEN) + ".sock")

        // Sanity-check the test scaffolding: bind must actually throw for this path.
        assertFailsWith<java.io.IOException> { IpcServer(tooLongUdsPath, stubHandler()).use {} }

        // Warm-up — the first few attempts can churn extra FDs from JVM/JNI lazy init
        // (NIO selectors, etc.) that would dominate the leak signal otherwise.
        repeat(FD_LEAK_WARMUP) { runCatching { IpcServer(tooLongUdsPath, stubHandler()).use {} } }

        val before = osBean.openFileDescriptorCount
        repeat(FD_LEAK_ITERATIONS) {
            assertFailsWith<java.io.IOException> { IpcServer(tooLongUdsPath, stubHandler()).use {} }
        }
        val after = osBean.openFileDescriptorCount
        val growth = after - before
        assertTrue(
            growth < FD_LEAK_TOLERANCE,
            "open FD count grew by $growth after $FD_LEAK_ITERATIONS failed binds " +
                "(from $before → $after) — tolerance is $FD_LEAK_TOLERANCE. The IpcServer " +
                "constructor is leaking the ServerSocketChannel on bind failure.",
        )
    }

    // NOTE: No regression test for the IpcClient FD-leak fix (Bugbot LOW on 9576bcf, fixed
    // in the same patch as this comment). `SocketChannel` is registered with the JDK's
    // `Cleaner` (see `sun.nio.ch.SocketChannelImpl`), so leaked client channels get
    // reclaimed by GC eventually — masking the bug from the
    // `openFileDescriptorCount`-based technique we use for the symmetric `IpcServer` FD-leak
    // test (`ServerSocketChannel` is also Cleaner-backed but empirically kept alive longer
    // by other JDK references, so its leak shows up in the FD count). Attempting the same
    // measurement against `IpcClient` produced false-greens because GC reaped the FDs
    // between iterations. The semantic fix (close-on-failure rather than rely on GC) is
    // still applied — see the `also { channel -> ... }` block in `IpcClient`'s primary
    // constructor.

    @Test
    fun `server survives malformed frame length prefix and keeps accepting`() {
        // Regression for Bugbot MEDIUM finding on commit fcf5b14: `Framing.readFrame`
        // throws `IllegalStateException` via `check(length in 0..MAX_FRAME_BYTES)` when a
        // client sends a negative or over-cap length header. The earlier per-connection
        // catch only handled `IOException`, so a single misbehaving client sending an
        // invalid length prefix would escape, kill the accept thread, and leave the agent
        // permanently unreachable — exactly the "MUST NOT happen" scenario the per-
        // connection catch's docstring promises.
        val server = IpcServer(udsPath, stubHandler())
        server.use {
            awaitSocket(udsPath)

            // Complete handshake first, then send a bad length prefix. Pre-handshake bad
            // frames intentionally tear down agent state (#199); this test pins post-
            // handshake accept-loop survival only.
            java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(udsPath))
                .use { rawClient ->
                    val output = java.nio.channels.Channels.newOutputStream(rawClient)
                    val input = java.nio.channels.Channels.newInputStream(rawClient)
                    Framing.writeFrame(
                        output,
                        WireCodec.encode(
                            AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT)
                        ),
                    )
                    Framing.readFrame(input) // HelloAck
                    val invalidHeader =
                        java.nio.ByteBuffer.allocate(Int.SIZE_BYTES)
                            .order(java.nio.ByteOrder.BIG_ENDIAN)
                            .putInt(-1)
                            .flip()
                    while (invalidHeader.hasRemaining()) {
                        rawClient.write(invalidHeader)
                    }
                }

            // A clean Ping must still round-trip, with a finite timeout because the
            // regression mode is the server going PERMANENTLY UNREACHABLE — without the
            // timeout the failing test would just hang the suite.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3),
                {
                    IpcClient(udsPath).use { client ->
                        assertEquals(
                            AgentResponse.Pong,
                            client.send(AgentRequest.Ping),
                            "expected the accept loop to have absorbed the IllegalStateException " +
                                "from the malformed length prefix and still serve a clean Ping",
                        )
                    }
                },
                "the accept loop did not respond within 3s — the per-connection catch likely " +
                    "regressed to only catching IOException, letting the IllegalStateException " +
                    "from the bad length prefix kill the loop",
            )
        }
    }

    @Test
    fun `Detach cleanup fires even when the response write fails`() {
        // Regression for Bugbot MEDIUM finding on commit fcf5b14: in `handleOneRequest`,
        // when `Framing.writeFrame` throws `IOException` while writing the `Detached`
        // response (e.g. the attaching client closed mid-Detach), the previous code never
        // reached `running.set(false)` or `onDetach()`. The `IOException` would propagate
        // through `handleConnection`, get absorbed by the per-connection catch — leaving
        // the server alive but with `SpectreAgent.agentState` still non-null. The next
        // re-attach attempt would hit the idempotency guard, skip creating a new IPC
        // server, and the caller's `waitForUdsPath` would time out. The target JVM
        // becomes permanently un-attachable until restart.
        //
        // Test approach: open a raw `SocketChannel`, send a Detach frame, immediately
        // close the channel. The server reads Detach, processes it, calls
        // `Framing.writeFrame(Detached)` against a closed peer (which races to broken
        // pipe). The `onDetach` callback MUST still fire because the try/finally guards it.
        val detached = java.util.concurrent.CountDownLatch(1)
        val server = IpcServer(udsPath, stubHandler(), onDetach = { detached.countDown() })
        server.use {
            awaitSocket(udsPath)

            java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(udsPath))
                .use { rawClient ->
                    val output = java.nio.channels.Channels.newOutputStream(rawClient)
                    val input = java.nio.channels.Channels.newInputStream(rawClient)
                    // #199 handshake required before Detach is dispatched.
                    Framing.writeFrame(
                        output,
                        WireCodec.encode(
                            AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT)
                        ),
                    )
                    Framing.readFrame(input) // HelloAck
                    val detachBytes =
                        WireCodec.encode(OpRequest(opId = 1L, body = AgentRequest.Detach))
                    val frame =
                        java.nio.ByteBuffer.allocate(Int.SIZE_BYTES + detachBytes.size).apply {
                            putInt(detachBytes.size)
                            put(detachBytes)
                            flip()
                        }
                    while (frame.hasRemaining()) {
                        rawClient.write(frame)
                    }
                    // Close immediately — the server's writeFrame for the Detached response
                    // will race against a closed client. Whether the broken-pipe IOException
                    // actually fires depends on OS write buffering; either way, `onDetach`
                    // MUST fire because of the try/finally guard.
                }

            assertTrue(
                detached.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "onDetach must fire even when the response writeFrame fails — if this " +
                    "times out, the Detach side-effects regressed to only running on " +
                    "successful response write",
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
            AgentRequest.AllNodes,
            is AgentRequest.FindByTestTag,
            is AgentRequest.FindByText,
            is AgentRequest.FindByContentDescription,
            is AgentRequest.FindByRole,
            is AgentRequest.WaitForNode -> AgentResponse.Nodes(emptyList())
            is AgentRequest.Click,
            is AgentRequest.DoubleClick,
            is AgentRequest.LongClick,
            is AgentRequest.Swipe,
            is AgentRequest.ScrollWheel,
            is AgentRequest.PressKey,
            is AgentRequest.TypeText,
            is AgentRequest.Cancel,
            is AgentRequest.WaitForVisualIdle -> AgentResponse.Ok
            is AgentRequest.Screenshot -> AgentResponse.Screenshot(ByteArray(0))
            is AgentRequest.Capture ->
                AgentResponse.Capture(
                    windowIndex = request.windowIndex,
                    schemaVersion = 1,
                    captureJsonUtf8 = "{}".toByteArray(),
                    pngBytes = ByteArray(0),
                    nodeCount = 0,
                    taggedNodeCount = 0,
                    textedNodeCount = 0,
                    imageWidth = 0,
                    imageHeight = 0,
                    captureDurationMs = 0,
                )
            is AgentRequest.WindowIdentity -> AgentResponse.WindowIdentities(emptyList())
            AgentRequest.Detach -> AgentResponse.Detached
            is AgentRequest.Hello ->
                AgentResponse.HelloAck(protocolVersion = ProtocolVersion.CURRENT)
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

    // Short, platform-appropriate base dir for UDS paths: `/tmp` on POSIX (macOS `java.io.tmpdir`
    // is `/var/folders/...` and can exceed sun_path); `%TEMP%` on Windows, where `/tmp` is
    // drive-relative nonsense. Reads only system properties, so it's safe from a property init.
    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
            Path.of(System.getProperty("java.io.tmpdir"))
        else Path.of("/tmp")

    private companion object {
        // Comfortably above macOS's ~104-byte and Linux's ~108-byte `sun_path` cap so the
        // bind unambiguously fails. Used by the "constructor releases ServerSocketChannel
        // when bind fails" regression test.
        const val SUN_PATH_OVERFLOW_LEN: Int = 200
        // Warm-up + measured iterations for the FD-leak regression test. 50 iterations is
        // enough that a per-iteration leak would grow the FD count by ~50 — easily detected
        // even with the tolerance for ambient JVM churn.
        const val FD_LEAK_WARMUP: Int = 5
        const val FD_LEAK_ITERATIONS: Int = 50
        const val FD_LEAK_TOLERANCE: Long = 10
        val OWNER_ONLY_DIRECTORY_PERMS =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
    }
}

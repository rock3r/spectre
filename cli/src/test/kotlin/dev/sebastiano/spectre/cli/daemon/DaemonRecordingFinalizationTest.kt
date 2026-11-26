package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.recording.RecordingResult
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonRecordingFinalizationTest {
    @Test
    fun `shutdown negotiates recording outcomes and operation diagnostics independently`() {
        fun registry() = DaemonSessionRegistry {
            object : DaemonSessionAutomator by TestDaemonSessionAutomator() {
                override fun recordingStatus() =
                    RecordingStatus(active = true, outputPath = "shutdown.mp4")

                override fun finalizeRecording(
                    remainingLiveSessionIds: Set<String>
                ): RecordingResult {
                    RequestDiagnostics.report("partial shutdown recording")
                    return RecordingResult(Path.of("shutdown.mp4"), 3, true)
                }
            }
        }

        val currentSocket = temporaryDaemonClientSocketPath()
        val currentServer = DaemonServer(currentSocket, registry())
        try {
            val client = DaemonClient(currentSocket, retainDiagnostics = true)
            assertIs<DaemonResponse.Attached>(client.request(DaemonRequest.Attach(711)))
            val diagnosed =
                assertIs<DaemonResponse.Diagnosed>(client.request(DaemonRequest.Shutdown))
            assertEquals(listOf("partial shutdown recording"), diagnosed.warnings)
            val shutdown = assertIs<DaemonResponse.ShuttingDownWithRecordings>(diagnosed.response)
            assertEquals(3, shutdown.recordings.single().diagnostics?.droppedFrames)
        } finally {
            currentServer.close()
            assertTrue(currentServer.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(currentSocket)
        }

        val legacySocket = temporaryDaemonClientSocketPath()
        val legacyServer = DaemonServer(legacySocket, registry())
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(legacySocket))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                writePublished15Hello(output)
                assertIs<DaemonResponse.Hello>(DaemonWireCodec.readResponse(input))
                DaemonWireCodec.writeRequest(output, DaemonRequest.Attach(712))
                assertIs<DaemonResponse.Attached>(DaemonWireCodec.readResponse(input))
                DaemonWireCodec.writeRequest(output, DaemonRequest.Shutdown)
                val diagnosed =
                    assertIs<DaemonResponse.Diagnosed>(DaemonWireCodec.readResponse(input))
                assertEquals(DaemonResponse.ShuttingDown, diagnosed.response)
                assertEquals(listOf("partial shutdown recording"), diagnosed.warnings)
            }
        } finally {
            legacyServer.close()
            assertTrue(legacyServer.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(legacySocket)
        }
    }

    @Test
    fun `pre-diagnostics recording opt-in never receives a diagnosed envelope`() {
        val registry = DaemonSessionRegistry {
            object : DaemonSessionAutomator by TestDaemonSessionAutomator() {
                override fun finalizeRecording(
                    remainingLiveSessionIds: Set<String>
                ): RecordingResult {
                    RequestDiagnostics.report("legacy warning cannot be delivered")
                    return RecordingResult(Path.of("legacy.mp4"), 0, false)
                }
            }
        }
        assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(714)))
        val socketPath = temporaryDaemonClientSocketPath()
        val server = DaemonServer(socketPath, registry)
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                val hello =
                    DaemonRequest.Hello(DaemonProtocolVersion(major = 1, minor = 14)).also {
                        it.acceptsShutdownRecordings = true
                    }
                DaemonWireCodec.writeRequest(output, hello)
                assertIs<DaemonResponse.Hello>(DaemonWireCodec.readResponse(input))
                DaemonWireCodec.writeRequest(output, DaemonRequest.Shutdown)
                assertIs<DaemonResponse.ShuttingDownWithRecordings>(
                    DaemonWireCodec.readResponse(input)
                )
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `finalize returns the backend observations on detach and repeated shutdown`() {
        val result = RecordingResult(Path.of("movie.mp4"), 6, true)
        val registry = DaemonSessionRegistry {
            object : DaemonSessionAutomator by TestDaemonSessionAutomator() {
                override fun finalizeRecording(remainingLiveSessionIds: Set<String>) = result
            }
        }
        val session = assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(42)))
        val expected = DaemonResponse.RecordingDiagnostics(6, true)
        val detached =
            assertIs<DaemonResponse.Detached>(
                registry.handle(DaemonRequest.Detach(session.sessionId))
            )
        assertEquals(result.output.toString(), detached.finalizedRecordingPath)
        assertEquals(expected, detached.diagnostics)

        registry.handle(DaemonRequest.Attach(42))
        repeat(2) {
            val shutdown =
                assertIs<DaemonResponse.ShuttingDownWithRecordings>(
                    registry.handle(DaemonRequest.Shutdown)
                )
            assertEquals(expected, shutdown.recordings.single().diagnostics)
        }
    }

    @Test
    fun `wire detach preserves finalization result and operation diagnostics together`() {
        listOf(false, true).forEachIndexed { index, fails ->
            val output = Path.of("wire-$index.mp4")
            val socketPath = temporaryDaemonClientSocketPath()
            val registry = DaemonSessionRegistry {
                object : DaemonSessionAutomator by TestDaemonSessionAutomator() {
                    override fun recordingStatus() =
                        RecordingStatus(active = true, outputPath = output.toString())

                    override fun finalizeRecording(
                        remainingLiveSessionIds: Set<String>
                    ): RecordingResult {
                        RequestDiagnostics.report("finalization diagnostic $index")
                        if (fails) throw IOException("finalization failed $index")
                        return RecordingResult(output, droppedFrames = 2, cropFixedAtStart = false)
                    }
                }
            }
            val server = DaemonServer(socketPath, registry)
            try {
                val client = DaemonClient(socketPath, retainDiagnostics = true)
                val attached =
                    assertIs<DaemonResponse.Attached>(
                        client.request(DaemonRequest.Attach(100L + index))
                    )
                val diagnosed =
                    assertIs<DaemonResponse.Diagnosed>(
                        client.request(DaemonRequest.Detach(attached.sessionId))
                    )
                assertEquals(listOf("finalization diagnostic $index"), diagnosed.warnings)
                val detached = assertIs<DaemonResponse.Detached>(diagnosed.response)
                if (fails) {
                    assertNull(detached.finalizedRecordingPath)
                    assertTrue(
                        detached.recordingError.orEmpty().contains("finalization failed $index")
                    )
                } else {
                    assertEquals(output.toString(), detached.finalizedRecordingPath)
                    assertEquals(
                        DaemonResponse.RecordingDiagnostics(2, false),
                        detached.diagnostics,
                    )
                    assertNull(detached.recordingError)
                }
            } finally {
                server.close()
                assertTrue(server.awaitTermination())
                deleteTemporaryDaemonClientSocketPath(socketPath)
            }
        }
    }
}

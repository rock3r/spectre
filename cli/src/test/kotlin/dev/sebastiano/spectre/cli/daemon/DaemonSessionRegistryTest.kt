package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.AttachUnsupportedException
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonSessionRegistryTest {
    @Test
    fun `lists JVM processes through the injected daemon discovery service`() {
        val daemonPid = ProcessHandle.current().pid()
        val processes =
            listOf(
                DaemonJvmProcessSummary(pid = daemonPid, displayName = "spectre daemon"),
                DaemonJvmProcessSummary(
                    pid = 40,
                    displayName =
                        "dev.sebastiano.spectre.cli.daemon.DaemonMainKt --socket daemon-v1-2.sock",
                ),
                DaemonJvmProcessSummary(pid = 30, displayName = "spectre cli"),
                DaemonJvmProcessSummary(pid = 20, displayName = "second"),
                DaemonJvmProcessSummary(pid = 10, displayName = "first"),
            )
        val registry =
            DaemonSessionRegistry(jvmProcessDiscovery = DaemonJvmProcessDiscovery { processes })

        assertEquals(
            DaemonResponse.JvmProcesses(processes.takeLast(2).reversed()),
            registry.handle(DaemonRequest.ListJvmProcesses(requesterPid = 30)),
        )
    }

    @Test
    fun `maps JVM process discovery failures to protocol errors`() {
        val registry =
            DaemonSessionRegistry(
                jvmProcessDiscovery =
                    DaemonJvmProcessDiscovery { throw AttachUnsupportedException() }
            )

        val response =
            assertIs<DaemonResponse.Error>(
                registry.handle(DaemonRequest.ListJvmProcesses(requesterPid = 1234))
            )

        assertEquals(DaemonErrorCode.AttachFailed, response.code)
    }

    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `dispatches every automator operation through the attached session`() {
        val window =
            WindowSummaryDto(
                index = 0,
                surfaceId = "main",
                title = "Fixture",
                isPopup = false,
                bounds = RectDto(x = 0, y = 0, width = 100, height = 100),
            )
        val node =
            NodeSnapshotDto(
                key = "main:0:1",
                testTag = "submit",
                texts = listOf("Submit"),
                role = "Button",
                contentDescription = null,
                isVisible = true,
                bounds = RectDto(x = 1, y = 2, width = 3, height = 4),
            )
        var clicked: String? = null
        var typed: String? = null
        var screenshotArgs: Triple<Int?, String?, Boolean>? = null
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                windowsResult = { listOf(window) },
                nodesResult = { listOf(node) },
                findByTestTagResult = { tag -> if (tag == "submit") listOf(node) else emptyList() },
                clickAction = { nodeKey -> clicked = nodeKey },
                typeTextAction = { text -> typed = text },
                screenshotResult = { windowIndex, surfaceId, fullscreen ->
                    screenshotArgs = Triple(windowIndex, surfaceId, fullscreen)
                    byteArrayOf(1, 2, 3)
                },
            )
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234))).sessionId

        assertEquals(
            DaemonResponse.Windows(sessionId, listOf(window)),
            registry.handle(DaemonRequest.Windows(sessionId)),
        )
        assertEquals(
            DaemonResponse.Nodes(sessionId, listOf(node)),
            registry.handle(DaemonRequest.AllNodes(sessionId)),
        )
        assertEquals(
            DaemonResponse.Nodes(sessionId, listOf(node)),
            registry.handle(DaemonRequest.FindByTestTag(sessionId, "submit")),
        )
        assertEquals(
            DaemonResponse.Completed(sessionId),
            registry.handle(DaemonRequest.Click(sessionId, node.key)),
        )
        assertEquals(node.key, clicked)
        assertEquals(
            DaemonResponse.Completed(sessionId),
            registry.handle(DaemonRequest.TypeText(sessionId, "hello")),
        )
        assertEquals("hello", typed)
        assertEquals(
            DaemonResponse.Screenshot(sessionId, byteArrayOf(1, 2, 3)),
            registry.handle(DaemonRequest.Screenshot(sessionId)),
        )
        assertEquals(Triple(null, null, false), screenshotArgs)
    }

    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `screenshot forwards window surface and fullscreen targeting to session automator`() {
        var screenshotArgs: Triple<Int?, String?, Boolean>? = null
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                screenshotResult = { windowIndex, surfaceId, fullscreen ->
                    screenshotArgs = Triple(windowIndex, surfaceId, fullscreen)
                    byteArrayOf(7)
                }
            )
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(55))).sessionId

        registry.handle(
            DaemonRequest.Screenshot(
                sessionId = sessionId,
                windowIndex = 2,
                surfaceId = "window:2",
                fullscreen = false,
            )
        )
        assertEquals(Triple(2, "window:2", false), screenshotArgs)

        registry.handle(
            DaemonRequest.Screenshot(
                sessionId = sessionId,
                windowIndex = null,
                surfaceId = null,
                fullscreen = true,
            )
        )
        assertEquals(Triple(null, null, true), screenshotArgs)
    }

    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `capture writes artifacts under out dir and returns summary paths`() {
        val outRoot = Files.createTempDirectory("spectre-capture-registry-")
        try {
            val registry = DaemonSessionRegistry {
                TestDaemonSessionAutomator(
                    captureResult = { windowIndex ->
                        AtomicCaptureResult(
                            windowIndex = windowIndex,
                            schemaVersion = 1,
                            captureJson = """{"schemaVersion":1,"nodes":[]}""",
                            pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                            nodeCount = 2,
                            taggedNodeCount = 1,
                            textedNodeCount = 1,
                            imageWidth = 100,
                            imageHeight = 50,
                            captureDurationMs = 9,
                        )
                    }
                )
            }
            val sessionId =
                assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(99)))
                    .sessionId

            val response =
                assertIs<DaemonResponse.Capture>(
                    registry.handle(
                        DaemonRequest.Capture(
                            sessionId = sessionId,
                            windowIndex = 0,
                            outDir = outRoot.toString(),
                        )
                    )
                )

            assertEquals(sessionId, response.sessionId)
            assertEquals(1, response.schemaVersion)
            assertEquals(2, response.nodeCount)
            assertEquals(1, response.taggedNodeCount)
            assertEquals(1, response.textedNodeCount)
            assertEquals(100, response.imageWidth)
            assertEquals(50, response.imageHeight)
            assertEquals(9, response.captureDurationMs)
            assertTrue(Files.isRegularFile(Path.of(response.captureJsonPath)))
            assertTrue(Files.isRegularFile(Path.of(response.screenshotPngPath)))
            assertTrue(Path.of(response.directory).startsWith(outRoot))
            assertEquals(
                """{"schemaVersion":1,"nodes":[]}""",
                Files.readString(Path.of(response.captureJsonPath)),
            )
        } finally {
            outRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `maps automator operation failures to protocol errors`() {
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(windowsResult = { throw IOException("target disconnected") })
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234))).sessionId

        val response =
            assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Windows(sessionId)))

        assertEquals(DaemonErrorCode.OperationFailed, response.code)
    }

    @Test
    fun `starts and stops a recording through the attached session`() {
        val outputPath = "/tmp/spectre-recording.mp4"
        var startedAt: String? = null
        var startedWindowIndex: Int? = null
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                startRecordingAction = { path, windowIndex, _ ->
                    startedAt = path
                    startedWindowIndex = windowIndex
                    path ?: outputPath
                },
                stopRecordingResult = { outputPath },
                recordingStatusResult = {
                    RecordingStatus(active = startedAt != null, outputPath = startedAt)
                },
            )
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234))).sessionId

        assertEquals(
            DaemonResponse.RecordingStarted(sessionId, outputPath),
            registry.handle(DaemonRequest.StartRecording(sessionId, outputPath, windowIndex = 0)),
        )
        assertEquals(outputPath, startedAt)
        assertEquals(0, startedWindowIndex)
        assertEquals(
            DaemonResponse.RecordingStatus(sessionId, active = true, outputPath = outputPath),
            registry.handle(DaemonRequest.RecordingStatus(sessionId)),
        )
        assertEquals(
            DaemonResponse.RecordingStopped(sessionId, outputPath),
            registry.handle(DaemonRequest.StopRecording(sessionId)),
        )
    }

    @Test
    fun `forwards fullscreen recording flag to session automator`() {
        var startedFullscreen: Boolean? = null
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                startRecordingAction = { path, _, fullscreen ->
                    startedFullscreen = fullscreen
                    path ?: "/tmp/full.mp4"
                }
            )
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(9))).sessionId

        registry.handle(
            DaemonRequest.StartRecording(
                sessionId = sessionId,
                outputPath = "/tmp/full.mp4",
                windowIndex = 0,
                fullscreen = true,
            )
        )
        assertEquals(true, startedFullscreen)
    }

    @Test
    fun `maps recording lifecycle failures to operation errors`() {
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                startRecordingAction = { _, _, _ -> throw IOException("recording already started") }
            )
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234))).sessionId

        val response =
            assertIs<DaemonResponse.Error>(
                registry.handle(DaemonRequest.StartRecording(sessionId, "/tmp/capture.mp4"))
            )

        assertEquals(DaemonErrorCode.OperationFailed, response.code)
        assertEquals("recording already started", response.message)
    }

    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `maps agent attach failures to protocol errors`() {
        val registry = DaemonSessionRegistry { throw AttachUnsupportedException() }

        val response = assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Attach(1234)))

        assertEquals(DaemonErrorCode.AttachFailed, response.code)
    }

    @Test
    fun `closes the attached session when detached`() {
        var closes = 0
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(closeAction = { closes++ })
        }

        registry.handle(DaemonRequest.Attach(1234))
        registry.handle(DaemonRequest.Detach("pid-1234"))

        assertEquals(1, closes)
    }

    @Test
    fun `closes every attached session when shutting down`() {
        var closes = 0
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(closeAction = { closes++ })
        }

        registry.handle(DaemonRequest.Attach(1234))
        registry.handle(DaemonRequest.Attach(5678))
        registry.handle(DaemonRequest.Shutdown)

        assertEquals(2, closes)
    }

    @Test
    fun `attach creates stable session ids keyed by pid`() {
        val registry = testRegistry()

        val first = assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234)))
        val second = assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234)))

        assertEquals(first, second)
        assertEquals("pid-1234", first.sessionId)
    }

    @Test
    fun `list sessions returns attached pid summaries`() {
        val registry = testRegistry()

        registry.handle(DaemonRequest.Attach(1234))
        registry.handle(DaemonRequest.Attach(5678))

        val response =
            assertIs<DaemonResponse.Sessions>(registry.handle(DaemonRequest.ListSessions))
        assertEquals(
            listOf(
                DaemonSessionSummary(sessionId = "pid-1234", targetPid = 1234),
                DaemonSessionSummary(sessionId = "pid-5678", targetPid = 5678),
            ),
            response.sessions,
        )
    }

    @Test
    fun `detach removes sessions by id and reports missing sessions`() {
        val registry = testRegistry()
        registry.handle(DaemonRequest.Attach(1234))

        val detached =
            assertIs<DaemonResponse.Detached>(registry.handle(DaemonRequest.Detach("pid-1234")))
        assertEquals("pid-1234", detached.sessionId)
        assertEquals(0, detached.captureCount)
        assertEquals(
            DaemonResponse.Sessions(emptyList()),
            registry.handle(DaemonRequest.ListSessions),
        )

        val missing =
            assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Detach("pid-1234")))
        assertEquals(DaemonErrorCode.SessionNotFound, missing.code)
    }

    @Test
    fun `shutdown clears sessions and rejects subsequent attach`() {
        val registry = testRegistry()
        registry.handle(DaemonRequest.Attach(1234))

        assertEquals(DaemonResponse.ShuttingDown, registry.handle(DaemonRequest.Shutdown))
        assertTrue(registry.isShutdown)
        assertEquals(
            DaemonResponse.Sessions(emptyList()),
            registry.handle(DaemonRequest.ListSessions),
        )

        val rejected = assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Attach(5678)))
        assertEquals(DaemonErrorCode.ShutdownInProgress, rejected.code)
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private fun testRegistry(): DaemonSessionRegistry = DaemonSessionRegistry {
    TestDaemonSessionAutomator()
}

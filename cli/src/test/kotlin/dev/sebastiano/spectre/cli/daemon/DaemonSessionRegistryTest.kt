package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AttachUnsupportedException
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.io.IOException
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
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                windowsResult = { listOf(window) },
                nodesResult = { listOf(node) },
                findByTestTagResult = { tag -> if (tag == "submit") listOf(node) else emptyList() },
                clickAction = { nodeKey -> clicked = nodeKey },
                typeTextAction = { text -> typed = text },
                screenshotResult = { byteArrayOf(1, 2, 3) },
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
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                startRecordingAction = { path ->
                    startedAt = path
                    path
                },
                stopRecordingResult = { outputPath },
            )
        }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234))).sessionId

        assertEquals(
            DaemonResponse.RecordingStarted(sessionId, outputPath),
            registry.handle(DaemonRequest.StartRecording(sessionId, outputPath)),
        )
        assertEquals(outputPath, startedAt)
        assertEquals(
            DaemonResponse.RecordingStopped(sessionId, outputPath),
            registry.handle(DaemonRequest.StopRecording(sessionId)),
        )
    }

    @Test
    fun `maps recording lifecycle failures to operation errors`() {
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                startRecordingAction = { throw IOException("recording already started") }
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

        assertEquals(
            DaemonResponse.Detached("pid-1234"),
            registry.handle(DaemonRequest.Detach("pid-1234")),
        )
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

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    // region #413 multi-session isolation, double-detach, post-detach, concurrent detach

    @Test
    fun `detach of one session leaves sibling session operable and fail-closes the detached id`() {
        val automators = mutableMapOf<Long, TestDaemonSessionAutomator>()
        val registry = DaemonSessionRegistry { pid ->
            TestDaemonSessionAutomator(
                    windowsResult = {
                        listOf(
                            WindowSummaryDto(
                                index = 0,
                                surfaceId = "main-$pid",
                                title = "Session $pid",
                                isPopup = false,
                                bounds = RectDto(0, 0, 10, 10),
                            )
                        )
                    },
                    nodesResult = {
                        listOf(
                            NodeSnapshotDto(
                                key = "node-$pid",
                                testTag = "tag-$pid",
                                texts = emptyList(),
                                role = "Button",
                                contentDescription = null,
                                isVisible = true,
                                bounds = RectDto(0, 0, 1, 1),
                            )
                        )
                    },
                    screenshotResult = { _, _, _ -> byteArrayOf(pid.toByte()) },
                )
                .also { automators[pid] = it }
        }

        val sessionA =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(100))).sessionId
        val sessionB =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(200))).sessionId
        assertNotEquals(sessionA, sessionB)

        val detached =
            assertIs<DaemonResponse.Detached>(registry.handle(DaemonRequest.Detach(sessionA)))
        assertEquals(sessionA, detached.sessionId)

        // Sibling still works.
        val windowsB =
            assertIs<DaemonResponse.Windows>(registry.handle(DaemonRequest.Windows(sessionB)))
        assertEquals("main-200", windowsB.windows.single().surfaceId)
        val nodesB =
            assertIs<DaemonResponse.Nodes>(registry.handle(DaemonRequest.AllNodes(sessionB)))
        assertEquals("node-200", nodesB.nodes.single().key)

        // Detached session fail-closed (not empty success).
        assertSessionNotFound(registry.handle(DaemonRequest.Windows(sessionA)), sessionA)
        assertSessionNotFound(registry.handle(DaemonRequest.AllNodes(sessionA)), sessionA)
        assertSessionNotFound(registry.handle(DaemonRequest.Click(sessionA, "node-100")), sessionA)
        assertSessionNotFound(registry.handle(DaemonRequest.Screenshot(sessionA)), sessionA)

        // Detach never killed the daemon table for remaining sessions.
        val listed = assertIs<DaemonResponse.Sessions>(registry.handle(DaemonRequest.ListSessions))
        assertEquals(
            listOf(DaemonSessionSummary(sessionId = sessionB, targetPid = 200)),
            listed.sessions,
        )
        assertEquals(1, automators.getValue(100).closeCount.get())
        assertEquals(0, automators.getValue(200).closeCount.get())

        // New attach still healthy.
        val sessionC =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(300))).sessionId
        assertEquals("pid-300", sessionC)
    }

    @Test
    fun `double detach fails closed with session-not-found honesty and no second side-effect`() {
        val automator = TestDaemonSessionAutomator()
        val registry = DaemonSessionRegistry { automator }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(42))).sessionId

        val first =
            assertIs<DaemonResponse.Detached>(registry.handle(DaemonRequest.Detach(sessionId)))
        assertEquals(sessionId, first.sessionId)
        assertEquals(1, automator.closeCount.get())
        assertEquals(1, automator.finalizeCount.get())

        val second =
            assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Detach(sessionId)))
        assertEquals(DaemonErrorCode.SessionNotFound, second.code)
        assertTrue(
            second.message.contains("not found", ignoreCase = true) &&
                second.message.contains(sessionId),
            "double-detach message should match unknown-session honesty, was: ${second.message}",
        )
        // No second finalize/close on already-released resources.
        assertEquals(1, automator.closeCount.get())
        assertEquals(1, automator.finalizeCount.get())
    }

    @Test
    fun `post-detach tree click and screenshot fail closed with actionable session not found`() {
        val registry = testRegistry()
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(77))).sessionId
        assertIs<DaemonResponse.Detached>(registry.handle(DaemonRequest.Detach(sessionId)))

        assertSessionNotFound(registry.handle(DaemonRequest.AllNodes(sessionId)), sessionId)
        assertSessionNotFound(registry.handle(DaemonRequest.Click(sessionId, "any-key")), sessionId)
        assertSessionNotFound(registry.handle(DaemonRequest.Screenshot(sessionId)), sessionId)
        assertSessionNotFound(
            registry.handle(DaemonRequest.FindByTestTag(sessionId, "tag")),
            sessionId,
        )
    }

    @Test
    fun `concurrent detach while wait is in flight fails closed without hang or leak`() {
        val waitEntered = CountDownLatch(1)
        val closedDuringWait = CountDownLatch(1)
        val waitResult = AtomicReference<DaemonResponse?>(null)
        val automator =
            TestDaemonSessionAutomator(
                waitForNodeResult = { _, _, _, _ ->
                    waitEntered.countDown()
                    // Block until detach closes the automator, then fail closed (no full timeout
                    // hang).
                    check(closedDuringWait.await(5, TimeUnit.SECONDS)) {
                        "detach did not close automator while wait was in flight"
                    }
                    throw IOException("session closed during waitForNode")
                },
                closeAction = { closedDuringWait.countDown() },
            )
        val registry = DaemonSessionRegistry { automator }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(9001))).sessionId

        val pool = Executors.newFixedThreadPool(2)
        try {
            val waitFuture =
                pool.submit<DaemonResponse> {
                    registry.handle(
                        DaemonRequest.WaitForNode(
                            sessionId = sessionId,
                            tag = "never-appears",
                            timeoutMs = 60_000,
                        )
                    )
                }
            check(waitEntered.await(5, TimeUnit.SECONDS)) { "wait did not start" }

            val detachStarted = System.nanoTime()
            val detached =
                assertIs<DaemonResponse.Detached>(registry.handle(DaemonRequest.Detach(sessionId)))
            assertEquals(sessionId, detached.sessionId)
            val detachMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - detachStarted)
            assertTrue(
                detachMs < 10_000,
                "detach must not hang waiting for the full wait timeout (took ${detachMs}ms)",
            )

            waitResult.set(waitFuture.get(5, TimeUnit.SECONDS))
            val waitError = assertIs<DaemonResponse.Error>(waitResult.get())
            assertEquals(DaemonErrorCode.OperationFailed, waitError.code)
            assertTrue(
                waitError.message.contains("closed", ignoreCase = true) ||
                    waitError.message.contains("session", ignoreCase = true),
                "waiter should fail closed, was: ${waitError.message}",
            )

            assertEquals(
                DaemonResponse.Sessions(emptyList()),
                registry.handle(DaemonRequest.ListSessions),
            )
            assertEquals(1, automator.closeCount.get())

            // Daemon remains healthy for a new attach after concurrent detach.
            val next =
                assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(9002)))
            assertEquals("pid-9002", next.sessionId)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same-PID re-attach waits for detach teardown to finish`() {
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closes = AtomicInteger(0)
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                closeAction = {
                    closes.incrementAndGet()
                    closeEntered.countDown()
                    check(releaseClose.await(5, TimeUnit.SECONDS))
                }
            )
        }
        assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(4242)))

        val pool = Executors.newFixedThreadPool(2)
        try {
            val detachFuture =
                pool.submit<DaemonResponse> { registry.handle(DaemonRequest.Detach("pid-4242")) }
            check(closeEntered.await(5, TimeUnit.SECONDS)) { "detach close did not start" }

            val reattachFuture =
                pool.submit<DaemonResponse> { registry.handle(DaemonRequest.Attach(4242)) }

            // Re-attach must not complete while the previous automator is still closing.
            Thread.sleep(100)
            assertTrue(!reattachFuture.isDone, "re-attach raced ahead of detach teardown")

            releaseClose.countDown()
            assertIs<DaemonResponse.Detached>(detachFuture.get(5, TimeUnit.SECONDS))
            val reattached =
                assertIs<DaemonResponse.Attached>(reattachFuture.get(5, TimeUnit.SECONDS))
            assertEquals("pid-4242", reattached.sessionId)
            assertEquals(1, closes.get(), "first session must close once before re-attach")
        } finally {
            releaseClose.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent detach finalizes an active recording once without leak`() {
        val finalizeLive = AtomicReference<Set<String>?>(null)
        val stopCount = AtomicInteger(0)
        val automator =
            TestDaemonSessionAutomator(
                startRecordingAction = { path, _, _ -> path ?: "/tmp/rec.mp4" },
                stopRecordingResult = { live ->
                    stopCount.incrementAndGet()
                    finalizeLive.set(live)
                    "/tmp/rec.mp4"
                },
                finalizeRecordingAction = { live ->
                    // Mirror production: finalize stops an active recording.
                    if (stopCount.get() == 0) {
                        stopCount.incrementAndGet()
                        finalizeLive.set(live)
                    }
                },
            )
        val registry = DaemonSessionRegistry { automator }
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(55))).sessionId
        assertIs<DaemonResponse.RecordingStarted>(
            registry.handle(DaemonRequest.StartRecording(sessionId, "/tmp/rec.mp4"))
        )

        val detached =
            assertIs<DaemonResponse.Detached>(registry.handle(DaemonRequest.Detach(sessionId)))
        assertEquals(sessionId, detached.sessionId)
        assertEquals(1, automator.finalizeCount.get())
        assertEquals(1, automator.closeCount.get())
        assertEquals(
            DaemonResponse.Sessions(emptyList()),
            registry.handle(DaemonRequest.ListSessions),
        )
        // Sibling table empty → remaining live set empty.
        assertEquals(emptySet<String>(), finalizeLive.get())
    }

    // endregion
}

private fun assertSessionNotFound(response: DaemonResponse, sessionId: String) {
    val error = assertIs<DaemonResponse.Error>(response)
    assertEquals(DaemonErrorCode.SessionNotFound, error.code)
    assertTrue(
        error.message.contains("not found", ignoreCase = true) && error.message.contains(sessionId),
        "expected actionable session-not-found for $sessionId, was: ${error.message}",
    )
}

@OptIn(ExperimentalSpectreAgentApi::class)
private fun testRegistry(): DaemonSessionRegistry = DaemonSessionRegistry {
    TestDaemonSessionAutomator()
}

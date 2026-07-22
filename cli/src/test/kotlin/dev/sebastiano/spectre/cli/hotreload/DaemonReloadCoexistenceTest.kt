package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionRegistry
import dev.sebastiano.spectre.cli.daemon.TestDaemonSessionAutomator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #212 coexistence contract at the daemon session boundary:
 * - attach remains usable across a simulated reload settle
 * - atomic capture succeeds before and after settle
 * - pre-reload stamped keys fail closed as nodeNotFound
 * - a fresh tree after settle yields usable keys for input
 *
 * Full class-redefine + JdwpTracker coexistence needs a live HR `hotRun` process (#208 still open).
 * This test exercises the Spectre-owned path that product code actually runs after orchestration
 * reports settle: key flush + attach/capture stay healthy without Spectre calling
 * `Instrumentation.redefineClasses` (see [SpectreDoesNotRedefineClassesContractTest]).
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonReloadCoexistenceTest {
    @Test
    fun `attach tree capture survives reload settle and rejects stale keys`() {
        val hr = ControllableHotReloadCapability(ConcurrentLinkedQueue())
        val generation = AtomicInteger(0)
        val clicked = mutableListOf<String>()
        val captureCalls = AtomicInteger(0)

        val registry =
            DaemonSessionRegistry(
                hotReloadSessionFactory = { hr },
                attachAutomator = {
                    TestDaemonSessionAutomator(
                        nodesResult = {
                            val gen = generation.get()
                            listOf(sampleNode(rawKey = "main:0:$gen", tag = "CounterValue"))
                        },
                        clickAction = { key -> clicked += key },
                        captureResult = { windowIndex ->
                            captureCalls.incrementAndGet()
                            AtomicCaptureResult(
                                windowIndex = windowIndex,
                                schemaVersion = 1,
                                captureJson =
                                    """{"schemaVersion":1,"nodes":[{"key":"raw-in-json"}]}""",
                                pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                                nodeCount = 1,
                                taggedNodeCount = 1,
                                textedNodeCount = 0,
                                imageWidth = 10,
                                imageHeight = 10,
                                captureDurationMs = 1,
                            )
                        },
                    )
                },
            )

        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(4242))).sessionId

        val preTree =
            assertIs<DaemonResponse.Nodes>(registry.handle(DaemonRequest.AllNodes(sessionId)))
        val preKey = preTree.nodes.single().key
        assertTrue(preKey.startsWith("g0:"), "expected stamped pre-reload key, got $preKey")

        // Capture while attached and pre-reload.
        assertIs<DaemonResponse.Capture>(
            registry.handle(DaemonRequest.Capture(sessionId = sessionId, windowIndex = 0))
        )
        assertEquals(1, captureCalls.get())

        // Simulated HR settle (orchestration already drained in production by HotReloadSession).
        hr.fireReloadSettled()
        generation.set(1)

        val stale =
            assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Click(sessionId, preKey)))
        assertEquals(DaemonErrorCode.OperationFailed, stale.code)
        assertEquals("nodeNotFound", stale.category)
        assertTrue(clicked.isEmpty(), "stale key must not dispatch click")

        // Tree re-read after reload.
        val postTree =
            assertIs<DaemonResponse.Nodes>(registry.handle(DaemonRequest.AllNodes(sessionId)))
        val postKey = postTree.nodes.single().key
        assertTrue(postKey.startsWith("g1:"), "expected new generation stamp, got $postKey")
        assertTrue(postKey != preKey)

        // Capture still works after reload settle (attach session still healthy).
        assertIs<DaemonResponse.Capture>(
            registry.handle(DaemonRequest.Capture(sessionId = sessionId, windowIndex = 0))
        )
        assertEquals(2, captureCalls.get())

        assertIs<DaemonResponse.Completed>(registry.handle(DaemonRequest.Click(sessionId, postKey)))
        assertEquals(listOf("main:0:1"), clicked)

        // Explicit wait still works after coexistence path (controllable returns Unavailable when
        // queue empty — proves the session still has an HR capability wired).
        val wait =
            assertIs<DaemonResponse.Error>(
                registry.handle(
                    DaemonRequest.WaitForReloadSettled(sessionId = sessionId, timeoutMs = 50)
                )
            )
        assertEquals(DaemonErrorCode.HotReloadUnavailable, wait.code)

        registry.close()
    }

    private fun sampleNode(rawKey: String, tag: String): NodeSnapshotDto =
        NodeSnapshotDto(
            key = rawKey,
            testTag = tag,
            texts = emptyList(),
            role = "Text",
            contentDescription = null,
            isVisible = true,
            bounds = RectDto(x = 0, y = 0, width = 10, height = 10),
        )
}

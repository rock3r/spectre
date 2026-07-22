package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionRegistry
import dev.sebastiano.spectre.cli.daemon.TestDaemonSessionAutomator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonKeyInvalidationTest {
    @Test
    fun `reload-aware session rejects pre-reload node keys with nodeNotFound`() {
        val outcomes = ConcurrentLinkedQueue<ReloadSettleOutcome>()
        val hr = ControllableHotReloadCapability(outcomes)
        val preKey = "main:0:1"
        val postKey = "main:0:99"
        var treeGeneration = 0
        val clicked = mutableListOf<String>()
        val registry =
            DaemonSessionRegistry(
                hotReloadSessionFactory = { hr },
                attachAutomator = {
                    TestDaemonSessionAutomator(
                        nodesResult = {
                            val key = if (treeGeneration == 0) preKey else postKey
                            listOf(sampleNode(key))
                        },
                        clickAction = { key -> clicked += key },
                    )
                },
            )
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(55))).sessionId

        val tree =
            assertIs<DaemonResponse.Nodes>(registry.handle(DaemonRequest.AllNodes(sessionId)))
        val stampedPre = tree.nodes.single().key
        assertTrue(stampedPre.startsWith("g0:"))
        assertTrue(stampedPre.endsWith(preKey))

        hr.fireReloadSettled()
        treeGeneration = 1

        val rejected =
            assertIs<DaemonResponse.Error>(
                registry.handle(DaemonRequest.Click(sessionId, stampedPre))
            )
        assertEquals(DaemonErrorCode.OperationFailed, rejected.code)
        assertEquals("nodeNotFound", rejected.category)
        assertTrue(clicked.isEmpty())

        val fresh =
            assertIs<DaemonResponse.Nodes>(registry.handle(DaemonRequest.AllNodes(sessionId)))
        val stampedPost = fresh.nodes.single().key
        assertTrue(stampedPost.startsWith("g1:"))
        assertIs<DaemonResponse.Completed>(
            registry.handle(DaemonRequest.Click(sessionId, stampedPost))
        )
        assertEquals(listOf(postKey), clicked)
    }

    @Test
    fun `non-reload-aware session does not guard node keys`() {
        val registry =
            DaemonSessionRegistry(
                hotReloadSessionFactory = { null },
                attachAutomator = {
                    TestDaemonSessionAutomator(
                        nodesResult = { listOf(sampleNode("k1")) },
                        clickAction = {},
                    )
                },
            )
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1))).sessionId
        assertIs<DaemonResponse.Completed>(
            registry.handle(DaemonRequest.Click(sessionId, "never-issued"))
        )
    }

    private fun sampleNode(key: String): NodeSnapshotDto =
        NodeSnapshotDto(
            key = key,
            testTag = "t",
            texts = emptyList(),
            role = "Text",
            contentDescription = null,
            isVisible = true,
            bounds = RectDto(x = 0, y = 0, width = 10, height = 10),
        )
}

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `focusWindow` over the daemon (#556). The agent raises the window that hosts a node, so the
 * daemon turns the CLI's window index into a node key inside that window.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonFocusWindowTest {

    @Test
    fun `focusWindow focuses the window at the requested index through one of its nodes`() {
        val focused = mutableListOf<String>()
        val registry = registry(focusWindowAction = { focused += it })
        val sessionId = attach(registry)

        val response = registry.handle(DaemonRequest.FocusWindow(sessionId, windowIndex = 1))

        assertEquals(DaemonResponse.Completed(sessionId), response)
        assertEquals(listOf("popup:1:0:3"), focused)
    }

    @Test
    fun `focusWindow defaults to the first window`() {
        val focused = mutableListOf<String>()
        val registry = registry(focusWindowAction = { focused += it })
        val sessionId = attach(registry)

        val response = registry.handle(DaemonRequest.FocusWindow(sessionId))

        assertEquals(DaemonResponse.Completed(sessionId), response)
        assertEquals(listOf("main:0:0:1"), focused)
    }

    @Test
    fun `focusWindow rejects a window index the session does not have`() {
        val focused = mutableListOf<String>()
        val registry = registry(focusWindowAction = { focused += it })
        val sessionId = attach(registry)

        val response = registry.handle(DaemonRequest.FocusWindow(sessionId, windowIndex = 7))

        val error = assertIs<DaemonResponse.Error>(response)
        assertEquals(DaemonErrorCode.OperationFailed, error.code)
        assertEquals(AgentErrorCategory.InvalidSelector.wireName, error.category)
        assertTrue(error.message.contains("7"), error.message)
        assertEquals(emptyList(), focused)
    }

    @Test
    fun `focusWindow reports a window with no semantics nodes`() {
        val focused = mutableListOf<String>()
        val registry =
            registry(focusWindowAction = { focused += it }, nodes = listOf(node("main:0:0:1")))
        val sessionId = attach(registry)

        val response = registry.handle(DaemonRequest.FocusWindow(sessionId, windowIndex = 1))

        val error = assertIs<DaemonResponse.Error>(response)
        assertEquals(DaemonErrorCode.OperationFailed, error.code)
        assertEquals(AgentErrorCategory.NodeNotFound.wireName, error.category)
        assertEquals(emptyList(), focused)
    }

    @Test
    fun `focusWindow fails closed for an unknown session`() {
        val response = registry().handle(DaemonRequest.FocusWindow("pid-missing"))

        val error = assertIs<DaemonResponse.Error>(response)
        assertEquals(DaemonErrorCode.SessionNotFound, error.code)
    }

    @Test
    fun `focusWindow needs a daemon that knows the op`() {
        assertEquals(
            DaemonProtocolVersion(major = 1, minor = 14),
            DaemonProtocol.minimumDaemonVersion(DaemonRequest.FocusWindow("pid-1")),
        )
    }

    private fun registry(
        focusWindowAction: (String) -> Unit = {},
        nodes: List<NodeSnapshotDto> = listOf(node("main:0:0:1"), node("popup:1:0:3")),
    ): DaemonSessionRegistry = DaemonSessionRegistry {
        TestDaemonSessionAutomator(
            windowsResult = { listOf(window(0, "main:0"), window(1, "popup:1")) },
            nodesResult = { nodes },
            focusWindowAction = focusWindowAction,
        )
    }

    private fun attach(registry: DaemonSessionRegistry): String =
        assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(4242))).sessionId

    private fun window(index: Int, surfaceId: String): WindowSummaryDto =
        WindowSummaryDto(
            index = index,
            surfaceId = surfaceId,
            title = null,
            isPopup = index > 0,
            bounds = RectDto(0, 0, 100, 100),
        )

    private fun node(key: String): NodeSnapshotDto =
        NodeSnapshotDto(
            key = key,
            testTag = null,
            texts = emptyList(),
            role = null,
            contentDescription = null,
            isVisible = true,
            bounds = RectDto(0, 0, 10, 10),
        )
}

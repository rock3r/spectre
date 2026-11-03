@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.state.ToggleableState
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.ProgressRangeDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.core.BothBounds
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ReflectiveAutomatorHandler] mapping of the node state that `NodeSnapshotDto` gained in #576:
 * clickability, toggleable state, progress range, tree links, and window-relative bounds.
 *
 * Like `ReflectiveAutomatorHandlerMappingTest`, it uses stand-ins with the same getter names as
 * `AutomatorNode`. The values they return are real Compose types, because those are what the
 * reflective mapping has to cope with at runtime.
 */
class NodeStateMappingTest {

    @Test
    fun `maps clickable, toggleable, progress, tree links, and window bounds`() {
        val node =
            StateNode(
                key = "surface-0:0:42",
                isClickable = true,
                // ProgressBarRangeInfo.range is a stdlib ClosedFloatingPointRange whose
                // implementation class is not public.
                toggleableState = ToggleableState.Indeterminate,
                progressBarRangeInfo =
                    ProgressBarRangeInfo(current = 0.25f, range = 0f..2f, steps = 3),
                parent = StateNode(key = "surface-0:0:1"),
                children =
                    listOf(StateNode(key = "surface-0:0:43"), StateNode(key = "surface-0:0:44")),
                boundsInWindow = Rect(left = 10.4f, top = 20.6f, right = 110.4f, bottom = 70.6f),
                boundsOnScreen = Rectangle(100, 200, 50, 25),
            )

        val dto = mapSingle(node)

        assertTrue(dto.isClickable)
        assertEquals("Indeterminate", dto.toggleableState)
        assertEquals(ProgressRangeDto(current = 0.25f, min = 0f, max = 2f, steps = 3), dto.progress)
        assertEquals("surface-0:0:1", dto.parentKey)
        assertEquals(listOf("surface-0:0:43", "surface-0:0:44"), dto.childKeys)
        assertEquals(RectDto(x = 10, y = 21, width = 100, height = 50), dto.boundsInWindow)
        assertEquals(RectDto(x = 100, y = 200, width = 50, height = 25), dto.bounds)
    }

    @Test
    fun `maps each toggleable state to its Compose name`() {
        val nodes =
            ToggleableState.entries.mapIndexed { index, state ->
                StateNode(key = "surface-0:0:$index", toggleableState = state)
            }

        val response =
            ReflectiveAutomatorHandler(NodeListAutomator(nodes)).handle(AgentRequest.AllNodes)

        check(response is AgentResponse.Nodes)
        assertEquals(
            listOf("On", "Off", "Indeterminate"),
            response.nodes.map { it.toggleableState },
        )
    }

    @Test
    fun `leaves node state fields at their defaults for a plain node`() {
        val dto = mapSingle(StateNode(key = "plain"))

        assertFalse(dto.isClickable)
        assertNull(dto.toggleableState)
        assertNull(dto.progress)
        assertNull(dto.parentKey)
        assertTrue(dto.childKeys.isEmpty())
        assertEquals(RectDto(0, 0, 0, 0), dto.boundsInWindow)
    }

    @Test
    fun `maps an AutomatorNode that predates the node state accessors`() {
        // A runtime-side AutomatorNode from before #576 has no isClickable / toggleable /
        // progress / parent / children / bothBounds accessors. Mapping must fall back to the
        // wire defaults rather than fail.
        val dto = mapSingle(LegacyNode(key = "legacy", boundsOnScreen = Rectangle(1, 2, 3, 4)))

        assertFalse(dto.isClickable)
        assertNull(dto.toggleableState)
        assertNull(dto.progress)
        assertNull(dto.parentKey)
        assertTrue(dto.childKeys.isEmpty())
        assertNull(dto.boundsInWindow)
        assertEquals(RectDto(x = 1, y = 2, width = 3, height = 4), dto.bounds)
    }

    private fun mapSingle(node: Any): NodeSnapshotDto {
        val response =
            ReflectiveAutomatorHandler(NodeListAutomator(listOf(node)))
                .handle(AgentRequest.AllNodes)
        check(response is AgentResponse.Nodes) {
            "expected AgentResponse.Nodes, got ${response::class.simpleName}: $response"
        }
        return response.nodes.single()
    }
}

/** The `ComposeAutomator` methods [ReflectiveAutomatorHandler] needs, serving a fixed node list. */
private class NodeListAutomator(private val nodes: List<Any>) {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = nodes

    @Suppress("unused")
    fun findByTestTag(@Suppress("UNUSED_PARAMETER") tag: String): List<Any> = nodes
}

/** Stand-in for an `AutomatorNode` with the node state accessors added in #576. */
@Suppress("LongParameterList")
private class StateNode(
    private val key: String,
    private val isClickable: Boolean = false,
    private val toggleableState: ToggleableState? = null,
    private val progressBarRangeInfo: ProgressBarRangeInfo? = null,
    private val parent: StateNode? = null,
    private val children: List<StateNode> = emptyList(),
    private val boundsInWindow: Rect = Rect.Zero,
    private val boundsOnScreen: Rectangle = Rectangle(0, 0, 0, 0),
    private val isVisible: Boolean = true,
    private val editableText: String? = null,
) {
    @Suppress("unused") fun getKey(): String = key

    @Suppress("unused") fun getEditableText(): String? = editableText

    @Suppress("unused") fun isVisible(): Boolean = isVisible

    @Suppress("unused") fun getBoundsOnScreen(): Rectangle = boundsOnScreen

    @Suppress("unused") fun isClickable(): Boolean = isClickable

    @Suppress("unused") fun getToggleableState(): ToggleableState? = toggleableState

    @Suppress("unused") fun getProgressBarRangeInfo(): ProgressBarRangeInfo? = progressBarRangeInfo

    @Suppress("unused") fun getParent(): StateNode? = parent

    @Suppress("unused") fun getChildren(): List<StateNode> = children

    @Suppress("unused")
    fun bothBounds(): BothBounds = BothBounds(inWindow = boundsInWindow, onScreen = boundsOnScreen)
}

/** Stand-in for an `AutomatorNode` built before #576 added the node state accessors. */
private class LegacyNode(
    private val key: String,
    private val boundsOnScreen: Rectangle,
    private val isVisible: Boolean = true,
    private val editableText: String? = null,
) {
    @Suppress("unused") fun getKey(): String = key

    @Suppress("unused") fun getEditableText(): String? = editableText

    @Suppress("unused") fun isVisible(): Boolean = isVisible

    @Suppress("unused") fun getBoundsOnScreen(): Rectangle = boundsOnScreen
}

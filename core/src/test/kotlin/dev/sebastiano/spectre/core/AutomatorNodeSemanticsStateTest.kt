package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Headless tests for the toggleable and progress state that [AutomatorNode] snapshots from a node's
 * semantics configuration (#576). A real `SemanticsNode` needs a live Compose surface, so these
 * tests drive the same readers through a hand-built [SemanticsConfiguration]. The end-to-end path
 * (real Compose nodes through the agent wire) is covered by `AgentAttachIntegrationTest`.
 */
class AutomatorNodeSemanticsStateTest {

    @Test
    fun `toggleable state is read from the ToggleableState semantics property`() {
        for (state in ToggleableState.entries) {
            val config =
                SemanticsConfiguration().apply { set(SemanticsProperties.ToggleableState, state) }

            assertEquals(state, config.toggleableStateOrNull())
        }
    }

    @Test
    fun `toggleable state is null when the node is not toggleable`() {
        assertNull(SemanticsConfiguration().toggleableStateOrNull())
    }

    @Test
    fun `progress range info is read from the ProgressBarRangeInfo semantics property`() {
        val info = ProgressBarRangeInfo(current = 0.25f, range = 0f..1f, steps = 3)
        val config =
            SemanticsConfiguration().apply { set(SemanticsProperties.ProgressBarRangeInfo, info) }

        val read = config.progressBarRangeInfoOrNull()

        assertEquals(0.25f, read?.current)
        assertEquals(0f..1f, read?.range)
        assertEquals(3, read?.steps)
    }

    @Test
    fun `progress range info is null when the node has no progress semantics`() {
        assertNull(SemanticsConfiguration().progressBarRangeInfoOrNull())
    }
}

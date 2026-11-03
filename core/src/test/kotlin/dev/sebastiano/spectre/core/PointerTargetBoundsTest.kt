package dev.sebastiano.spectre.core

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The guard behind every node-targeted pointer action (#564).
 *
 * A node clipped entirely out of its scroll container's viewport keeps empty in-window bounds.
 * Converting those to screen space clamps them to 1x1 at the surface origin, so without this guard
 * `click(node)` lands on whatever sits at the surface's top-left corner. The live end-to-end case
 * is in sample-desktop's `OffViewportPointerValidationTest`; this pins the bounds rule headlessly.
 */
class PointerTargetBoundsTest {

    @Test
    fun `zero bounds fail fast with a message that names the action and suggests scrolling`() {
        val error =
            assertFailsWith<IllegalStateException> {
                checkPointerTargetBounds(Rect.Zero, action = "click", nodeDescription = NODE)
            }

        val message = error.message.orEmpty()
        assertTrue("Cannot click $NODE" in message, "names action and node: $message")
        assertTrue("bounds are empty" in message, "explains the empty bounds: $message")
        assertTrue("viewport" in message, "names the likely cause: $message")
        assertTrue("Scroll it into view" in message, "suggests scrolling first: $message")
    }

    @Test
    fun `bounds clipped to a zero-height strip fail too`() {
        // A node clipped at the viewport edge can collapse to a line rather than Rect.Zero.
        assertFailsWith<IllegalStateException> {
            checkPointerTargetBounds(
                Rect(left = 0f, top = 480f, right = 200f, bottom = 480f),
                action = "scroll the wheel over",
                nodeDescription = NODE,
            )
        }
    }

    @Test
    fun `non-empty bounds pass`() {
        checkPointerTargetBounds(
            Rect(left = 10f, top = 20f, right = 110f, bottom = 60f),
            action = "click",
            nodeDescription = NODE,
        )
    }

    @Test
    fun `message is not mistaken for an OS input refusal on the attach path`() {
        // :agent classifies IllegalStateExceptions as inputRejected by matching these words. An
        // off-viewport node is a caller problem, not the OS refusing input.
        val message =
            assertFailsWith<IllegalStateException> {
                    checkPointerTargetBounds(Rect.Zero, action = "click", nodeDescription = NODE)
                }
                .message
                .orEmpty()
                .lowercase()

        for (word in listOf("accessibility", "tcc", "permission", "screen recording")) {
            assertTrue(word !in message, "message must not contain '$word': $message")
        }
        assertTrue("input desktop" !in message && "issues/460" !in message, message)
    }

    private companion object {
        const val NODE = "AutomatorNode(key=main:0:42, tag=row.49)"
    }
}

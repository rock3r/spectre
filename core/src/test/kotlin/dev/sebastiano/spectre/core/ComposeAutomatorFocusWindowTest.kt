package dev.sebastiano.spectre.core

import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComposeAutomatorFocusWindowTest {
    @Test
    fun `focus handoff refuses when the target window is hidden`() {
        assumeLiveAwtAvailable()
        val hidden = JFrame("hidden target")
        try {
            var failure: Throwable? = null
            SwingUtilities.invokeAndWait {
                failure = runCatching { requestAndVerifyWindowFocus(hidden) }.exceptionOrNull()
            }

            assertContains(
                assertIs<IllegalStateException>(failure).message.orEmpty(),
                "showing and focusable",
            )
            assertTrue(hidden.windowFocusListeners.isEmpty())
        } finally {
            SwingUtilities.invokeAndWait { hidden.dispose() }
        }
    }

    @Test
    fun `focus handoff refuses a showing window that cannot receive focus`() {
        assumeLiveAwtAvailable()
        val nonFocusable = JFrame("non-focusable target")
        try {
            SwingUtilities.invokeAndWait {
                nonFocusable.focusableWindowState = false
                nonFocusable.setSize(200, 100)
                nonFocusable.isVisible = true
            }

            val failure =
                assertFailsWith<IllegalStateException> { requestAndVerifyWindowFocus(nonFocusable) }

            assertContains(failure.message.orEmpty(), "showing and focusable")
            assertTrue(nonFocusable.windowFocusListeners.isEmpty())
        } finally {
            SwingUtilities.invokeAndWait { nonFocusable.dispose() }
        }
    }
}

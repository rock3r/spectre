package dev.sebastiano.spectre.agent.runtime

import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

class WindowChangeCaptureTest {
    @Test
    fun `title ABA is observed but child navigation is harmless`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT")
        val frame = onEdt { JFrame("A").apply { add(JButton("one"), BorderLayout.CENTER) } }
        try {
            WindowChangeCapture.begin().use { capture ->
                onEdt {
                    frame.title = "B"
                    frame.title = "A"
                }
                assertTrue(capture.changeGeneration > 0)
            }

            WindowChangeCapture.begin().use { childCapture ->
                onEdt { (frame.contentPane.getComponent(0) as JButton).text = "two" }
                assertEquals(0, childCapture.changeGeneration)
            }
        } finally {
            onEdt { frame.dispose() }
        }
    }
}

private fun <T> onEdt(block: () -> T): T {
    var value: T? = null
    SwingUtilities.invokeAndWait { value = block() }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

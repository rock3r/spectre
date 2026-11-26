package dev.sebastiano.spectre.agent.runtime

import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledIfSystemProperty

@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
class WindowChangeCaptureTest {
    @Test
    fun `title ABA is observed but child navigation is harmless`() {
        val frame = onEdt { JFrame("A").apply { add(JButton("one"), BorderLayout.CENTER) } }
        try {
            val capture = WindowChangeCapture.begin()
            onEdt {
                frame.title = "B"
                frame.title = "A"
            }
            assertTrue(capture.changeGeneration > 0)
            capture.close()

            val childCapture = WindowChangeCapture.begin()
            onEdt { (frame.contentPane.getComponent(0) as JButton).text = "two" }
            assertEquals(0, childCapture.changeGeneration)
            childCapture.close()
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

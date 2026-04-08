package dev.sebastiano.spectre.core

import java.awt.Container
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowTrackerTest {

    @Test
    fun `findComposePanels returns empty for plain Swing container`() {
        val frame = JFrame()
        val panel = JPanel()
        frame.contentPane.add(panel)
        val result = findComposePanels(frame.contentPane as Container)
        assertTrue(result.isEmpty())
        frame.dispose()
    }

    @Test
    fun `findComposePanels returns empty for empty container`() {
        val panel = JPanel()
        val result = findComposePanels(panel)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findComposePanels searches nested containers`() {
        // Build a 3-level Swing hierarchy with no ComposePanel
        val root = JPanel()
        val child = JPanel()
        val grandchild = JPanel()
        child.add(grandchild)
        root.add(child)
        val result = findComposePanels(root)
        assertEquals(0, result.size)
    }
}

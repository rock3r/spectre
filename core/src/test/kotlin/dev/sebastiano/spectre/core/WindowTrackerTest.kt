package dev.sebastiano.spectre.core

import java.awt.Container
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIf

class WindowTrackerTest {

    @EnabledIf("liveAwtAvailable")
    @Test
    fun `findComposePanels returns empty for plain Swing container`() {
        val frame = JFrame()
        val panel = Container()
        frame.contentPane.add(panel)
        val result = findComposePanels(frame.contentPane as Container)
        assertTrue(result.isEmpty())
        frame.dispose()
    }

    @Test
    fun `findComposePanels returns empty for empty container`() {
        val panel = Container()
        val result = findComposePanels(panel)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findComposePanels searches nested containers`() {
        val root = Container()
        val child = Container()
        val grandchild = Container()
        child.add(grandchild)
        root.add(child)
        val result = findComposePanels(root)
        assertEquals(0, result.size)
    }

    companion object {

        @JvmStatic
        fun liveAwtAvailable(): Boolean =
            !GraphicsEnvironment.isHeadless() &&
                (!detectMacOs() || System.getProperty("spectre.test.liveAwt").toBoolean())
    }
}

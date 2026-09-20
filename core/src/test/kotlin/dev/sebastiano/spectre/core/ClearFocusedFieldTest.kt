package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ClearFocusedFieldTest {

    @Test
    fun `clearAndTypeText uses shortcut then Backspace before typing`() = runTest {
        val robot = RecordingClearRobotAdapter()
        val driver = RobotDriver(robot, EmptyClipboardAdapter())
        val selectAll = shortcutModifierKeyCode(detectMacOs())

        driver.clearAndTypeText("ab")

        assertEquals(
            listOf(
                "keyPress($selectAll)",
                "keyPress(${KeyEvent.VK_A})",
                "keyRelease(${KeyEvent.VK_A})",
                "keyRelease($selectAll)",
                "waitForIdle()",
                "keyPress(${KeyEvent.VK_BACK_SPACE})",
                "keyRelease(${KeyEvent.VK_BACK_SPACE})",
                "waitForIdle()",
                "keyPress(${KeyEvent.VK_A})",
                "keyRelease(${KeyEvent.VK_A})",
                "keyPress(${KeyEvent.VK_B})",
                "keyRelease(${KeyEvent.VK_B})",
            ),
            robot.events,
        )
    }

    @Test
    fun `clearAndTypeText uses Home Shift End only when the adapter needs a line fallback`() =
        runTest {
            val robot = RecordingClearRobotAdapter(needsSelectAllLineFallback = true)
            val driver = RobotDriver(robot, EmptyClipboardAdapter())
            val selectAll = shortcutModifierKeyCode(detectMacOs())

            driver.clearAndTypeText("ab")

            assertEquals(
                listOf(
                    "keyPress($selectAll)",
                    "keyPress(${KeyEvent.VK_A})",
                    "keyRelease(${KeyEvent.VK_A})",
                    "keyRelease($selectAll)",
                    "waitForIdle()",
                    "keyPress(${KeyEvent.VK_HOME})",
                    "keyRelease(${KeyEvent.VK_HOME})",
                    "keyPress(${KeyEvent.VK_SHIFT})",
                    "keyPress(${KeyEvent.VK_END})",
                    "keyRelease(${KeyEvent.VK_END})",
                    "keyRelease(${KeyEvent.VK_SHIFT})",
                    "waitForIdle()",
                    "keyPress(${KeyEvent.VK_BACK_SPACE})",
                    "keyRelease(${KeyEvent.VK_BACK_SPACE})",
                    "waitForIdle()",
                    "keyPress(${KeyEvent.VK_A})",
                    "keyRelease(${KeyEvent.VK_A})",
                    "keyPress(${KeyEvent.VK_B})",
                    "keyRelease(${KeyEvent.VK_B})",
                ),
                robot.events,
            )
        }
}

private class RecordingClearRobotAdapter(
    override val autoDelayMs: Int = 0,
    override val needsSelectAllLineFallback: Boolean = false,
) : RobotAdapter {
    override val requiresOffEdt: Boolean = false
    val events = mutableListOf<String>()

    override fun mouseMove(x: Int, y: Int) = Unit

    override fun mousePress(buttons: Int) = Unit

    override fun mouseRelease(buttons: Int) = Unit

    override fun keyPress(keyCode: Int) {
        events += "keyPress($keyCode)"
    }

    override fun keyRelease(keyCode: Int) {
        events += "keyRelease($keyCode)"
    }

    override fun mouseWheel(wheelClicks: Int) = Unit

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    override fun waitForIdle() {
        events += "waitForIdle()"
    }
}

private class EmptyClipboardAdapter : ClipboardAdapter {
    override fun getContents(): Transferable? = null

    override fun setContents(contents: Transferable) = Unit
}

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `clearAndTypeText never narrows a successful multiline select all to one line`() = runTest {
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
    fun `clearAndTypeText preflights unsupported text before clearing`() = runTest {
        val robot = RecordingClearRobotAdapter()
        val driver = RobotDriver(robot, EmptyClipboardAdapter())

        assertFailsWith<IllegalArgumentException> { driver.clearAndTypeText("valid then é") }

        assertEquals(emptyList(), robot.events)
    }
}

private class RecordingClearRobotAdapter(override val autoDelayMs: Int = 0) : RobotAdapter {
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

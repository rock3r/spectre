package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    @Test
    fun `clearAndTypeText leaves multiline contents untouched when select all is unreliable`() =
        runTest {
            val original = "first line\nsecond line"
            val robot = StatefulClearRobotAdapter(original, supportsReliableSelectAll = false)
            val driver = RobotDriver(robot, EmptyClipboardAdapter())

            val failure =
                assertFailsWith<IllegalStateException> { driver.clearAndTypeText("replacement") }

            assertTrue(failure.message.orEmpty().contains("select-all"))
            assertEquals(original, robot.contents)
            assertEquals(emptyList(), robot.events)
        }

    @Test
    fun `clearAndTypeText fully replaces multiline contents on a reliable select all path`() =
        runTest {
            val robot = StatefulClearRobotAdapter("first line\nsecond line")
            val driver = RobotDriver(robot, EmptyClipboardAdapter())

            driver.clearAndTypeText("replacement")

            assertEquals("replacement", robot.contents)
        }

    @Test
    fun `stateful adapter recognizes both platform select all modifiers`() = runTest {
        for (isMacOs in listOf(false, true)) {
            val selectAllModifier = shortcutModifierKeyCode(isMacOs)
            val robot =
                StatefulClearRobotAdapter(
                    initialContents = "first line\nsecond line",
                    selectAllModifier = selectAllModifier,
                )

            robot.clearFocusedField(selectAllModifier)

            assertEquals("", robot.contents)
        }
    }
}

private class StatefulClearRobotAdapter(
    initialContents: String,
    override val supportsReliableSelectAll: Boolean = true,
    private val selectAllModifier: Int = shortcutModifierKeyCode(detectMacOs()),
) : RobotAdapter {
    override val autoDelayMs: Int = 0
    override val requiresOffEdt: Boolean = false
    val events = mutableListOf<String>()
    var contents: String = initialContents
        private set

    private var selectAllModifierPressed = false
    private var allSelected = false

    override fun mouseMove(x: Int, y: Int) = Unit

    override fun mousePress(buttons: Int) = Unit

    override fun mouseRelease(buttons: Int) = Unit

    override fun mouseWheel(wheelClicks: Int) = Unit

    override fun waitForIdle() = Unit

    override fun keyPress(keyCode: Int) {
        events += "press($keyCode)"
        when (keyCode) {
            selectAllModifier -> selectAllModifierPressed = true
            KeyEvent.VK_A ->
                if (selectAllModifierPressed && supportsReliableSelectAll) allSelected = true
                else type('a')
            KeyEvent.VK_BACK_SPACE ->
                if (allSelected) {
                    contents = ""
                    allSelected = false
                } else if (contents.isNotEmpty()) {
                    contents = contents.dropLast(1)
                }
            in KeyEvent.VK_B..KeyEvent.VK_Z -> type(('a'.code + keyCode - KeyEvent.VK_A).toChar())
        }
    }

    override fun keyRelease(keyCode: Int) {
        events += "release($keyCode)"
        if (keyCode == selectAllModifier) selectAllModifierPressed = false
    }

    private fun type(character: Char) {
        if (allSelected) {
            contents = ""
            allSelected = false
        }
        contents += character
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

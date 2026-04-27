package dev.sebastiano.spectre.core

import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class RobotDriverTest {

    @Test
    fun `shortcutModifierKeyCode returns VK_META on macOS`() {
        val result = shortcutModifierKeyCode(isMacOs = true)
        assertEquals(KeyEvent.VK_META, result)
    }

    @Test
    fun `shortcutModifierKeyCode returns VK_CONTROL on non-macOS`() {
        val result = shortcutModifierKeyCode(isMacOs = false)
        assertEquals(KeyEvent.VK_CONTROL, result)
    }

    @Test
    fun `shortcutModifierKeyCode matches platform shortcut key`() {
        val expected = if (detectMacOs()) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        assertEquals(expected, shortcutModifierKeyCode(detectMacOs()))
    }

    @Test
    fun `swipePauseMillis subtracts autoDelay from per-step pause`() {
        // 200ms over 10 steps = 20ms/step, minus 10ms Robot autoDelay per move = 10ms.
        val pause = swipePauseMillis(duration = 200.milliseconds, steps = 10, autoDelayMs = 10)
        assertEquals(10L, pause)
    }

    @Test
    fun `swipePauseMillis clamps to zero when autoDelay exceeds the per-step budget`() {
        // 50ms over 10 steps = 5ms/step, minus 10ms autoDelay would be negative.
        val pause = swipePauseMillis(duration = 50.milliseconds, steps = 10, autoDelayMs = 10)
        assertEquals(0L, pause)
    }

    @Test
    fun `swipePauseMillis returns zero for zero duration`() {
        val pause = swipePauseMillis(duration = ZERO, steps = 5, autoDelayMs = 10)
        assertEquals(0L, pause)
    }

    @Test
    fun `detectMacOs reads os dot name system property`() {
        val osName = System.getProperty("os.name").lowercase()
        val expected = osName.contains("mac")
        assertEquals(expected, detectMacOs())
    }

    @Test
    fun `modifierMaskToKeyCodes returns empty for zero mask`() {
        assertEquals(emptyList(), modifierMaskToKeyCodes(0))
    }

    @Test
    fun `modifierMaskToKeyCodes translates CTRL_DOWN_MASK to VK_CONTROL`() {
        assertEquals(listOf(KeyEvent.VK_CONTROL), modifierMaskToKeyCodes(InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `modifierMaskToKeyCodes translates combined Ctrl+Shift mask`() {
        val mask = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
        val result = modifierMaskToKeyCodes(mask)
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT), result)
    }

    @Test
    fun `modifierMaskToKeyCodes translates META_DOWN_MASK to VK_META`() {
        assertEquals(listOf(KeyEvent.VK_META), modifierMaskToKeyCodes(InputEvent.META_DOWN_MASK))
    }

    @Test
    fun `interpolateSwipePoints includes both endpoints`() {
        val result =
            interpolateSwipePoints(startX = 10, startY = 20, endX = 40, endY = 50, steps = 3)

        assertEquals(listOf(Point(10, 20), Point(20, 30), Point(30, 40), Point(40, 50)), result)
    }

    @Test
    fun `interpolateSwipePoints rejects non-positive steps`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                interpolateSwipePoints(startX = 0, startY = 0, endX = 10, endY = 10, steps = 0)
            }

        assertTrue(error.message?.contains("steps") == true)
    }

    @Test
    fun `click moves and presses left button`() {
        val robot = RecordingRobotAdapter()
        val driver = RobotDriver(robot, RecordingClipboardAdapter())

        driver.click(10, 20)

        assertEquals(
            listOf(
                "move(10,20)",
                "press(${InputEvent.BUTTON1_DOWN_MASK})",
                "release(${InputEvent.BUTTON1_DOWN_MASK})",
            ),
            robot.events,
        )
    }

    @Test
    fun `swipe presses drags and releases in order`() {
        val robot = RecordingRobotAdapter()
        val driver = RobotDriver(robot, RecordingClipboardAdapter())

        driver.swipe(startX = 10, startY = 20, endX = 40, endY = 50, steps = 3, duration = ZERO)

        assertEquals(
            listOf(
                "move(10,20)",
                "press(${InputEvent.BUTTON1_DOWN_MASK})",
                "move(20,30)",
                "move(30,40)",
                "move(40,50)",
                "release(${InputEvent.BUTTON1_DOWN_MASK})",
            ),
            robot.events,
        )
    }
}

private class RecordingRobotAdapter(override val autoDelayMs: Int = 0) : RobotAdapter {
    override val requiresOffEdt: Boolean = false
    val events = mutableListOf<String>()

    override fun mouseMove(x: Int, y: Int) {
        events += "move($x,$y)"
    }

    override fun mousePress(buttons: Int) {
        events += "press($buttons)"
    }

    override fun mouseRelease(buttons: Int) {
        events += "release($buttons)"
    }

    override fun keyPress(keyCode: Int) {
        events += "keyPress($keyCode)"
    }

    override fun keyRelease(keyCode: Int) {
        events += "keyRelease($keyCode)"
    }

    override fun mouseWheel(wheelClicks: Int) {
        events += "mouseWheel($wheelClicks)"
    }

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        BufferedImage(
            region.width.coerceAtLeast(1),
            region.height.coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )

    override fun waitForIdle() {
        events += "waitForIdle()"
    }
}

private class RecordingClipboardAdapter : ClipboardAdapter {
    private var current: Transferable? = null

    override fun getContents(): Transferable? = current

    override fun setContents(contents: Transferable) {
        current = contents
    }
}

package dev.sebastiano.spectre.core

import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
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
    fun `typeText waits for the clipboard to reflect the new text before dispatching paste`() {
        // Simulate a slow OS pasteboard: the first N reads after setContents still return the
        // previous value. typeText must not press Cmd+V until the clipboard has settled, otherwise
        // the paste handler reads stale contents.
        val clipboard = LatentClipboardAdapter(latencyReads = 3)
        clipboard.setContentsImmediate(StringSelection("previous"))
        val driver = RobotDriver(clipboard.robotAdapter, clipboard)

        driver.typeText("spectre")

        // The first key event must come AFTER the clipboard has the new value. Reconstruct by
        // looking at the recorded order: each call to clipboard.getContents() interleaves with
        // robot events, and we check the clipboard read at the moment of the first keyPress.
        val firstKeyPressIndex = clipboard.eventLog.indexOfFirst { it.startsWith("robot:keyPress") }
        check(firstKeyPressIndex >= 0) { "Expected a keyPress event in ${clipboard.eventLog}" }
        val readsBeforePaste =
            clipboard.eventLog.subList(0, firstKeyPressIndex).count {
                it == "clipboard:get=spectre"
            }
        assertTrue(
            readsBeforePaste >= 1,
            "typeText must observe the new clipboard contents at least once before pressing " +
                "Cmd+V (event log: ${clipboard.eventLog})",
        )
    }

    @Test
    fun `typeText pumps the EDT after key release before restoring the clipboard`() {
        // The OS paste handler reads the clipboard asynchronously after Cmd+V is released, so
        // restoring the previous contents synchronously can clobber the value before the paste
        // lands. typeText must give the AWT event queue (and any post-press settle) a chance to
        // drain before restoring.
        val clipboard = LatentClipboardAdapter()
        clipboard.setContentsImmediate(StringSelection("previous"))
        val driver = RobotDriver(clipboard.robotAdapter, clipboard)

        driver.typeText("spectre")

        // The order must be: setContents("spectre") → keyPress/keyRelease → waitForIdle
        // → setContents("previous"). If waitForIdle does not appear between the last keyRelease
        // and the restore, the paste handler can race.
        val log = clipboard.eventLog
        val lastReleaseIdx = log.indexOfLast { it.startsWith("robot:keyRelease") }
        val restoreIdx =
            log.withIndex().indexOfFirst { (i, e) ->
                e == "clipboard:set=previous" && i > lastReleaseIdx
            }
        val waitIdx =
            log.withIndex().indexOfFirst { (i, e) ->
                e == "robot:waitForIdle()" && i > lastReleaseIdx
            }
        check(lastReleaseIdx >= 0) { "Expected a keyRelease in $log" }
        check(restoreIdx > lastReleaseIdx) { "Expected restore after last keyRelease in $log" }
        assertTrue(
            waitIdx in (lastReleaseIdx + 1) until restoreIdx,
            "typeText must call waitForIdle() between the final keyRelease and the clipboard " +
                "restore (log: $log)",
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

private class RecordingRobotAdapter(
    override val autoDelayMs: Int = 0,
    private val sharedLog: MutableList<String>? = null,
) : RobotAdapter {
    override val requiresOffEdt: Boolean = false
    val events = mutableListOf<String>()

    private fun log(event: String) {
        events += event
        sharedLog?.add("robot:$event")
    }

    override fun mouseMove(x: Int, y: Int) = log("move($x,$y)")

    override fun mousePress(buttons: Int) = log("press($buttons)")

    override fun mouseRelease(buttons: Int) = log("release($buttons)")

    override fun keyPress(keyCode: Int) = log("keyPress($keyCode)")

    override fun keyRelease(keyCode: Int) = log("keyRelease($keyCode)")

    override fun mouseWheel(wheelClicks: Int) = log("mouseWheel($wheelClicks)")

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        BufferedImage(
            region.width.coerceAtLeast(1),
            region.height.coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )

    override fun waitForIdle() = log("waitForIdle()")
}

private class RecordingClipboardAdapter : ClipboardAdapter {
    private var current: Transferable? = null

    override fun getContents(): Transferable? = current

    override fun setContents(contents: Transferable) {
        current = contents
    }
}

/**
 * Clipboard adapter that simulates an asynchronous OS pasteboard. After [setContents] the next
 * [latencyReads] reads still return the *previous* value; only after that do reads return the new
 * one. Records every read and write into a shared event log alongside robot events so tests can
 * assert ordering between input dispatch and clipboard mutation.
 */
private class LatentClipboardAdapter(private val latencyReads: Int = 0) : ClipboardAdapter {
    val eventLog: MutableList<String> = mutableListOf()
    val robotAdapter: RecordingRobotAdapter = RecordingRobotAdapter(sharedLog = eventLog)

    private var current: Transferable? = null
    private var pending: Transferable? = null
    private var readsRemaining: Int = 0

    fun setContentsImmediate(contents: Transferable) {
        current = contents
        pending = null
        readsRemaining = 0
    }

    override fun getContents(): Transferable? {
        if (pending != null) {
            if (readsRemaining > 0) {
                readsRemaining--
            } else {
                current = pending
                pending = null
            }
        }
        val value = current?.getTransferData(DataFlavor.stringFlavor) as? String
        eventLog += "clipboard:get=$value"
        return current
    }

    override fun setContents(contents: Transferable) {
        val asString = contents.getTransferData(DataFlavor.stringFlavor) as? String
        eventLog += "clipboard:set=$asString"
        if (latencyReads > 0) {
            pending = contents
            readsRemaining = latencyReads
        } else {
            current = contents
            pending = null
            readsRemaining = 0
        }
    }
}

package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

class RobotDriver(
    private val robot: Robot =
        Robot().apply {
            autoDelay = DEFAULT_AUTO_DELAY_MS
            isAutoWaitForIdle = false
        }
) {

    fun click(screenX: Int, screenY: Int) = runOffEdt {
        robot.mouseMove(screenX, screenY)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    fun doubleClick(screenX: Int, screenY: Int) = runOffEdt {
        robot.mouseMove(screenX, screenY)
        repeat(2) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    fun typeText(text: String) = runOffEdt {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previousContents = runCatching { clipboard.getContents(null) }.getOrNull()
        try {
            clipboard.setContents(StringSelection(text), null)
            val modifier = pasteModifierKeyCode(detectMacOs())
            robot.keyPress(modifier)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(modifier)
        } finally {
            if (previousContents != null) {
                runCatching { clipboard.setContents(previousContents, null) }
            }
        }
    }

    fun pressKey(keyCode: Int, modifiers: Int = 0) = runOffEdt {
        val modifierKeys = modifierMaskToKeyCodes(modifiers)
        for (mod in modifierKeys) robot.keyPress(mod)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        for (mod in modifierKeys.reversed()) robot.keyRelease(mod)
    }

    fun screenshot(region: Rectangle? = null): BufferedImage {
        val captureRegion = region ?: virtualDesktopBounds()
        return robot.createScreenCapture(captureRegion)
    }
}

/**
 * Robot actions must not run on the EDT because Robot.waitForIdle() would deadlock. When called
 * from the EDT, this dispatches the block to a temporary thread and waits for it to complete.
 *
 * Note: isAutoWaitForIdle is disabled to avoid the Robot internally calling waitForIdle() (which
 * would still deadlock if the spawned thread tried it). The autoDelay provides a small pause
 * between events instead.
 */
private fun runOffEdt(block: () -> Unit) {
    if (!SwingUtilities.isEventDispatchThread()) {
        block()
        return
    }
    var result: Result<Unit>? = null
    val thread = Thread({ result = runCatching(block) }, "spectre-robot")
    thread.start()
    thread.join()
    result!!.getOrThrow()
}

fun pasteModifierKeyCode(isMacOs: Boolean): Int =
    if (isMacOs) KeyEvent.VK_META else KeyEvent.VK_CONTROL

/**
 * Translates an AWT modifier bitmask (e.g. [InputEvent.CTRL_DOWN_MASK]) into individual key codes
 * that Robot can press/release.
 */
fun modifierMaskToKeyCodes(mask: Int): List<Int> = buildList {
    if (mask and InputEvent.CTRL_DOWN_MASK != 0) add(KeyEvent.VK_CONTROL)
    if (mask and InputEvent.SHIFT_DOWN_MASK != 0) add(KeyEvent.VK_SHIFT)
    if (mask and InputEvent.ALT_DOWN_MASK != 0) add(KeyEvent.VK_ALT)
    if (mask and InputEvent.META_DOWN_MASK != 0) add(KeyEvent.VK_META)
}

fun detectMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

private fun virtualDesktopBounds(): Rectangle {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val devices = ge.screenDevices
    // Seed with the first device's bounds to avoid Rectangle() defaulting to (0,0)
    // which would incorrectly include the origin in multi-monitor offset setups
    var bounds = devices.first().defaultConfiguration.bounds
    for (i in 1 until devices.size) {
        bounds = bounds.union(devices[i].defaultConfiguration.bounds)
    }
    return bounds
}

private const val DEFAULT_AUTO_DELAY_MS = 10

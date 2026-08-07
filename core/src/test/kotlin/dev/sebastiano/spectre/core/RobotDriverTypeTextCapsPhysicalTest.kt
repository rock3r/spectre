package dev.sebastiano.spectre.core

import java.awt.BorderLayout
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout

/**
 * Physical coverage for #396: real [RobotDriver.typeText] must land the requested letter case under
 * Caps Lock off and (when the host can force it) Caps Lock on, and must restore Caps Lock when it
 * temporarily clears the lock.
 *
 * Gating:
 * - [assumeLiveAwtAvailable] — needs a display; macOS requires `-Dspectre.test.liveAwt=true`.
 * - Caps Lock **on** is attempted via Toolkit then Robot toggle; if neither works the on scenario
 *   is assumption-skipped (macOS Toolkit cannot set Caps Lock; synthetic HID often cannot either).
 */
class RobotDriverTypeTextCapsPhysicalTest {

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `typeText lands requested case with Caps Lock off`() {
        assumeLiveAwtAvailable()
        withTextFieldFixture { field, robot, toolkit ->
            ensureCaps(robot, toolkit, on = false)
            assumeTrue(!readCaps(toolkit), "Could not clear Caps Lock for off scenario")
            val before = readCaps(toolkit)
            val typed = "xYz"
            clearAndFocus(field, robot)
            runBlocking { RobotDriver().typeText(typed) }
            val got = readText(field)
            val after = readCaps(toolkit)
            assertEquals(
                typed,
                got,
                "Caps Lock off: typeText must produce requested case (beforeCaps=$before afterCaps=$after)",
            )
            assertEquals(
                before,
                after,
                "typeText must not leave Caps Lock inverted when it was off",
            )
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `typeText lands requested case with Caps Lock on without mutating lock`() {
        assumeLiveAwtAvailable()
        withTextFieldFixture { field, robot, toolkit ->
            val original = readCaps(toolkit)
            try {
                ensureCaps(robot, toolkit, on = true)
                assumeTrue(
                    readCaps(toolkit),
                    "Host cannot force Caps Lock on (Toolkit set / Robot toggle unsupported); " +
                        "Caps Lock on physical path skipped. Unit tests cover Shift compensation.",
                )
                val before = readCaps(toolkit)
                assertTrue(before, "precondition: Caps Lock on")
                val typed = "xYz"
                clearAndFocus(field, robot)
                runBlocking { RobotDriver().typeText(typed) }
                val got = readText(field)
                val after = readCaps(toolkit)
                assertEquals(
                    typed,
                    got,
                    "Caps Lock on: typeText must produce requested case independent of ambient lock " +
                        "(beforeCaps=$before afterCaps=$after)",
                )
                assertTrue(
                    after,
                    "typeText must not clear ambient Caps Lock (before was on; after must stay on)",
                )
            } finally {
                ensureCaps(robot, toolkit, on = original)
            }
        }
    }

    private fun withTextFieldFixture(block: (JTextField, Robot, Toolkit) -> Unit) {
        val toolkit = Toolkit.getDefaultToolkit()
        val robot =
            Robot().apply {
                autoDelay = 20
                isAutoWaitForIdle = true
            }
        val fieldRef = AtomicReference<JTextField>()
        val frameRef = AtomicReference<JFrame>()
        SwingUtilities.invokeAndWait {
            val field = JTextField(24)
            val frame = JFrame("Spectre #396 typeText Caps Lock physical")
            frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            frame.layout = BorderLayout()
            frame.add(field, BorderLayout.CENTER)
            frame.setSize(480, 120)
            frame.setLocation(140, 140)
            frame.isAlwaysOnTop = true
            frame.isVisible = true
            frame.toFront()
            field.requestFocusInWindow()
            fieldRef.set(field)
            frameRef.set(frame)
        }
        try {
            Thread.sleep(FOCUS_SETTLE_MS)
            block(fieldRef.get(), robot, toolkit)
        } finally {
            SwingUtilities.invokeAndWait { frameRef.get()?.dispose() }
        }
    }

    private fun clearAndFocus(field: JTextField, robot: Robot) {
        SwingUtilities.invokeAndWait {
            field.text = ""
            field.requestFocusInWindow()
        }
        val loc = AtomicReference<Point>()
        SwingUtilities.invokeAndWait {
            val p = field.locationOnScreen
            loc.set(Point(p.x + field.width / 2, p.y + field.height / 2))
        }
        val p = loc.get()
        robot.mouseMove(p.x, p.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(FOCUS_SETTLE_MS / 2)
    }

    private fun readText(field: JTextField): String {
        val ref = AtomicReference<String>()
        SwingUtilities.invokeAndWait { ref.set(field.text) }
        return ref.get()
    }

    private fun readCaps(toolkit: Toolkit): Boolean =
        try {
            toolkit.getLockingKeyState(KeyEvent.VK_CAPS_LOCK)
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun trySetCaps(toolkit: Toolkit, on: Boolean): Boolean =
        try {
            toolkit.setLockingKeyState(KeyEvent.VK_CAPS_LOCK, on)
            readCaps(toolkit) == on
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun ensureCaps(robot: Robot, toolkit: Toolkit, on: Boolean) {
        if (readCaps(toolkit) == on) return
        if (trySetCaps(toolkit, on)) return
        repeat(3) {
            if (readCaps(toolkit) == on) return
            robot.keyPress(KeyEvent.VK_CAPS_LOCK)
            robot.keyRelease(KeyEvent.VK_CAPS_LOCK)
            Thread.sleep(CAPS_TOGGLE_SETTLE_MS)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 30
        const val FOCUS_SETTLE_MS: Long = 400
        const val CAPS_TOGGLE_SETTLE_MS: Long = 200
    }
}

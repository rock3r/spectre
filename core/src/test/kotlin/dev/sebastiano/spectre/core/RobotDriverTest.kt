package dev.sebastiano.spectre.core

import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobotDriverTest {

    @Test
    fun `pasteModifierKeyCode returns VK_META on macOS`() {
        val result = pasteModifierKeyCode(isMacOs = true)
        assertEquals(KeyEvent.VK_META, result)
    }

    @Test
    fun `pasteModifierKeyCode returns VK_CONTROL on non-macOS`() {
        val result = pasteModifierKeyCode(isMacOs = false)
        assertEquals(KeyEvent.VK_CONTROL, result)
    }

    @Test
    fun `detectMacOs reads os dot name system property`() {
        val osName = System.getProperty("os.name").lowercase()
        val expected = osName.contains("mac")
        assertEquals(expected, detectMacOs())
    }

    @Test
    fun `click does not throw when called from the EDT`() {
        val driver = RobotDriver()
        val latch = CountDownLatch(1)
        var error: Throwable? = null

        SwingUtilities.invokeLater {
            try {
                // Click at (0,0) — we don't care where, just that it doesn't throw
                driver.click(0, 0)
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }

        assertTrue(latch.await(ROBOT_EDT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out")
        error?.let { throw AssertionError("click from EDT should not throw", it) }
    }

    @Test
    fun `typeText does not throw when called from the EDT`() {
        val driver = RobotDriver()
        val latch = CountDownLatch(1)
        var error: Throwable? = null

        SwingUtilities.invokeLater {
            try {
                driver.typeText("test")
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }

        assertTrue(latch.await(ROBOT_EDT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out")
        error?.let { throw AssertionError("typeText from EDT should not throw", it) }
    }
}

private const val ROBOT_EDT_TEST_TIMEOUT_SECONDS = 5L

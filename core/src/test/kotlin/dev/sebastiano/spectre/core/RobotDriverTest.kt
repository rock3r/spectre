package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIf

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

    @EnabledIf("isNotHeadless")
    @Test
    fun `click does not throw when called from the EDT`() {
        val driver = RobotDriver()
        val latch = CountDownLatch(1)
        var error: Throwable? = null

        SwingUtilities.invokeLater {
            try {
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

    @EnabledIf("isNotHeadless")
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

    companion object {

        @JvmStatic fun isNotHeadless(): Boolean = !GraphicsEnvironment.isHeadless()
    }
}

private const val ROBOT_EDT_TEST_TIMEOUT_SECONDS = 5L

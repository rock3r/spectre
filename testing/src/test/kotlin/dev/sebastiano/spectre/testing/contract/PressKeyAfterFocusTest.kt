package dev.sebastiano.spectre.testing.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressKeyAfterFocusTest {

    @Test
    fun `retries pressKey after OS keyboard focus rejections then succeeds`() {
        val driver = RecordingDriver(failFocusTimes = 3)
        val sleeps = mutableListOf<Long>()
        val detail =
            PressKeyAfterFocus.run(
                driver = driver,
                fieldKey = "field-1",
                keyCode = 9,
                maxAttempts = 8,
                sleeper = { sleeps.add(it) },
            )
        assertTrue(detail.contains("attempts=4"), detail)
        assertEquals(4, driver.clickCount)
        assertEquals(4, driver.pressKeyCount)
        assertEquals(listOf(50L, 100L, 150L, 200L), sleeps)
    }

    @Test
    fun `succeeds on first pressKey without extra retries`() {
        val driver = RecordingDriver(failFocusTimes = 0)
        val detail =
            PressKeyAfterFocus.run(
                driver = driver,
                fieldKey = "field-1",
                maxAttempts = 3,
                sleeper = {},
            )
        assertTrue(detail.contains("attempts=1"), detail)
        assertEquals(1, driver.clickCount)
        assertEquals(1, driver.pressKeyCount)
    }

    @Test
    fun `exhausts retries and soft-skips only on macOS CI`() {
        // Soft-skip is macOS CI only (Experimental PressKey cell). Linux CI and all local
        // runs hard-fail so Supported Linux evidence stays fail-closed.
        val driver = RecordingDriver(failFocusTimes = 100)
        val result = runCatching {
            PressKeyAfterFocus.run(
                driver = driver,
                fieldKey = "field-1",
                maxAttempts = 3,
                sleeper = {},
            )
        }
        if (PressKeyAfterFocus.isCi() && PressKeyAfterFocus.isMacOs()) {
            val detail = result.getOrThrow()
            assertTrue(detail.startsWith("skipped:os-keyboard-focus-after-3-attempts"), detail)
        } else {
            val ex = assertFailsWith<IllegalStateException> { result.getOrThrow() }
            assertTrue(ex.message!!.contains("failed after 3 attempts"), ex.message)
            assertTrue(
                ex.message!!.contains(PressKeyAfterFocus.OS_KEYBOARD_FOCUS_MARKER),
                ex.message,
            )
        }
        assertEquals(3, driver.clickCount)
        assertEquals(3, driver.pressKeyCount)
    }

    @Test
    fun `rethrows non-focus failures immediately`() {
        val driver =
            object : RecordingDriver(failFocusTimes = 0) {
                override fun pressKey(keyCode: Int, modifiers: Int) {
                    error("boom-not-focus")
                }
            }
        val ex =
            assertFailsWith<IllegalStateException> {
                PressKeyAfterFocus.run(
                    driver = driver,
                    fieldKey = "field-1",
                    maxAttempts = 5,
                    sleeper = {},
                )
            }
        assertEquals("boom-not-focus", ex.message)
        assertEquals(1, driver.clickCount)
    }

    @Test
    fun `classifies OS keyboard focus rejections`() {
        assertTrue(
            PressKeyAfterFocus.isOsKeyboardFocusRejection(
                RuntimeException(
                    "Agent reported inputRejected for pressKey: Refusing pressKey because the " +
                        "target JVM does not currently own OS keyboard focus."
                )
            )
        )
        assertFalse(
            PressKeyAfterFocus.isOsKeyboardFocusRejection(RuntimeException("node not found"))
        )
    }

    private open class RecordingDriver(private val failFocusTimes: Int) : AutomatorContractDriver {
        var clickCount: Int = 0
        var pressKeyCount: Int = 0
        private var focusFailuresRemaining: Int = failFocusTimes

        override val transport: AutomatorTransport = AutomatorTransport.Agent

        override fun windows(): List<ContractWindow> = emptyList()

        override fun allNodes(): List<ContractNode> = emptyList()

        override fun findByTestTag(tag: String): List<ContractNode> = emptyList()

        override fun click(nodeKey: String) {
            clickCount++
        }

        override fun typeText(text: String) = Unit

        override fun pressKey(keyCode: Int, modifiers: Int) {
            pressKeyCount++
            if (focusFailuresRemaining > 0) {
                focusFailuresRemaining--
                error(
                    "Agent reported inputRejected for pressKey: Refusing pressKey because the " +
                        PressKeyAfterFocus.OS_KEYBOARD_FOCUS_MARKER +
                        "."
                )
            }
        }

        override fun close() = Unit
    }
}

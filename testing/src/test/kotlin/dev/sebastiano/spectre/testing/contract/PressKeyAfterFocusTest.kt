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
            PressKeyAfterFocus.runGated(
                driver = driver,
                fieldKey = "field-1",
                keyCode = 9,
                maxAttempts = 8,
                sleeper = { sleeps.add(it) },
                gateEnabled = true,
            )
        assertTrue(detail.contains("attempts=4"), detail)
        assertEquals(4, driver.focusWindowCount)
        assertEquals(4, driver.clickCount)
        assertEquals(4, driver.pressKeyCount)
        assertEquals(listOf(50L, 100L, 150L, 200L), sleeps)
    }

    @Test
    fun `succeeds on first pressKey without extra retries`() {
        val driver = RecordingDriver(failFocusTimes = 0)
        val detail =
            PressKeyAfterFocus.runGated(
                driver = driver,
                fieldKey = "field-1",
                maxAttempts = 3,
                sleeper = {},
                gateEnabled = true,
            )
        assertTrue(detail.contains("attempts=1"), detail)
        assertEquals(1, driver.focusWindowCount)
        assertEquals(1, driver.clickCount)
        assertEquals(1, driver.pressKeyCount)
    }

    @Test
    fun `exhausts retries and soft-skips only on macOS CI`() {
        // Soft-skip is macOS CI only (Experimental PressKey cell). Linux CI and all local
        // runs hard-fail so Supported Linux evidence stays fail-closed.
        val driver = RecordingDriver(failFocusTimes = 100)
        val result = runCatching {
            PressKeyAfterFocus.runGated(
                driver = driver,
                fieldKey = "field-1",
                maxAttempts = 3,
                sleeper = {},
                gateEnabled = true,
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
        assertEquals(3, driver.focusWindowCount)
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
                PressKeyAfterFocus.runGated(
                    driver = driver,
                    fieldKey = "field-1",
                    maxAttempts = 5,
                    sleeper = {},
                    gateEnabled = true,
                )
            }
        assertEquals("boom-not-focus", ex.message)
        assertEquals(1, driver.focusWindowCount)
        assertEquals(1, driver.clickCount)
    }

    @Test
    fun `skips without touching the driver when the real-keyboard gate is off`() {
        // #449: `./gradlew check` must stay runnable while someone uses the machine. Raising the
        // fixture window and clicking it are themselves focus-stealing, so a gated-off run has to
        // do nothing at all — not "try and tolerate the failure".
        val driver = RecordingDriver(failFocusTimes = 0)
        val detail =
            PressKeyAfterFocus.runGated(
                driver = driver,
                fieldKey = "field-1",
                sleeper = {},
                gateEnabled = false,
                warn = {},
            )
        assertEquals(RealKeyboardGate.SKIPPED_DETAIL, detail)
        assertEquals(0, driver.focusWindowCount)
        assertEquals(0, driver.clickCount)
        assertEquals(0, driver.pressKeyCount)
    }

    @Test
    fun `tells the developer how to run the keyboard path when it skips`() {
        val warnings = mutableListOf<String>()
        PressKeyAfterFocus.runGated(
            driver = RecordingDriver(failFocusTimes = 0),
            fieldKey = "field-1",
            sleeper = {},
            gateEnabled = false,
            warn = { warnings.add(it) },
        )
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("press-key-tab-after-focus"), warnings.single())
        assertTrue(warnings.single().contains(RealKeyboardGate.ENABLE_HINT), warnings.single())
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
        var focusWindowCount: Int = 0
        private var focusFailuresRemaining: Int = failFocusTimes

        override val transport: AutomatorTransport = AutomatorTransport.Agent

        override fun windows(): List<ContractWindow> = emptyList()

        override fun allNodes(): List<ContractNode> = emptyList()

        override fun findByTestTag(tag: String): List<ContractNode> = emptyList()

        override fun click(nodeKey: String) {
            clickCount++
        }

        override fun focusWindow(nodeKey: String) {
            focusWindowCount++
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

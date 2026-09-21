package dev.sebastiano.spectre.testing

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ScreenshotUpdateModeTest {

    @Test
    fun `update mode is off when nothing is set`() {
        assertFalse(ScreenshotUpdateMode.isEnabled(property = null, env = null))
        assertFalse(ScreenshotUpdateMode.isEnabled(property = "", env = ""))
    }

    @Test
    fun `env true enables update mode when the property is unset`() {
        assertTrue(ScreenshotUpdateMode.isEnabled(property = null, env = "true"))
        assertTrue(ScreenshotUpdateMode.isEnabled(property = null, env = "TRUE"))
    }

    @Test
    fun `gradle property true enables update mode`() {
        assertTrue(ScreenshotUpdateMode.isEnabled(property = "true", env = null))
    }

    @Test
    fun `gradle property wins over env when both are set`() {
        assertFalse(ScreenshotUpdateMode.isEnabled(property = "false", env = "true"))
        assertTrue(ScreenshotUpdateMode.isEnabled(property = "true", env = "false"))
    }

    @Test
    fun `non-true values do not enable update mode`() {
        assertFalse(ScreenshotUpdateMode.isEnabled(property = "yes", env = null))
        assertFalse(ScreenshotUpdateMode.isEnabled(property = null, env = "1"))
    }
}

package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the real-keyboard opt-in that keeps `./gradlew check` runnable on a machine
 * someone is using, while CI still exercises the Robot-backed `typeText` subpath of
 * [AgentAttachIntegrationTest] (#444).
 */
class RealKeyboardE2eGateTest {
    @Test
    fun `CI enables the subpath when no property is set`() {
        assertTrue(RealKeyboardE2eGate.isEnabled(property = null, ci = "true"))
        assertTrue(RealKeyboardE2eGate.isEnabled(property = null, ci = "TRUE"))
    }

    @Test
    fun `a developer machine disables the subpath by default`() {
        assertFalse(RealKeyboardE2eGate.isEnabled(property = null, ci = null))
        assertFalse(RealKeyboardE2eGate.isEnabled(property = null, ci = ""))
        assertFalse(RealKeyboardE2eGate.isEnabled(property = null, ci = "false"))
    }

    @Test
    fun `the property wins over the CI environment in both directions`() {
        assertTrue(RealKeyboardE2eGate.isEnabled(property = "true", ci = null))
        assertTrue(RealKeyboardE2eGate.isEnabled(property = "TRUE", ci = "false"))
        assertFalse(RealKeyboardE2eGate.isEnabled(property = "false", ci = "true"))
    }

    @Test
    fun `a blank property falls back to the CI environment`() {
        assertTrue(RealKeyboardE2eGate.isEnabled(property = "  ", ci = "true"))
        assertFalse(RealKeyboardE2eGate.isEnabled(property = "", ci = null))
    }

    @Test
    fun `an unparseable property falls back to the CI environment`() {
        // A typo must not silently drop the CI-side keyboard coverage.
        assertTrue(RealKeyboardE2eGate.isEnabled(property = "yes", ci = "true"))
        assertFalse(RealKeyboardE2eGate.isEnabled(property = "yes", ci = null))
    }
}

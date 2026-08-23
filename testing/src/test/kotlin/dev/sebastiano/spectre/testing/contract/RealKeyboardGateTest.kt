package dev.sebastiano.spectre.testing.contract

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the shared real-keyboard opt-in (#444, #449).
 *
 * One gate now serves both Robot-backed keyboard paths: the `typeText` subpath of
 * `AgentAttachIntegrationTest` and the `press-key-tab-after-focus` scenario of
 * [AutomatorContractCorpus]. Both need the fixture window to own OS keyboard focus, so both must be
 * off by default on a machine someone is using and on by default on CI.
 */
class RealKeyboardGateTest {
    @Test
    fun `CI enables the keyboard paths when no property is set`() {
        assertTrue(RealKeyboardGate.isEnabled(property = null, ci = "true"))
        assertTrue(RealKeyboardGate.isEnabled(property = null, ci = "TRUE"))
    }

    @Test
    fun `a developer machine disables the keyboard paths by default`() {
        assertFalse(RealKeyboardGate.isEnabled(property = null, ci = null))
        assertFalse(RealKeyboardGate.isEnabled(property = null, ci = ""))
        assertFalse(RealKeyboardGate.isEnabled(property = null, ci = "false"))
    }

    @Test
    fun `the property wins over the CI environment in both directions`() {
        assertTrue(RealKeyboardGate.isEnabled(property = "true", ci = null))
        assertTrue(RealKeyboardGate.isEnabled(property = "TRUE", ci = "false"))
        assertFalse(RealKeyboardGate.isEnabled(property = "false", ci = "true"))
    }

    @Test
    fun `a blank property falls back to the CI environment`() {
        assertTrue(RealKeyboardGate.isEnabled(property = "  ", ci = "true"))
        assertFalse(RealKeyboardGate.isEnabled(property = "", ci = null))
    }

    @Test
    fun `an unparseable property falls back to the CI environment`() {
        // A typo must not silently drop the CI-side keyboard coverage.
        assertTrue(RealKeyboardGate.isEnabled(property = "yes", ci = "true"))
        assertFalse(RealKeyboardGate.isEnabled(property = "yes", ci = null))
    }

    @Test
    fun `the enable hint names both property spellings a caller can actually pass`() {
        // The hint is the only thing a developer sees when a path skips, so it must stay in
        // sync with the names the build script and the gate really read.
        assertTrue(
            RealKeyboardGate.ENABLE_HINT.contains("-P${RealKeyboardGate.GRADLE_PROPERTY}=true"),
            RealKeyboardGate.ENABLE_HINT,
        )
        assertTrue(
            RealKeyboardGate.ENABLE_HINT.contains("-D${RealKeyboardGate.ENABLE_PROP}=true"),
            RealKeyboardGate.ENABLE_HINT,
        )
    }

    @Test
    fun `the system property is the dotted form of the Gradle property`() {
        assertTrue(
            RealKeyboardGate.ENABLE_PROP.endsWith(
                RealKeyboardGate.GRADLE_PROPERTY.removePrefix("spectre.")
            ),
            "ENABLE_PROP=${RealKeyboardGate.ENABLE_PROP} " +
                "GRADLE_PROPERTY=${RealKeyboardGate.GRADLE_PROPERTY}",
        )
    }
}

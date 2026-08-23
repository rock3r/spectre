package dev.sebastiano.spectre.testing.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gated `press-key-tab-after-focus` row must be decided before the corpus touches the driver
 * (#449).
 *
 * Resolving the fixture text field is a driver round-trip. If it runs before the gate check, a
 * transport error or a missing node fails the scenario on a host that was never going to run the
 * keyboard path — the opposite of keeping `./gradlew check` runnable on a desktop in use.
 */
class GatedPressKeyScenarioTest {

    @Test
    fun `a gated-off run skips the scenario even when the node lookup would fail`() {
        val driver = ExplodingLookupDriver()
        val result =
            AutomatorContractCorpus.run(driver = driver, realKeyboardEnabled = false)
                .results
                .single { it.id == PressKeyAfterFocus.SCENARIO_ID }

        assertTrue(result.passed, "gated-off press-key row must pass: ${result.detail}")
        assertEquals(RealKeyboardGate.SKIPPED_DETAIL, result.detail)
        assertEquals(0, driver.textFieldLookups, "gated-off run must not touch the driver for it")
    }

    @Test
    fun `a gated-on run resolves the field and so surfaces a lookup failure`() {
        // The mirror image: with the gate on, the row is a real assertion again.
        val driver = ExplodingLookupDriver()
        val result =
            AutomatorContractCorpus.run(driver = driver, realKeyboardEnabled = true)
                .results
                .single { it.id == PressKeyAfterFocus.SCENARIO_ID }

        assertTrue(!result.passed, "gated-on press-key row must not silently pass")
        assertEquals(1, driver.textFieldLookups)
    }

    /** Fixture-backed driver whose text-field lookup always fails, like a wedged transport. */
    private class ExplodingLookupDriver : AutomatorContractDriver {
        var textFieldLookups: Int = 0

        override val transport: AutomatorTransport = AutomatorTransport.Agent
        override val expectsFixtureSemantics: Boolean = true

        override fun windows(): List<ContractWindow> = listOf(ContractWindow(surfaceId = "w0"))

        override fun allNodes(): List<ContractNode> = listOf(node(ContractFixtureTags.BUTTON))

        override fun findByTestTag(tag: String): List<ContractNode> {
            if (tag == ContractFixtureTags.TEXT_FIELD) {
                textFieldLookups++
                error("transport wedged resolving $tag")
            }
            return listOf(node(tag))
        }

        override fun click(nodeKey: String) = Unit

        override fun focusWindow(nodeKey: String) = Unit

        override fun typeText(text: String) = Unit

        override fun pressKey(keyCode: Int, modifiers: Int) = Unit

        override fun screenshotProbe(): ScreenshotProbe = ScreenshotProbe(byteCount = 1)

        override fun close() = Unit

        private fun node(tag: String) = ContractNode(key = "key-$tag", testTag = tag, text = null)
    }
}

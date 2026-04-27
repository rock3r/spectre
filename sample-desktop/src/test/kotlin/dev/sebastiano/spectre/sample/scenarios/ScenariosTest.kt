package dev.sebastiano.spectre.sample.scenarios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Catalogue tests.
 *
 * The scenario testTags are part of Spectre's test contract — validation passes (#7, #8, #14)
 * navigate by them. These checks pin the catalogue shape so any reshuffle becomes a deliberate
 * change.
 */
class ScenariosTest {

    @Test
    fun `every scenario testTag is unique`() {
        val tags = ALL_SCENARIOS.map { it.testTag }
        assertEquals(tags.size, tags.toSet().size, "Duplicate testTag in scenario catalogue: $tags")
    }

    @Test
    fun `every scenario testTag uses the scenario prefix`() {
        for (scenario in ALL_SCENARIOS) {
            assertTrue(
                scenario.testTag.startsWith("scenario."),
                "Scenario '${scenario.title}' testTag '${scenario.testTag}' should start with 'scenario.'",
            )
        }
    }

    @Test
    fun `catalogue includes the v1 baseline scenarios`() {
        val titles = ALL_SCENARIOS.map { it.title }
        assertTrue("Counter + text input" in titles)
        assertTrue("Popup (in-canvas)" in titles)
        assertTrue("Multi-window (secondary Window)" in titles)
        assertTrue("Focus traversal" in titles)
        assertTrue("Scrollable list" in titles)
        assertTrue("HiDPI coordinate targets" in titles)
    }

    @Test
    fun `the counter scenario sits first so the legacy demo flow continues to find it`() {
        // Main.kt's spectre.demo flow targets the incrementButton / textInput / echoText tags
        // owned by CounterScenario. App.kt selects the first scenario by default, so keeping
        // CounterScenario at index 0 preserves that flow.
        assertEquals(CounterScenario, ALL_SCENARIOS.first())
    }
}

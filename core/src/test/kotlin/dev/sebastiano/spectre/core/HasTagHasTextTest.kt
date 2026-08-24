package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit-level tests for [ComposeAutomator.hasTag] / [ComposeAutomator.hasText]: they are boolean
 * presence checks over the same current-snapshot finders, with no extra
 * [ComposeAutomator.refreshWindows] of their own. True-positive matches against live Compose live
 * in the sample-desktop validation suite (`HasTagHasTextValidationTest`).
 */
class HasTagHasTextTest {

    @Test
    fun `hasTag is false on an empty tracked-window snapshot`() {
        val automator = emptySnapshotAutomator()
        assertFalse(automator.hasTag("anything"))
        assertEquals(automator.findByTestTag("anything").isNotEmpty(), automator.hasTag("anything"))
    }

    @Test
    fun `hasText is false on an empty tracked-window snapshot`() {
        val automator = emptySnapshotAutomator()
        assertFalse(automator.hasText("anything"))
        assertEquals(automator.findByText("anything").isNotEmpty(), automator.hasText("anything"))
    }

    @Test
    fun `hasText substring overload matches findByText exact false`() {
        val automator = emptySnapshotAutomator()
        assertEquals(
            automator.findByText("any", exact = false).isNotEmpty(),
            automator.hasText("any", exact = false),
        )
    }

    @Test
    fun `hasText TextQuery overload matches findByText query`() {
        val automator = emptySnapshotAutomator()
        val query = TextQuery.substring("any")
        assertEquals(automator.findByText(query).isNotEmpty(), automator.hasText(query))
    }

    private fun emptySnapshotAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless(), discoverWindows = false)
}

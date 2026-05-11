package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.NodeKey
import dev.sebastiano.spectre.core.TextQuery
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Proves the R1 centralized-matcher contract end to end: the live path (`SemanticsReader`, which
 * filters with `LiveNodeMatcher` while walking the Compose tree on the EDT) and the snapshot path
 * (`AutomatorWindow`, which filters with `SnapshotNodeMatcher` over an already projected
 * `AutomatorNode` collection) return equal node sets for the same fixture. If the two ever diverge
 * — selector semantics drift, one path collects fewer or different nodes — this test fails before
 * the bug reaches users.
 *
 * Lives in the validation suite because the matchers operate on real Compose `SemanticsNode`s and
 * `AutomatorNode`s; constructing either without a live Compose surface is not feasible.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeMatcherParityValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre R1 matcher parity")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `live and snapshot paths agree on findByTestTag results`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            // Make sure the counter is on screen before we capture both sides.
            waitForTestTag("incrementButton")
            refreshWindows()

            val liveKeys = findByTestTag("incrementButton").map { it.key }.toSet()
            val snapshotKeys =
                tree()
                    .windows()
                    .flatMap { it.findByTestTag("incrementButton") }
                    .map { it.key }
                    .toSet()

            assertEquals(liveKeys, snapshotKeys, "live vs snapshot diverged for findByTestTag")
            assertTrue(liveKeys.isNotEmpty(), "expected at least one match for 'incrementButton'")
        }
    }

    @Test
    fun `live and snapshot paths agree on findByText with exact query`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val targetText = button.text ?: error("incrementButton must expose its 'Count: N' text")
            refreshWindows()

            val query = TextQuery.exact(targetText)
            val liveKeys = findByText(query).map(::nodeKey).toSet()
            val snapshotKeys =
                tree().windows().flatMap { it.findByText(query) }.map(::nodeKey).toSet()

            assertEquals(liveKeys, snapshotKeys, "live vs snapshot diverged for findByText(exact)")
            assertTrue(liveKeys.isNotEmpty(), "expected at least one match for '$targetText'")
        }
    }

    @Test
    fun `live and snapshot paths return empty for an unknown selector`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            waitForTestTag("incrementButton")
            refreshWindows()

            val unknown = "this.tag.definitely.does.not.exist"
            val liveKeys = findByTestTag(unknown).map { it.key }.toSet()
            val snapshotKeys =
                tree().windows().flatMap { it.findByTestTag(unknown) }.map { it.key }.toSet()

            assertEquals(emptySet(), liveKeys)
            assertEquals(emptySet(), snapshotKeys)
        }
    }

    private fun nodeKey(node: dev.sebastiano.spectre.core.AutomatorNode): NodeKey = node.key
}

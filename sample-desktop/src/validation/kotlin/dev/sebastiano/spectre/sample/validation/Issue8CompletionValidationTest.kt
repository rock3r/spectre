package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Closes the remaining v1 checklist items on #8: thread safety / snapshot coherence under heavy
 * recomposition, and the wait-contract correctness while a background animation never settles.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Issue8CompletionValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre #8 completion")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `automator reads coherent snapshots while the tree mutates at 60 Hz`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.recomposition")
            click(waitForTestTag("recomp.toggleButton"))

            // Hammer refreshWindows + findOneByTestTag for a few seconds. The ticker text node
            // is the always-present anchor — every read must succeed without throwing and must
            // return either null (mid-mutation snapshot dropped the node briefly, very rare) or
            // a node carrying a coherent ticker label. The list under the ticker mutates from
            // 0..32 items every 16ms, so we never assert on the item count — only that the
            // automator never throws and the ticker stays observable.
            val deadline = TimeSource.Monotonic.markNow() + STRESS_DURATION
            var iterations = 0
            var nullSnapshots = 0
            while (deadline.hasNotPassedNow()) {
                refreshWindows()
                val ticker = findOneByTestTag("recomp.ticker")
                if (ticker == null) nullSnapshots++ else assertNotNull(ticker.text)
                // Walk the items list — we don't care which indices exist, just that the read
                // doesn't crash on a list whose size changed mid-read.
                allNodes()
                iterations++
            }
            click(waitForTestTag("recomp.toggleButton"))

            assertTrue(iterations > MIN_STRESS_ITERATIONS, "Stress loop did $iterations iterations")
            // A null snapshot here and there is acceptable (the ticker text is briefly absent
            // during a recomposition window), but the vast majority of reads must succeed.
            assertTrue(
                nullSnapshots < iterations / 4,
                "Too many null ticker snapshots: $nullSnapshots / $iterations iterations",
            )
        }
    }

    @Test
    @Order(2)
    fun `eventually settles on event-driven targets while a background animation never settles`() =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.animation")
                // The spinner is rotating forever — confirm it's there but don't wait for it to
                // stop.
                assertNotNull(waitForTestTag("anim.spinner"))
                // anim.settled does not exist yet. The wait helper must not block forever just
                // because the animation is running; eventually() should return as soon as the
                // event-driven node appears.
                click(waitForTestTag("anim.toggleButton"))
                val settled =
                    eventually(description = "anim.settled appears", timeout = 5.seconds) {
                        findOneByTestTag("anim.settled")
                    }
                assertNotNull(
                    settled.text,
                    "Settled marker should expose its text once the click lands",
                )
            }
        }

    private companion object {
        val STRESS_DURATION = 3.seconds
        const val MIN_STRESS_ITERATIONS: Int = 30
    }
}

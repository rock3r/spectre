@file:OptIn(
    dev.sebastiano.spectre.core.InternalSpectreApi::class,
    dev.sebastiano.spectre.core.perf.ExperimentalSpectreApi::class,
)

package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.perf.RecompositionMonitor
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * End-to-end coverage for [RecompositionMonitor] against a live Compose Desktop window.
 *
 * The unit tests in `:core` exercise the counter math through an internal seam and the reflection
 * chain through a fake hierarchy keyed by field name. They cannot verify that the chain still lines
 * up with the real CMP internal field names, that the `CompositionObserver` actually fires when
 * Compose recomposes, that the EDT-marshalled discovery path doesn't deadlock against Spectre's
 * other EDT users, or that disposal teardown is clean. This class fills all four gaps by spawning
 * the sample app via [SampleAppFixture] and asserting against the counter scenario.
 *
 * Run via `./gradlew :sample-desktop:validationTest`. Opt-in, requires a real display.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RecompositionMonitorValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre recomposition monitor validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `monitor attaches to the live ComposeWindow recomposer`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            waitForTestTag("incrementButton")

            monitorRecompositions().use { monitor ->
                // The monitor subscribes to the WindowTracker StateFlow on Dispatchers.Default;
                // attachment happens asynchronously inside readOnEdt as soon as the first
                // emission is processed. Driving a refresh and polling activeSurfaces gives the
                // reconciler a deterministic chance to install observers without a fixed sleep.
                refreshWindows()
                eventually("monitor to attach a surface", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces > 0) Unit else null
                }

                val active = monitor.activeSurfaces
                println("[monitor] attached surfaces: $active")
                assertTrue(active > 0, "Expected ≥1 attached surface; got $active")
            }
        }
    }

    @Test
    @Order(2)
    fun `clicking the counter increments the lifetime total`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")

            monitorRecompositions().use { monitor ->
                refreshWindows()
                eventually("monitor to attach", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces > 0) Unit else null
                }

                // Drain any composition activity carried over from scenario navigation, then zero
                // the counters so the assertion measures only what the clicks produce.
                monitor.awaitCompositionIdle(quietPeriod = SETTLE_QUIET_PERIOD)
                monitor.reset()

                repeat(CLICKS) {
                    click(button)
                    // Let each click's recomposition complete before issuing the next one — the
                    // assertion needs distinct, observable passes, not an aggregated batch.
                    monitor.awaitCompositionIdle(quietPeriod = SETTLE_QUIET_PERIOD)
                }

                val snapshot = monitor.snapshot()
                println(
                    "[monitor] after $CLICKS clicks: total=${snapshot.total}, " +
                        "surfaces=${snapshot.activeSurfaces}, rate=${snapshot.ratePerSecond}/s"
                )
                // Compose may coalesce multiple invalidations into a single pass on the same
                // frame, so we assert ≥1 pass per click is a *lower* bound, not equality.
                assertTrue(
                    snapshot.total >= CLICKS.toLong(),
                    "Expected at least $CLICKS recompositions, got ${snapshot.total}",
                )
            }
        }
    }

    @Test
    @Order(3)
    fun `perSurface reports the live surface with a non-zero total after activity`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")

            monitorRecompositions().use { monitor ->
                refreshWindows()
                eventually("monitor to attach", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces > 0) Unit else null
                }
                monitor.awaitCompositionIdle(quietPeriod = SETTLE_QUIET_PERIOD)
                monitor.reset()

                click(button)
                monitor.awaitCompositionIdle(quietPeriod = SETTLE_QUIET_PERIOD)

                val perSurface = monitor.perSurface()
                println("[monitor] perSurface: $perSurface")
                assertTrue(perSurface.isNotEmpty(), "perSurface must list at least one surface")
                val countingSurfaces = perSurface.filter { it.total > 0 }
                assertTrue(
                    countingSurfaces.isNotEmpty(),
                    "Expected at least one surface with total>0 after clicking; got $perSurface",
                )
                // Surface ids are assigned by WindowTracker as `prefix:N` (e.g. "window:0"). We
                // don't lock the exact suffix to avoid coupling to discovery order, but every id
                // must follow that shape — drift here would mean monitor reconciliation has
                // diverged from the tracker's naming scheme.
                assertTrue(
                    countingSurfaces.all { it.surfaceId.contains(":") },
                    "Surface ids should be of form 'prefix:N'; got ${countingSurfaces.map { it.surfaceId }}",
                )
            }
        }
    }

    @Test
    @Order(4)
    fun `awaitRateBelow returns true after the UI settles`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")

            monitorRecompositions().use { monitor ->
                refreshWindows()
                eventually("monitor to attach", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces > 0) Unit else null
                }

                // Click to push the sliding window above zero, then assert the rate decays to
                // below the threshold once the UI settles. Generous threshold so machine noise
                // doesn't trip the assertion — the goal is "no thrashing", not a tight bound.
                click(button)
                val settled = monitor.awaitRateBelow(threshold = 10.0, timeout = AWAIT_RATE_TIMEOUT)
                println("[monitor] settled below 10/s within timeout: $settled")
                assertTrue(settled, "Expected ratePerSecond to drop below 10/s after settling")
            }
        }
    }

    private companion object {
        const val CLICKS: Int = 5
        val ATTACH_TIMEOUT = 5.seconds
        val AWAIT_RATE_TIMEOUT = 3.seconds
        // Slightly longer than the monitor's default 50ms quiet period: Compose Desktop on a real
        // display can delay the post-input recomposition by a frame or two under load, and a
        // 150ms quiet window gives the click event time to land on the EDT, be dispatched to the
        // scene, and produce its recomposition pass before we declare idle.
        val SETTLE_QUIET_PERIOD = 150.milliseconds
    }
}

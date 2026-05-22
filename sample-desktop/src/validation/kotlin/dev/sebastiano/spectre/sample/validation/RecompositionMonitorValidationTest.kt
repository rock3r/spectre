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
 * other EDT users, that the StateFlow reconciler attaches new surfaces as they arrive, or that the
 * sliding-window rate measurement holds up under high-frequency recomposition. This class fills
 * those gaps by spawning the sample app via [SampleAppFixture] and asserting against four
 * scenarios:
 * - **counter**: baseline single-surface attach, counter math, rate decay, per-surface reporting.
 * - **multiwindow**: a secondary `Window` gets its own `surfaceId`, its own counter, and detach
 *   clears its rate ring buffer when the window closes (while the lifetime `total` persists).
 * - **recomposition stress**: a ~60Hz ticker drives the rate well above an idle baseline, then
 *   [RecompositionMonitor.awaitRateBelow] confirms the rate decays after the ticker stops.
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

    @Test
    @Order(5)
    fun `secondary Window gets its own surfaceId and independent counter`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.multiwindow")
            val toggleButton = waitForTestTag("multiwindow.toggleButton")

            monitorRecompositions().use { monitor ->
                refreshWindows()
                eventually("monitor to attach main surface", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces >= 1) Unit else null
                }
                val initialSurfaces = monitor.activeSurfaces
                monitor.awaitCompositionIdle(quietPeriod = SETTLE_QUIET_PERIOD)
                monitor.reset()

                // Open the secondary window. WindowTracker won't pick it up until refreshWindows
                // fires the next StateFlow emission, after which the monitor's reconciler should
                // hop to the EDT, resolve the secondary's Recomposer, and attach a fresh surface.
                click(toggleButton)
                val secondaryText =
                    eventually("secondary window's text to appear", timeout = ATTACH_TIMEOUT) {
                        findOneByTestTag("multiwindow.secondary.text")
                    }
                eventually("monitor to attach the secondary surface", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces > initialSurfaces) Unit else null
                }
                val twoSurfaces = monitor.activeSurfaces
                val perSurfaceTwo = monitor.perSurface()
                println(
                    "[monitor] after opening secondary window: surfaces=$twoSurfaces, " +
                        "perSurface=$perSurfaceTwo"
                )
                assertTrue(
                    twoSurfaces > initialSurfaces,
                    "Expected activeSurfaces to grow when secondary window opens; " +
                        "was $initialSurfaces, now $twoSurfaces",
                )
                val ids = perSurfaceTwo.map { it.surfaceId }
                assertTrue(
                    ids.distinct().size == ids.size,
                    "Surface ids should be distinct across surfaces; got $ids",
                )
                // The text node lives in the secondary's semantics tree, so its TrackedWindow
                // surfaceId is the right key for asserting per-surface attribution.
                val secondarySurfaceId = secondaryText.surfaceId
                println("[monitor] secondary surfaceId: $secondarySurfaceId")
                assertTrue(
                    perSurfaceTwo.any { it.surfaceId == secondarySurfaceId },
                    "Expected monitor to expose the secondary surface ($secondarySurfaceId) in " +
                        "perSurface(); got ${perSurfaceTwo.map { it.surfaceId }}",
                )

                // Close the secondary window. After refresh, the reconciler detaches it — rate
                // for that surfaceId must read 0 immediately (the lifetime total persists).
                val dismissButton = waitForTestTag("multiwindow.secondary.dismissButton")
                click(dismissButton)
                waitUntilGone("multiwindow.secondary.text", timeout = ATTACH_TIMEOUT)
                refreshWindows()
                eventually("secondary surface's rate to drop to zero", timeout = ATTACH_TIMEOUT) {
                    val secondaryRate =
                        monitor
                            .perSurface()
                            .firstOrNull { it.surfaceId == secondarySurfaceId }
                            ?.ratePerSecond
                    if (secondaryRate != null && secondaryRate == 0.0) Unit else null
                }
                val perSurfaceAfter = monitor.perSurface()
                println("[monitor] after closing secondary: perSurface=$perSurfaceAfter")
                val secondaryAfter = perSurfaceAfter.firstOrNull {
                    it.surfaceId == secondarySurfaceId
                }
                assertTrue(
                    secondaryAfter != null && secondaryAfter.ratePerSecond == 0.0,
                    "Closed surface should report rate=0; got $secondaryAfter",
                )
            }
        }
    }

    @Test
    @Order(6)
    fun `recomposition stress drives rate above threshold then settles`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.recomposition")
            val toggle = waitForTestTag("recomp.toggleButton")

            monitorRecompositions().use { monitor ->
                refreshWindows()
                eventually("monitor to attach", timeout = ATTACH_TIMEOUT) {
                    if (monitor.activeSurfaces > 0) Unit else null
                }
                monitor.awaitCompositionIdle(quietPeriod = SETTLE_QUIET_PERIOD)
                monitor.reset()

                // The stress scenario ticks every 16ms while running, producing ~60 recompositions
                // per second on the main scene. Start it, let it run for one sliding-window worth
                // of time so the rate stabilises, then assert it's well above an idle baseline.
                click(toggle)
                kotlinx.coroutines.delay(STRESS_RUN_DURATION_MS)

                val activeRate = monitor.ratePerSecond
                val activeTotal = monitor.total
                println("[monitor] under stress: total=$activeTotal, rate=${activeRate}/s")
                assertTrue(
                    activeRate >= STRESS_MIN_RATE,
                    "Expected ratePerSecond ≥ $STRESS_MIN_RATE during stress, got $activeRate",
                )
                assertTrue(
                    activeTotal >= STRESS_MIN_TOTAL,
                    "Expected total ≥ $STRESS_MIN_TOTAL during stress, got $activeTotal",
                )

                // Stop the ticker; the rate window should drain back below threshold via
                // awaitRateBelow. Validates both the rate decay and the await helper under
                // realistic post-thrashing conditions.
                click(toggle)
                val calmed =
                    monitor.awaitRateBelow(
                        threshold = STRESS_CALM_THRESHOLD,
                        timeout = STRESS_CALM_TIMEOUT,
                    )
                println(
                    "[monitor] after stop: rate=${monitor.ratePerSecond}/s, calmed=$calmed " +
                        "(threshold=$STRESS_CALM_THRESHOLD)"
                )
                assertTrue(
                    calmed,
                    "Expected rate to drop below $STRESS_CALM_THRESHOLD/s within $STRESS_CALM_TIMEOUT",
                )
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

        // Stress thresholds: the recomp scenario ticks at ~60Hz. Take the rate measurement after
        // letting it run for STRESS_RUN_DURATION_MS so the 1s sliding window is fully populated.
        // MIN_RATE is well below 60 to absorb scheduling jitter on slower CI hardware.
        const val STRESS_RUN_DURATION_MS: Long = 1_200L
        const val STRESS_MIN_RATE: Double = 20.0
        const val STRESS_MIN_TOTAL: Long = 20L
        const val STRESS_CALM_THRESHOLD: Double = 5.0
        val STRESS_CALM_TIMEOUT = 3.seconds
    }
}

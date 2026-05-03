package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
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
 * End-to-end perf checks for #14 — "nice-to-validate" timing budgets on a real display.
 *
 * These are sanity-bound, not microbenchmark-precise: the assertions use generous ceilings so that
 * the suite catches order-of-magnitude regressions (a 10× slowdown in tree traversal, a popup that
 * suddenly takes seconds to appear) without flaking on machine-to-machine variance.
 *
 * Each test prints its measurement to stdout for ad-hoc trend tracking; tighten the budgets only
 * when you have evidence the CI hardware is consistent enough to support a tighter limit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Issue14PerfValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre #14 validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `tree traversal over a 200-item LazyColumn stays under the budget`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.scroll")
            waitForTestTag("scroll.list")

            // Warmup so JIT and Skiko caches are hot — the first traversal pays one-time costs.
            repeat(WARMUP_ITERATIONS) {
                refreshWindows()
                allNodes()
            }

            val source = TimeSource.Monotonic
            val samples =
                (0 until MEASURED_ITERATIONS).map {
                    val start = source.markNow()
                    refreshWindows()
                    val nodes = allNodes()
                    val elapsed = start.elapsedNow()
                    check(nodes.size > MIN_EXPECTED_NODES) {
                        "Expected >$MIN_EXPECTED_NODES nodes in scroll scenario, got ${nodes.size}"
                    }
                    elapsed.inWholeMilliseconds
                }
            val median = samples.sorted()[samples.size / 2]
            println("[#14] tree traversal median: ${median}ms (samples=$samples)")
            assertTrue(
                median < TREE_TRAVERSAL_BUDGET_MS,
                "Tree traversal median ${median}ms exceeds ${TREE_TRAVERSAL_BUDGET_MS}ms budget",
            )
        }
    }

    @Test
    @Order(2)
    fun `popup discoverability latency stays under the budget`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.popup")
            val toggle = waitForTestTag("popup.toggleButton")

            // Warm one cycle to amortise first-popup compositing.
            click(toggle)
            waitForTestTag("popup.body")
            click(waitForTestTag("popup.dismissButton"))
            waitUntilGone("popup.body")

            val source = TimeSource.Monotonic
            val samples =
                (0 until POPUP_ITERATIONS).map {
                    val freshToggle = waitForTestTag("popup.toggleButton")
                    val start = source.markNow()
                    click(freshToggle)
                    waitForTestTag("popup.body", timeout = POPUP_OUTER_TIMEOUT)
                    val elapsed = start.elapsedNow()
                    click(waitForTestTag("popup.dismissButton"))
                    waitUntilGone("popup.body")
                    elapsed.inWholeMilliseconds
                }
            val median = samples.sorted()[samples.size / 2]
            println("[#14] popup-open median: ${median}ms (samples=$samples)")
            assertTrue(
                median < POPUP_BUDGET_MS,
                "Popup-open median ${median}ms exceeds ${POPUP_BUDGET_MS}ms budget",
            )
        }
    }

    @Test
    @Order(3)
    fun `screenshot warmup completes within a reasonable budget`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")

            // Cold call — first screenshot pays Skiko init costs on the synthetic paint path.
            val coldSource = TimeSource.Monotonic.markNow()
            screenshot(button)
            val coldMs = coldSource.elapsedNow().inWholeMilliseconds
            println("[#14] screenshot cold: ${coldMs}ms")

            // Hot call — should be much faster once paths are warm.
            val hotSource = TimeSource.Monotonic.markNow()
            screenshot(button)
            val hotMs = hotSource.elapsedNow().inWholeMilliseconds
            println("[#14] screenshot hot: ${hotMs}ms")

            assertTrue(
                coldMs < SCREENSHOT_COLD_BUDGET_MS,
                "Cold screenshot ${coldMs}ms exceeds ${SCREENSHOT_COLD_BUDGET_MS}ms budget",
            )
            assertTrue(
                hotMs < SCREENSHOT_HOT_BUDGET_MS,
                "Hot screenshot ${hotMs}ms exceeds ${SCREENSHOT_HOT_BUDGET_MS}ms budget",
            )
        }
    }

    private companion object {
        const val WARMUP_ITERATIONS: Int = 3
        const val MEASURED_ITERATIONS: Int = 5
        // Lazy lists virtualise their item count — only the items currently in the viewport are
        // realised in the semantics tree. A handful (visible items + scenario chrome + picker) is
        // all we can rely on, so this is a presence check, not a tree-size check.
        const val MIN_EXPECTED_NODES: Int = 10
        const val TREE_TRAVERSAL_BUDGET_MS: Long = 500L

        const val POPUP_ITERATIONS: Int = 5
        const val POPUP_BUDGET_MS: Long = 1_000L

        const val SCREENSHOT_COLD_BUDGET_MS: Long = 5_000L
        const val SCREENSHOT_HOT_BUDGET_MS: Long = 1_000L

        val POPUP_OUTER_TIMEOUT = 5.seconds
    }
}

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import org.junit.rules.ExternalResource

/**
 * JUnit 4 [org.junit.Rule] that owns a per-test [ComposeAutomator] instance.
 *
 * Usage:
 * ```
 * @get:Rule val automatorRule = ComposeAutomatorRule()
 *
 * @Test fun something() {
 *     val node = automatorRule.automator.findOneByTestTag("Send")
 *     ...
 * }
 * ```
 *
 * ## Lifecycle
 *
 * The [factory] is invoked in `before` (before each `@Test` method); the resulting automator is
 * available via [automator] for the duration of the test and goes out of scope in `after`.
 * Accessing [automator] outside a running test throws [IllegalStateException]. The default factory
 * is `ComposeAutomator.inProcess()`; tests that need a stub for headless CI or focused unit testing
 * can supply their own factory.
 *
 * Both [factory] invocation and automator interaction can touch the EDT; standard Spectre EDT rules
 * apply (no EDT callers of suspend wait helpers; see
 * [`waitForIdle` / `waitForNode` / `waitForVisualIdle`][ComposeAutomator]).
 *
 * Prefer [ComposeAutomatorExtension] when using JUnit 5 — JUnit 5's parameter-injection model is a
 * better fit for parallel test execution.
 */
class ComposeAutomatorRule(private val factory: AutomatorFactory) : ExternalResource() {

    // Explicit no-arg secondary constructor so JUnit 4 callers can write
    // `@get:Rule val r = ComposeAutomatorRule()` without relying on Kotlin's
    // default-parameter constructor synthesis (consistent with ComposeAutomatorExtension).
    constructor() : this({ ComposeAutomator.inProcess() })

    private var instance: ComposeAutomator? = null

    val automator: ComposeAutomator
        get() =
            checkNotNull(instance) {
                "ComposeAutomatorRule.automator accessed outside of a running test"
            }

    public override fun before() {
        instance = factory()
    }

    public override fun after() {
        // Future hook: when the automator gains lifecycle-aware resources (recordings,
        // background pollers, etc.) tear them down here.
        instance = null
    }
}

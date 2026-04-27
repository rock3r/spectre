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
 * The [factory] defaults to `ComposeAutomator.inProcess()`. Tests that need a stub for headless CI
 * or focused unit testing can supply their own factory.
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

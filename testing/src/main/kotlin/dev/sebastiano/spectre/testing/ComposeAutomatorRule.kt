package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement

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
 * The [factory] is invoked before each `@Test` method; the resulting automator is available via
 * [automator] for the duration of the test and goes out of scope after the test (including after
 * failure-artifact capture). Accessing [automator] outside a running test throws
 * [IllegalStateException]. The default factory is `ComposeAutomator.inProcess()`; tests that need a
 * stub for headless CI or focused unit testing can supply their own factory.
 *
 * ## Failure artifacts (#205)
 *
 * When a test fails, this rule captures atomic screenshots + semantics for every known window
 * **after** the failure and **before** the automator is cleared. Default-on; opt out with
 * `FailureArtifactsConfig(enabled = false)`.
 *
 * ### RuleChain
 *
 * Keep this rule **innermost** (last `.around(...)` in a [org.junit.rules.RuleChain]) so failure
 * capture runs while the automator and windows are still available, before outer rules tear down UI
 * or process state:
 * ```
 * @get:Rule
 * val chain = RuleChain.outerRule(myWindowRule).around(ComposeAutomatorRule())
 * ```
 *
 * Both [factory] invocation and automator interaction can touch the EDT; standard Spectre EDT rules
 * apply (no EDT callers of suspend wait helpers; see
 * [`waitForIdle` / `waitForNode` / `waitForVisualIdle`][ComposeAutomator]).
 *
 * Prefer [ComposeAutomatorExtension] when using JUnit 5 — JUnit 5's parameter-injection model is a
 * better fit for parallel test execution.
 */
public class ComposeAutomatorRule(
    private val factory: AutomatorFactory,
    private val failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
) : ExternalResource() {

    // Explicit no-arg secondary constructor so JUnit 4 callers can write
    // `@get:Rule val r = ComposeAutomatorRule()` without relying on Kotlin's
    // default-parameter constructor synthesis (consistent with ComposeAutomatorExtension).
    public constructor() : this({ ComposeAutomator.inProcess() })

    public constructor(
        failureArtifacts: FailureArtifactsConfig
    ) : this({ ComposeAutomator.inProcess() }, failureArtifacts)

    private var instance: ComposeAutomator? = null

    public val automator: ComposeAutomator
        get() =
            checkNotNull(instance) {
                "ComposeAutomatorRule.automator accessed outside of a running test"
            }

    /**
     * Full lifecycle so failure capture runs **before** [after] clears the automator.
     * [ExternalResource.apply] would run `after` in a `finally` before callers can observe the
     * failure, which is too late for window capture.
     */
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                before()
                try {
                    // runCatching so both Exception and AssertionError (JUnit 4 failures) are
                    // captured without a broad catch-Throwable detekt hit at this boundary.
                    val outcome = runCatching { base.evaluate() }
                    outcome.exceptionOrNull()?.let { captureOnFailure(description) }
                    outcome.getOrThrow()
                } finally {
                    after()
                }
            }
        }
    }

    public override fun before() {
        instance = factory()
    }

    public override fun after() {
        // Future hook: when the automator gains lifecycle-aware resources (recordings,
        // background pollers, etc.) tear them down here.
        instance = null
    }

    private fun captureOnFailure(description: Description) {
        val automator = instance ?: return
        val testClass = description.className ?: "UnknownClass"
        val testMethod = description.methodName ?: description.displayName
        FailureArtifactHooks.recordFailure(
            automator = automator,
            config = failureArtifacts,
            testClassName = testClass,
            testMethodName = testMethod,
            publishReport = { _, _ ->
                // JUnit 4 has no report-entry API equivalent to JUnit 5's publishReportEntry.
                // Paths still land on disk under build/reports/spectre for CI globs.
            },
        )
    }
}

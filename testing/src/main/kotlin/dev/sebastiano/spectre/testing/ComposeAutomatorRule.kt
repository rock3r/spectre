@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
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
 * ## Failure video (#206)
 *
 * Optional whole-test recording via [FailureVideoConfig] (default [FailureVideoPolicy.Off]). When
 * the policy is not off, recording starts with the automator and is stopped+finalized in teardown
 * before the keep/delete decision. Non-failure aborts align with stills rules.
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
public class ComposeAutomatorRule
internal constructor(
    // `factory` MUST stay last among public-facing construction parameters — see the matching note
    // on ComposeAutomatorExtension. Kotlin's trailing-lambda convention binds
    // `ComposeAutomatorRule { … }` to the last parameter, so an optional non-function parameter
    // after it breaks every trailing-lambda call site.
    private val failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
    private val failureVideo: FailureVideoConfig = FailureVideoConfig(),
    private val videoStarter: FailureVideoStarter = AutoFailureVideoStarter,
    private val inputIsolation: InputIsolationConfig = InputIsolationConfig.perInteraction(),
    private val leaseFactory: InputTestLeaseFactory = ProductionInputTestLeaseFactory,
    private val acquireBeforeAutoFactory: Boolean = false,
    private val factory: AutomatorFactory,
) : ExternalResource() {

    public constructor(
        failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
        failureVideo: FailureVideoConfig = FailureVideoConfig(),
        factory: AutomatorFactory,
    ) : this(
        failureArtifacts = failureArtifacts,
        failureVideo = failureVideo,
        videoStarter = AutoFailureVideoStarter,
        factory = factory,
    )

    @ExperimentalSpectreInputCoordinationApi
    public constructor(
        inputIsolation: InputIsolationConfig
    ) : this(
        inputIsolation = inputIsolation,
        acquireBeforeAutoFactory = true,
        factory = defaultAutomatorFactory(inputIsolation),
    )

    @ExperimentalSpectreInputCoordinationApi
    public constructor(
        failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
        failureVideo: FailureVideoConfig = FailureVideoConfig(),
        inputIsolation: InputIsolationConfig,
        factory: AutomatorFactory,
    ) : this(
        failureArtifacts = failureArtifacts,
        failureVideo = failureVideo,
        videoStarter = AutoFailureVideoStarter,
        inputIsolation = inputIsolation,
        factory = factory,
    )

    // Explicit no-arg secondary constructor so JUnit 4 callers can write
    // `@get:Rule val r = ComposeAutomatorRule()` without relying on Kotlin's
    // default-parameter constructor synthesis (consistent with ComposeAutomatorExtension).
    public constructor() : this(factory = { ComposeAutomator.inProcess() })

    public constructor(
        failureArtifacts: FailureArtifactsConfig
    ) : this(failureArtifacts = failureArtifacts, factory = { ComposeAutomator.inProcess() })

    public constructor(
        failureVideo: FailureVideoConfig
    ) : this(
        failureArtifacts = FailureArtifactsConfig(),
        failureVideo = failureVideo,
        factory = { ComposeAutomator.inProcess() },
    )

    /**
     * Pre-#206 shape with default args so already-compiled Kotlin callers that used default
     * parameters / trailing-lambda factories stay binary-compatible.
     */
    public constructor(
        failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
        factory: AutomatorFactory = { ComposeAutomator.inProcess() },
    ) : this(
        failureArtifacts = failureArtifacts,
        failureVideo = FailureVideoConfig(),
        factory = factory,
    )

    public constructor(
        failureArtifacts: FailureArtifactsConfig,
        failureVideo: FailureVideoConfig,
    ) : this(
        failureArtifacts = failureArtifacts,
        failureVideo = failureVideo,
        factory = { ComposeAutomator.inProcess() },
    )

    private var instance: ComposeAutomator? = null
    private var videoSession: FailureVideoSession? = null
    private var lastDescription: Description? = null
    /** Shared per-evaluate invocation id for stills + video when callers leave both null. */
    private var evaluateInvocationId: String? = null

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
                lastDescription = description
                // One invocation id for this evaluate() so stills and video share a method dir
                // when callers leave invocationId null on both configs.
                val testClass = description.className ?: "UnknownClass"
                val testMethod = description.methodName ?: description.displayName ?: "unknown"
                evaluateInvocationId =
                    failureVideo.invocationId?.takeIf { it.isNotBlank() }
                        ?: failureArtifacts.invocationId?.takeIf { it.isNotBlank() }
                        ?: "${testClass}#${testMethod}#${System.nanoTime()}"
                val isolation =
                    InputIsolationSession(
                        config = inputIsolation,
                        acquireBeforeAutoFactory = acquireBeforeAutoFactory,
                        ownerLabel = ownerLabel(description),
                        leaseFactory = leaseFactory,
                    )
                try {
                    isolation.acquireBeforeFactory()
                    instance = isolation.createAutomator(factory)
                    isolation.bindAfterFactory(automator)
                    startFailureVideo(lastDescription)
                    // runCatching so both Exception and AssertionError (JUnit 4 failures) are
                    // captured without a broad catch-Throwable detekt hit at this boundary.
                    val outcome = runCatching { base.evaluate() }
                    outcome.exceptionOrNull()?.let { failure ->
                        // Assumptions abort without a "failure"; skip stills (same as JUnit 5).
                        if (!FailureArtifactHooks.isNonFailureAbort(failure)) {
                            captureOnFailure(description)
                        }
                    }
                    // Finalize before rethrow so keep/delete runs while the automator is live.
                    // If finalize itself fails, abandon so we never leave an orphaned recorder.
                    val videoOutcome =
                        FailureVideoDecisions.outcomeFromThrowable(outcome.exceptionOrNull())
                    runCatching { finalizeVideo(videoOutcome) }
                        .onFailure {
                            videoSession?.abandon()
                            videoSession = null
                        }
                    outcome.getOrThrow()
                } finally {
                    // Safety net: never leave an active recorder if evaluate exits oddly.
                    videoSession?.abandon()
                    videoSession = null
                    try {
                        after()
                    } finally {
                        isolation.close()
                    }
                    lastDescription = null
                    evaluateInvocationId = null
                }
            }
        }
    }

    public override fun before() {
        instance = factory()
        startFailureVideo(lastDescription)
    }

    public override fun after() {
        instance = null
    }

    private fun startFailureVideo(description: Description?) {
        if (!FailureVideoDecisions.shouldStart(failureVideo.policy)) return
        val automator = instance ?: return
        val testClass = description?.className ?: "UnknownClass"
        val testMethod = description?.methodName ?: description?.displayName ?: "unknown"
        val invocation =
            failureVideo.invocationId?.takeIf { it.isNotBlank() }
                ?: evaluateInvocationId
                ?: "${testClass}#${testMethod}#${System.nanoTime()}"
        val videoConfig = failureVideo.copy(invocationId = invocation)
        val session = FailureVideoSession(config = videoConfig, starter = videoStarter)
        session.start(automator = automator, testClassName = testClass, testMethodName = testMethod)
        videoSession = session
    }

    private fun finalizeVideo(outcome: FailureVideoOutcome) {
        val session = videoSession ?: return
        videoSession = null
        session.finalizeAndApply(outcome)
    }

    private fun captureOnFailure(description: Description) {
        val automator = instance ?: return
        val testClass = description.className ?: "UnknownClass"
        val testMethod = description.methodName ?: description.displayName
        // JUnit 4 has no ExtensionContext.uniqueId; reuse the per-evaluate invocation id so
        // stills and video land as siblings under the same method directory.
        val config =
            failureArtifacts.copy(
                invocationId =
                    failureArtifacts.invocationId?.takeIf { it.isNotBlank() }
                        ?: evaluateInvocationId
                        ?: "${testClass}#${testMethod}#${System.nanoTime()}"
            )
        FailureArtifactHooks.recordFailure(
            automator = automator,
            config = config,
            testClassName = testClass,
            testMethodName = testMethod,
            publishReport = { _, _ ->
                // JUnit 4 has no report-entry API equivalent to JUnit 5's publishReportEntry.
                // Paths still land on disk under build/reports/spectre for CI globs.
            },
        )
    }

    private fun ownerLabel(description: Description): String {
        val className = description.testClass?.simpleName ?: "UnknownClass"
        val methodName = description.methodName?.substringBefore('[') ?: "unknown"
        return "$className#$methodName"
    }
}

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import java.lang.reflect.Method
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * JUnit 5 extension that owns a per-test [ComposeAutomator] instance.
 *
 * Usage with `@RegisterExtension` (the safest pattern — one extension instance per test class):
 * ```
 * @JvmField @RegisterExtension val automatorExt = ComposeAutomatorExtension()
 *
 * @Test fun something() {
 *     val node = automatorExt.automator.findOneByTestTag("Send")
 * }
 * ```
 *
 * The extension also implements [ParameterResolver], so tests can take a [ComposeAutomator]
 * parameter and have it injected automatically. This is the parallel-execution-safe form, because
 * each test resolves its own automator from the per-invocation [ExtensionContext.Store]:
 * ```
 * @ExtendWith(ComposeAutomatorExtension::class)
 * class MyTest {
 *     @Test fun something(automator: ComposeAutomator) { ... }
 * }
 * ```
 *
 * ## Failure artifacts (#205)
 *
 * When a test fails, this extension captures atomic screenshots + semantics for every known window
 * **after** the failure and **before** [afterEach] tears down the automator (via
 * [AfterTestExecutionCallback]). Default-on; opt out with `FailureArtifactsConfig(enabled =
 * false)`. Paths are published as JUnit report entries under
 * [FailureArtifactHooks.REPORT_ENTRY_KEY].
 *
 * ## Failure video (#206)
 *
 * Optional whole-test recording via [FailureVideoConfig] (default [FailureVideoPolicy.Off]). When
 * the policy is not off, recording starts in [beforeEach] and is stopped+finalized in [afterEach]
 * before the keep/delete decision (`onFailureKeep` deletes on pass; `always` keeps pass and fail).
 * Non-failure aborts use the same rules as stills and do not leave a kept video.
 *
 * The [factory] defaults to `ComposeAutomator.inProcess()`. Tests that need a stub for headless CI
 * or focused unit testing can supply their own factory.
 *
 * Concurrency: the per-test instance is keyed in [ExtensionContext.Store], so parameter resolution
 * remains correct even when JUnit 5 reuses one extension instance across parallel methods. The
 * [automator] property accessor returns the most recently created instance and is intended for the
 * typical sequential `@RegisterExtension` flow; callers running tests in parallel should rely on
 * parameter injection instead.
 */
public class ComposeAutomatorExtension
internal constructor(
    // `factory` MUST stay last among public-facing construction parameters. It is function-typed,
    // so Kotlin's trailing-lambda convention binds `ComposeAutomatorExtension { … }` to whichever
    // parameter comes last; putting an optional non-function parameter after it silently breaks
    // every trailing-lambda call site. AutomatorFactoryTrailingLambdaTest stops compiling if this
    // order is changed again.
    private val failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
    private val failureVideo: FailureVideoConfig = FailureVideoConfig(),
    private val videoStarter: FailureVideoStarter = AutoFailureVideoStarter,
    private val factory: AutomatorFactory,
) :
    BeforeEachCallback,
    AfterEachCallback,
    AfterTestExecutionCallback,
    LifecycleMethodExecutionExceptionHandler,
    ParameterResolver {

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

    // Explicit no-arg secondary constructor so JUnit 5's @ExtendWith — which reflectively
    // calls the no-arg constructor — can instantiate the extension. Kotlin's default-parameter
    // primary constructor does not emit a true JVM no-arg overload without @JvmOverloads.
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

    public constructor(
        failureArtifacts: FailureArtifactsConfig,
        factory: AutomatorFactory,
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

    @Volatile private var lastInstance: ComposeAutomator? = null

    public val automator: ComposeAutomator
        get() =
            checkNotNull(lastInstance) {
                "ComposeAutomatorExtension.automator accessed outside of a running test"
            }

    override fun beforeEach(context: ExtensionContext) {
        val automator = factory()
        val store = context.getStore(NAMESPACE)
        store.put(STORE_KEY, automator)
        lastInstance = automator
        startFailureVideo(context, automator)
    }

    override fun afterTestExecution(context: ExtensionContext) {
        val failure = context.executionException.orElse(null) ?: return
        captureFailureArtifacts(context, failure)
    }

    /**
     * Also capture when `@AfterEach` / `@BeforeEach` lifecycle methods fail (not covered by
     * [afterTestExecution], which only sees the test method's exception).
     */
    override fun handleAfterEachMethodExecutionException(
        context: ExtensionContext,
        throwable: Throwable,
    ) {
        captureFailureArtifacts(context, throwable)
        throw throwable
    }

    override fun handleBeforeEachMethodExecutionException(
        context: ExtensionContext,
        throwable: Throwable,
    ) {
        // Automator may already exist if factory ran; capture is best-effort.
        captureFailureArtifacts(context, throwable)
        // Video may have started; finalize with abort/fail outcome so we never leave an orphan.
        finalizeFailureVideo(context, throwable)
        throw throwable
    }

    private fun captureFailureArtifacts(context: ExtensionContext, cause: Throwable) {
        // Assumptions abort without a "failure"; do not write artifacts for those (test method
        // or lifecycle @BeforeEach/@AfterEach).
        if (FailureArtifactHooks.isNonFailureAbort(cause)) return
        val store = context.getStore(NAMESPACE)
        // One capture per invocation: test-method failure runs afterTestExecution, then a later
        // @AfterEach failure must not recapture (which would clear the earlier run-* tree).
        if (store.get(CAPTURED_KEY) == true) return
        val automator = store.get(STORE_KEY, ComposeAutomator::class.java) ?: return
        val testClass = context.testClass.map { it.name }.orElse("UnknownClass")
        val testMethod = context.testMethod.map { it.name }.orElseGet { context.displayName }
        val config =
            failureArtifacts.copy(
                invocationId =
                    failureArtifacts.invocationId?.takeIf { it.isNotBlank() } ?: context.uniqueId
            )
        FailureArtifactHooks.recordFailure(
            automator = automator,
            config = config,
            testClassName = testClass,
            testMethodName = testMethod,
            publishReport = { key, value -> context.publishReportEntry(key, value) },
        )
        store.put(CAPTURED_KEY, true)
    }

    override fun afterEach(context: ExtensionContext) {
        // Finalize video before clearing the automator so window-targeted backends still see
        // live windows during stop if needed; then drop the automator.
        val failure = context.executionException.orElse(null)
        finalizeFailureVideo(context, failure)
        context.getStore(NAMESPACE).remove(STORE_KEY)
        lastInstance = null
    }

    private fun startFailureVideo(context: ExtensionContext, automator: ComposeAutomator) {
        if (!FailureVideoDecisions.shouldStart(failureVideo.policy)) return
        val store = context.getStore(NAMESPACE)
        val testClass = context.testClass.map { it.name }.orElse("UnknownClass")
        val testMethod = context.testMethod.map { it.name }.orElseGet { context.displayName }
        val videoConfig =
            failureVideo.copy(
                invocationId =
                    failureVideo.invocationId?.takeIf { it.isNotBlank() } ?: context.uniqueId
            )
        val session = FailureVideoSession(config = videoConfig, starter = videoStarter)
        session.start(automator = automator, testClassName = testClass, testMethodName = testMethod)
        store.put(VIDEO_SESSION_KEY, session)
    }

    private fun finalizeFailureVideo(context: ExtensionContext, failure: Throwable?) {
        val store = context.getStore(NAMESPACE)
        if (store.get(VIDEO_FINALIZED_KEY) == true) return
        val session = store.remove(VIDEO_SESSION_KEY, FailureVideoSession::class.java)
        store.put(VIDEO_FINALIZED_KEY, true)
        if (session == null) return
        val outcome = FailureVideoDecisions.outcomeFromThrowable(failure)
        session.finalizeAndApply(outcome) { key, value -> context.publishReportEntry(key, value) }
    }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean =
        parameterContext.parameter.type == ComposeAutomator::class.java &&
            parameterContext.declaringExecutable is Method &&
            // Restrict resolution to per-test method invocations. Constructor parameters and
            // @BeforeAll / @AfterAll lifecycle hooks run outside the per-test window, when no
            // instance is in the Store; rejecting them lets other resolvers handle those slots
            // and avoids surfacing IllegalStateException to the runner.
            extensionContext.testMethod.isPresent

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any =
        checkNotNull(
            extensionContext.getStore(NAMESPACE).get(STORE_KEY, ComposeAutomator::class.java)
        ) {
            "ComposeAutomator parameter requested but no per-test instance is registered"
        }

    private companion object {
        // Per-extension-class namespace + a fixed key — JUnit 5 already scopes Store entries to
        // the current ExtensionContext, so the (namespace, key) pair is enough to keep parallel
        // test invocations from clobbering each other.
        val NAMESPACE: ExtensionContext.Namespace =
            ExtensionContext.Namespace.create(ComposeAutomatorExtension::class.java)
    }
}

// File-level private constant rather than `const val` inside the `private companion object`.
// The Kotlin compiler emits `const val` members of any companion as JVM-level
// `public static final` fields, which makes them visible in the ABI dump even though
// the companion itself is `private`. A file-level `private const val` compiles to a private
// static field on the file's facade class and stays out of the public ABI surface.
private const val STORE_KEY: String = "automator"
private const val CAPTURED_KEY: String = "failureArtifactsCaptured"
private const val VIDEO_SESSION_KEY: String = "failureVideoSession"
private const val VIDEO_FINALIZED_KEY: String = "failureVideoFinalized"

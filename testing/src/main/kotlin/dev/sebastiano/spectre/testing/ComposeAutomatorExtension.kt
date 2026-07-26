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
 * The [factory] defaults to `ComposeAutomator.inProcess()`. Tests that need a stub for headless CI
 * or focused unit testing can supply their own factory.
 *
 * Concurrency: the per-test instance is keyed in [ExtensionContext.Store], so parameter resolution
 * remains correct even when JUnit 5 reuses one extension instance across parallel methods. The
 * [automator] property accessor returns the most recently created instance and is intended for the
 * typical sequential `@RegisterExtension` flow; callers running tests in parallel should rely on
 * parameter injection instead.
 */
public class ComposeAutomatorExtension(
    private val factory: AutomatorFactory,
    private val failureArtifacts: FailureArtifactsConfig = FailureArtifactsConfig(),
) :
    BeforeEachCallback,
    AfterEachCallback,
    AfterTestExecutionCallback,
    LifecycleMethodExecutionExceptionHandler,
    ParameterResolver {

    // Explicit no-arg secondary constructor so JUnit 5's @ExtendWith — which reflectively
    // calls the no-arg constructor — can instantiate the extension. Kotlin's default-parameter
    // primary constructor does not emit a true JVM no-arg overload without @JvmOverloads.
    public constructor() : this({ ComposeAutomator.inProcess() })

    public constructor(
        failureArtifacts: FailureArtifactsConfig
    ) : this({ ComposeAutomator.inProcess() }, failureArtifacts)

    @Volatile private var lastInstance: ComposeAutomator? = null

    public val automator: ComposeAutomator
        get() =
            checkNotNull(lastInstance) {
                "ComposeAutomatorExtension.automator accessed outside of a running test"
            }

    override fun beforeEach(context: ExtensionContext) {
        val automator = factory()
        context.getStore(NAMESPACE).put(STORE_KEY, automator)
        lastInstance = automator
    }

    override fun afterTestExecution(context: ExtensionContext) {
        val failure = context.executionException.orElse(null) ?: return
        // Assumptions abort the test without a "failure"; do not write artifacts for those.
        if (failure is org.opentest4j.TestAbortedException) return
        captureFailureArtifacts(context)
    }

    /**
     * Also capture when `@AfterEach` / `@BeforeEach` lifecycle methods fail (not covered by
     * [afterTestExecution], which only sees the test method's exception).
     */
    override fun handleAfterEachMethodExecutionException(
        context: ExtensionContext,
        throwable: Throwable,
    ) {
        captureFailureArtifacts(context)
        throw throwable
    }

    override fun handleBeforeEachMethodExecutionException(
        context: ExtensionContext,
        throwable: Throwable,
    ) {
        // Automator may already exist if factory ran; capture is best-effort.
        captureFailureArtifacts(context)
        throw throwable
    }

    private fun captureFailureArtifacts(context: ExtensionContext) {
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
        // Future hook: when the automator gains lifecycle-aware resources (recordings,
        // background pollers, etc.) tear them down here.
        context.getStore(NAMESPACE).remove(STORE_KEY)
        lastInstance = null
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

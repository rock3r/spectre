package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import java.lang.reflect.Method
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
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
 * The [factory] defaults to `ComposeAutomator.inProcess()`. Tests that need a stub for headless CI
 * or focused unit testing can supply their own factory.
 *
 * Concurrency: the per-test instance is keyed in [ExtensionContext.Store], so parameter resolution
 * remains correct even when JUnit 5 reuses one extension instance across parallel methods. The
 * [automator] property accessor returns the most recently created instance and is intended for the
 * typical sequential `@RegisterExtension` flow; callers running tests in parallel should rely on
 * parameter injection instead.
 */
class ComposeAutomatorExtension(private val factory: AutomatorFactory) :
    BeforeEachCallback, AfterEachCallback, ParameterResolver {

    // Explicit no-arg secondary constructor so JUnit 5's @ExtendWith — which reflectively
    // calls the no-arg constructor — can instantiate the extension. Kotlin's default-parameter
    // primary constructor does not emit a true JVM no-arg overload without @JvmOverloads.
    constructor() : this({ ComposeAutomator.inProcess() })

    @Volatile private var lastInstance: ComposeAutomator? = null

    val automator: ComposeAutomator
        get() =
            checkNotNull(lastInstance) {
                "ComposeAutomatorExtension.automator accessed outside of a running test"
            }

    override fun beforeEach(context: ExtensionContext) {
        val automator = factory()
        context.getStore(NAMESPACE).put(STORE_KEY, automator)
        lastInstance = automator
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
        const val STORE_KEY: String = "automator"
    }
}

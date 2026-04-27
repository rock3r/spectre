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
 * Usage:
 * ```
 * @RegisterExtension val automatorExt = ComposeAutomatorExtension()
 *
 * @Test fun something() {
 *     val node = automatorExt.automator.findOneByTestTag("Send")
 *     ...
 * }
 * ```
 *
 * The extension also implements [ParameterResolver], so tests can take a [ComposeAutomator]
 * parameter and have it injected automatically:
 * ```
 * @ExtendWith(ComposeAutomatorExtension::class)
 * class MyTest {
 *     @Test fun something(automator: ComposeAutomator) { ... }
 * }
 * ```
 *
 * The [factory] defaults to `ComposeAutomator.inProcess()`. Tests that need a stub for headless CI
 * or focused unit testing can supply their own factory.
 */
class ComposeAutomatorExtension(
    private val factory: AutomatorFactory = { ComposeAutomator.inProcess() }
) : BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private var instance: ComposeAutomator? = null

    val automator: ComposeAutomator
        get() =
            checkNotNull(instance) {
                "ComposeAutomatorExtension.automator accessed outside of a running test"
            }

    override fun beforeEach(context: ExtensionContext) {
        instance = factory()
    }

    override fun afterEach(context: ExtensionContext) {
        // Future hook: when the automator gains lifecycle-aware resources (recordings,
        // background pollers, etc.) tear them down here.
        instance = null
    }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean =
        parameterContext.parameter.type == ComposeAutomator::class.java &&
            // Constructor injection happens before beforeEach, so the per-test instance does not
            // exist yet. Restrict resolution to method/lifecycle parameters where the instance
            // is guaranteed to be available.
            parameterContext.declaringExecutable is Method

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any = automator
}

@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.launch.LaunchAndAttach
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.agent.launch.LaunchedSession
import java.lang.reflect.Method
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * JUnit 5 extension that launches a process via [LaunchAndAttach] before each test and tears it
 * down afterwards.
 *
 * Composes with the same per-test lifecycle window as [ComposeAutomatorExtension] so future
 * failure-artifact hooks (#205) can plug into after-each teardown without the launch harness owning
 * that path.
 *
 * Sequential usage (`@RegisterExtension` property accessors):
 * ```
 * @JvmField
 * @RegisterExtension
 * val launchExt = LaunchAndAttachExtension(
 *     LaunchSpec(command = listOf("java", "-jar", "app.jar"))
 * )
 *
 * @Test fun exercise() {
 *     launchExt.automator.windows()
 * }
 * ```
 *
 * Parallel-safe usage (parameter injection from the per-invocation store):
 * ```
 * @JvmField
 * @RegisterExtension
 * val launchExt = LaunchAndAttachExtension(
 *     LaunchSpec(command = listOf("java", "-jar", "app.jar"))
 * )
 *
 * @Test fun exercise(session: LaunchedSession) {
 *     session.automator.windows()
 * }
 *
 * @Test fun alsoFine(automator: AttachedAutomator) {
 *     automator.windows()
 * }
 * ```
 *
 * Prefer a prod-like [LaunchSpec.command]. Gradle `run` / `hotRun` work but warn loudly.
 *
 * Note: this extension requires a [LaunchSpec], so it is registered with `@RegisterExtension`
 * rather than `@ExtendWith`. [ParameterResolver] still applies to parameters of methods on the same
 * test class.
 */
public class LaunchAndAttachExtension(
    private val spec: LaunchSpec,
    private val warningSink: (String) -> Unit = LaunchAndAttach.DEFAULT_WARNING_SINK,
) : BeforeEachCallback, AfterEachCallback, ParameterResolver {

    /**
     * Live session for the **most recent** sequential `@RegisterExtension` test. Prefer parameter
     * injection of [LaunchedSession] / [AttachedAutomator] (or [launchedFrom]) when running tests
     * in parallel.
     */
    public val launched: LaunchedSession
        get() =
            checkNotNull(lastSequentialSession) {
                "LaunchAndAttachExtension.launched accessed outside of a running test"
            }

    /** Attached automator for the current sequential test (same lifetime as [launched]). */
    public val automator: AttachedAutomator
        get() = launched.automator

    @Volatile private var lastSequentialSession: LaunchedSession? = null

    /**
     * Per-invocation session from [context] (parallel-safe). Prefer method-parameter injection of
     * [LaunchedSession] / [AttachedAutomator] from test bodies; this accessor is for other
     * extensions that already hold an [ExtensionContext].
     */
    public fun launchedFrom(context: ExtensionContext): LaunchedSession =
        checkNotNull(context.getStore(storeNamespace).get(STORE_KEY, LaunchedSession::class.java)) {
            "No LaunchAndAttach session registered for this test invocation"
        }

    override fun beforeEach(context: ExtensionContext) {
        val launchedSession = LaunchAndAttach.launch(spec, warningSink)
        context.getStore(storeNamespace).put(STORE_KEY, launchedSession)
        lastSequentialSession = launchedSession
    }

    override fun afterEach(context: ExtensionContext) {
        val stored = context.getStore(storeNamespace).remove(STORE_KEY, LaunchedSession::class.java)
        if (lastSequentialSession === stored) {
            lastSequentialSession = null
        }
        stored?.close()
    }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean {
        if (parameterContext.declaringExecutable !is Method) return false
        // Restrict resolution to per-test method invocations. Constructor parameters and
        // @BeforeAll / @AfterAll lifecycle hooks run outside the per-test window.
        if (!extensionContext.testMethod.isPresent) return false
        val type = parameterContext.parameter.type
        return type == LaunchedSession::class.java || type == AttachedAutomator::class.java
    }

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any {
        val session = launchedFrom(extensionContext)
        return when (parameterContext.parameter.type) {
            LaunchedSession::class.java -> session
            AttachedAutomator::class.java -> session.automator
            else ->
                error(
                    "LaunchAndAttachExtension cannot resolve parameter type " +
                        parameterContext.parameter.type.name
                )
        }
    }

    /**
     * Per-instance store namespace so two `@RegisterExtension` fields on the same test class do not
     * overwrite each other's sessions under a shared class-level key.
     */
    private val storeNamespace: ExtensionContext.Namespace =
        ExtensionContext.Namespace.create(LaunchAndAttachExtension::class.java, this)
}

// File-level private constant rather than companion `const val` (which leaks into public ABI).
private const val STORE_KEY: String = "launchedSession"

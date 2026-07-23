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
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * JUnit 5 extension that launches a process via [LaunchAndAttach] before each test and tears it
 * down afterwards.
 *
 * Composes with the same per-test lifecycle window as [ComposeAutomatorExtension] so future
 * failure-artifact hooks (#205) can plug into after-each teardown without the launch harness owning
 * that path.
 *
 * Sequential or multi-process usage (`@RegisterExtension` property accessors — instance-specific
 * and parallel-safe via a thread-local):
 * ```
 * @JvmField
 * @RegisterExtension
 * val app = LaunchAndAttachExtension(LaunchSpec(command = listOf("java", "-jar", "app.jar")))
 *
 * @JvmField
 * @RegisterExtension
 * val helper = LaunchAndAttachExtension(LaunchSpec(command = listOf("java", "-jar", "helper.jar")))
 *
 * @Test fun exercise() {
 *     app.automator.windows()
 *     helper.automator.windows()
 * }
 * ```
 *
 * Single-extension parameter injection (parallel-safe from the per-invocation store):
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
 * rather than `@ExtendWith`. Parameter injection is enabled only when the test class registers a
 * single [LaunchAndAttachExtension]; with two or more, use the property accessors so JUnit does not
 * see competing [ParameterResolver]s.
 */
public class LaunchAndAttachExtension(
    private val spec: LaunchSpec,
    private val warningSink: (String) -> Unit = LaunchAndAttach.DEFAULT_WARNING_SINK,
) : BeforeEachCallback, AfterEachCallback, ParameterResolver {

    /**
     * Live session for the current test on this thread. Backed by a thread-local so parallel
     * methods each see their own session even when multiple extension instances are registered.
     * Prefer parameter injection of [LaunchedSession] / [AttachedAutomator] when a single extension
     * is registered; prefer this accessor when multiple extensions share a class.
     */
    public val launched: LaunchedSession
        get() =
            checkNotNull(threadSession.get() ?: lastSequentialSession) {
                "LaunchAndAttachExtension.launched accessed outside of a running test"
            }

    /** Attached automator for the current test (same lifetime as [launched]). */
    public val automator: AttachedAutomator
        get() = launched.automator

    @Volatile private var lastSequentialSession: LaunchedSession? = null
    private val threadSession: ThreadLocal<LaunchedSession> = ThreadLocal()

    /**
     * Per-invocation session from [context] (parallel-safe). Prefer method-parameter injection of
     * [LaunchedSession] / [AttachedAutomator] from test bodies when a single extension is
     * registered; this accessor is for other extensions that already hold an [ExtensionContext].
     */
    public fun launchedFrom(context: ExtensionContext): LaunchedSession =
        checkNotNull(context.getStore(storeNamespace).get(STORE_KEY, LaunchedSession::class.java)) {
            "No LaunchAndAttach session registered for this test invocation"
        }

    override fun beforeEach(context: ExtensionContext) {
        val launchedSession = LaunchAndAttach.launch(spec, warningSink)
        context.getStore(storeNamespace).put(STORE_KEY, launchedSession)
        threadSession.set(launchedSession)
        lastSequentialSession = launchedSession
    }

    override fun afterEach(context: ExtensionContext) {
        val stored = context.getStore(storeNamespace).remove(STORE_KEY, LaunchedSession::class.java)
        if (threadSession.get() === stored) {
            threadSession.remove()
        }
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
        // With two+ registered instances JUnit would see competing resolvers for the same types;
        // refuse injection so callers use instance-specific property accessors instead.
        if (!isSoleRegisteredExtension(extensionContext)) return false
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

    private companion object {
        fun isSoleRegisteredExtension(context: ExtensionContext): Boolean {
            val testClass = context.testClass.orElse(null) ?: return true
            var count = 0
            var cls: Class<*>? = testClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    if (!field.isAnnotationPresent(RegisterExtension::class.java)) continue
                    if (LaunchAndAttachExtension::class.java.isAssignableFrom(field.type)) {
                        count++
                        if (count > 1) return false
                    }
                }
                cls = cls.superclass
            }
            return true
        }
    }
}

// File-level private constant rather than companion `const val` (which leaks into public ABI).
private const val STORE_KEY: String = "launchedSession"

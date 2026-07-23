@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.launch.LaunchAndAttach
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.agent.launch.LaunchedSession
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit 5 extension that launches a process via [LaunchAndAttach] before each test and tears it
 * down afterwards.
 *
 * Composes with the same per-test lifecycle window as [ComposeAutomatorExtension] so future
 * failure-artifact hooks (#205) can plug into after-each teardown without the launch harness owning
 * that path.
 *
 * Usage:
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
 * Prefer a prod-like [LaunchSpec.command]. Gradle `run` / `hotRun` work but warn loudly.
 */
public class LaunchAndAttachExtension(
    private val spec: LaunchSpec,
    private val warningSink: (String) -> Unit = LaunchAndAttach.DEFAULT_WARNING_SINK,
) : BeforeEachCallback, AfterEachCallback {

    /**
     * Live session for the **most recent** sequential `@RegisterExtension` test. Prefer
     * [launchedFrom] when running tests in parallel.
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

    /** Per-invocation session from [context] (parallel-safe). */
    public fun launchedFrom(context: ExtensionContext): LaunchedSession =
        checkNotNull(context.getStore(NAMESPACE).get(STORE_KEY, LaunchedSession::class.java)) {
            "No LaunchAndAttach session registered for this test invocation"
        }

    override fun beforeEach(context: ExtensionContext) {
        val launchedSession = LaunchAndAttach.launch(spec, warningSink)
        context.getStore(NAMESPACE).put(STORE_KEY, launchedSession)
        lastSequentialSession = launchedSession
    }

    override fun afterEach(context: ExtensionContext) {
        val stored = context.getStore(NAMESPACE).remove(STORE_KEY, LaunchedSession::class.java)
        if (lastSequentialSession === stored) {
            lastSequentialSession = null
        }
        stored?.close()
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace =
            ExtensionContext.Namespace.create(LaunchAndAttachExtension::class.java)
    }
}

private const val STORE_KEY: String = "launchedSession"

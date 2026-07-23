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

    @Volatile private var session: LaunchedSession? = null

    /** Live session for the current test; throws if accessed outside before/after. */
    public val launched: LaunchedSession
        get() =
            checkNotNull(session) {
                "LaunchAndAttachExtension.launched accessed outside of a running test"
            }

    /** Attached automator for the current test (same lifetime as [launched]). */
    public val automator: AttachedAutomator
        get() = launched.automator

    override fun beforeEach(context: ExtensionContext) {
        val launchedSession = LaunchAndAttach.launch(spec, warningSink)
        context.getStore(NAMESPACE).put(STORE_KEY, launchedSession)
        session = launchedSession
    }

    override fun afterEach(context: ExtensionContext) {
        val stored = context.getStore(NAMESPACE).remove(STORE_KEY, LaunchedSession::class.java)
        session = null
        stored?.close()
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace =
            ExtensionContext.Namespace.create(LaunchAndAttachExtension::class.java)
    }
}

private const val STORE_KEY: String = "launchedSession"

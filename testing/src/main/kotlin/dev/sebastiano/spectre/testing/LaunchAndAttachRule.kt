@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.launch.LaunchAndAttach
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.agent.launch.LaunchedSession
import org.junit.rules.ExternalResource

/**
 * JUnit 4 rule that launches a process via [LaunchAndAttach] before each test and tears it down
 * afterwards.
 *
 * Prefer [LaunchAndAttachExtension] when using JUnit 5.
 *
 * Usage:
 * ```
 * @get:Rule
 * val launchRule = LaunchAndAttachRule(
 *     LaunchSpec(command = listOf("java", "-jar", "app.jar"))
 * )
 * ```
 */
public class LaunchAndAttachRule(
    private val spec: LaunchSpec,
    private val warningSink: (String) -> Unit = LaunchAndAttach.DEFAULT_WARNING_SINK,
) : ExternalResource() {

    private var session: LaunchedSession? = null

    public val launched: LaunchedSession
        get() =
            checkNotNull(session) {
                "LaunchAndAttachRule.launched accessed outside of a running test"
            }

    public val automator: AttachedAutomator
        get() = launched.automator

    override fun before() {
        startSession()
    }

    override fun after() {
        stopSession()
    }

    /** Starts the launch session (same as JUnit 4 `before`). Exposed for tests. */
    public fun startSession() {
        stopSession()
        session = LaunchAndAttach.launch(spec, warningSink)
    }

    /** Tears down the launch session (same as JUnit 4 `after`). Exposed for tests. */
    public fun stopSession() {
        val toClose = session
        session = null
        toClose?.close()
    }
}

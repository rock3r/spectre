package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.JvmProcessInfo

/**
 * Recognises Gradle test executors: the worker JVMs that run a `Test` task.
 *
 * Gradle starts every worker as `GradleWorkerMain '<display name>'`, and names the ones that run
 * tests `Gradle Test Executor <n>`. A JVM whose parent is a test executor was started by a test,
 * not by a Gradle launch, so [LaunchDescendantDiscovery] never takes it for the launched app
 * (#603, #614).
 */
@ExperimentalSpectreAgentApi
internal object GradleTestExecutors {

    fun isTestExecutorDisplayName(displayName: String): Boolean =
        displayName.contains("gradle test executor", ignoreCase = true)

    /** The pids in [listed] that are Gradle test executors. */
    fun pidsIn(listed: List<JvmProcessInfo>): Set<Long> =
        listed.filter { isTestExecutorDisplayName(it.displayName) }.map { it.pid }.toSet()
}

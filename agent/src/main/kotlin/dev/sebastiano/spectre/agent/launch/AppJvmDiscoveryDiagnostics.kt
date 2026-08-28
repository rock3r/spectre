@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.time.Instant

/**
 * One JVM considered while discovering the app JVM for a Gradle-ish launch (#458).
 *
 * [rejectionReason] is null when this pid was still a candidate after the start-time / daemon /
 * client-pid filters. It is not a claim that the pid was selected.
 */
@ExperimentalSpectreAgentApi
internal data class AppJvmDiscoveryCandidate(
    val pid: Long,
    val displayName: String,
    val startInstant: Instant?,
    val rejectionReason: String?,
)

/**
 * Opt-in snapshot of one [LaunchDescendantDiscovery.selectAppJvm] decision. Captures the candidate
 * list, each start instant, the launch boundary, and why a leftover was rejected — the data a
 * future loaded `./gradlew check` miss needs, without guessing a gate change.
 */
@ExperimentalSpectreAgentApi
internal data class AppJvmDiscoveryRecord(
    val clientPid: Long,
    val nameFilter: String?,
    val launchBoundary: Instant?,
    val candidates: List<AppJvmDiscoveryCandidate>,
    val selectedPid: Long?,
) {
    fun format(): String = buildString {
        append(LOG_PREFIX)
        append(" clientPid=").append(clientPid)
        append(" nameFilter=").append(nameFilter.orEmpty())
        append(" boundary=").append(launchBoundary ?: "")
        append(" selected=").append(selectedPid ?: "")
        append('\n')
        for (candidate in candidates) {
            append("  pid=").append(candidate.pid)
            append(" displayName='").append(candidate.displayName).append("'")
            append(" start=").append(candidate.startInstant ?: "")
            append(" rejected=").append(candidate.rejectionReason.orEmpty())
            append('\n')
        }
    }
}

/** Side channel for [AppJvmDiscoveryRecord]. Default is [NoOp] so selection is unchanged. */
@ExperimentalSpectreAgentApi
internal fun interface AppJvmDiscoveryDiagnosticSink {
    fun emit(record: AppJvmDiscoveryRecord)

    companion object {
        val NoOp: AppJvmDiscoveryDiagnosticSink = AppJvmDiscoveryDiagnosticSink {}
    }
}

/**
 * Opt-in Gradle app-JVM discovery diagnostics (#458). Off unless the system property or env var is
 * the exact string `true`. Production [LaunchDescendantDiscovery.discoverAppJvm] wires
 * [sinkFromEnvironment]; tests inject a capturing sink.
 */
@ExperimentalSpectreAgentApi
internal object AppJvmDiscoveryDiagnostics {
    const val PROPERTY: String = "dev.sebastiano.spectre.agent.launch.discoveryDiagnostics"
    const val ENV: String = "SPECTRE_LAUNCH_DISCOVERY_DIAGNOSTICS"
    const val REASON_PREDATES_LAUNCH: String = "predates the launch"
    const val REASON_GRADLE_DAEMON: String = "gradle daemon"
    const val REASON_CLIENT_PID: String = "client pid"

    fun enabled(
        property: String? = System.getProperty(PROPERTY),
        env: String? = System.getenv(ENV),
    ): Boolean = property.equals("true", ignoreCase = true) || env.equals("true", ignoreCase = true)

    fun sinkFromEnvironment(
        property: String? = System.getProperty(PROPERTY),
        env: String? = System.getenv(ENV),
        write: (String) -> Unit = System.err::println,
    ): AppJvmDiscoveryDiagnosticSink =
        if (enabled(property, env)) {
            AppJvmDiscoveryDiagnosticSink { record -> write(record.format()) }
        } else {
            AppJvmDiscoveryDiagnosticSink.NoOp
        }
}

private const val LOG_PREFIX: String = "[spectre-launch-discovery]"

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAttachException
import dev.sebastiano.spectre.agent.SpectreProcesses

/** Lists JVM processes while excluding the daemon and the requesting CLI process. */
@OptIn(ExperimentalSpectreAgentApi::class)
internal class DaemonJvmProcessDiscovery(
    private val discover: () -> List<DaemonJvmProcessSummary> = {
        SpectreProcesses.listJvmProcesses().map { process ->
            DaemonJvmProcessSummary(pid = process.pid, displayName = process.displayName)
        }
    }
) {
    fun list(requesterPid: Long): DaemonResponse =
        try {
            DaemonResponse.JvmProcesses(
                discover()
                    .filter { process ->
                        process.pid != requesterPid && process.pid != ProcessHandle.current().pid()
                    }
                    .sortedBy { process -> process.pid }
            )
        } catch (exception: SpectreAttachException) {
            DaemonResponse.Error(
                code = DaemonErrorCode.AttachFailed,
                message = exception.message ?: "Failed to list attachable JVM processes",
            )
        }
}

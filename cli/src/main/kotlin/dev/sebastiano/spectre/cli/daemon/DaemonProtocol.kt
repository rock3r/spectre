package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** Shared client/daemon wire protocol metadata for Spectre's agent-facing entrypoints. */
@OptIn(ExperimentalSerializationApi::class)
public object DaemonProtocol {
    public val CurrentVersion: DaemonProtocolVersion = DaemonProtocolVersion(major = 1, minor = 3)

    public val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun checkCompatibility(
        client: DaemonProtocolVersion,
        daemon: DaemonProtocolVersion,
    ): VersionCompatibility =
        when {
            client.major != daemon.major -> VersionCompatibility.MajorMismatch
            daemon.minor < client.minor -> VersionCompatibility.DaemonTooOld
            else -> VersionCompatibility.Compatible
        }

    internal fun minimumDaemonVersion(request: DaemonRequest): DaemonProtocolVersion =
        when (request) {
            is DaemonRequest.Hello -> versionFor(MINIMUM_PROTOCOL_MINOR)
            is DaemonRequest.Attach,
            is DaemonRequest.Detach,
            DaemonRequest.ListSessions,
            DaemonRequest.Shutdown -> versionFor(SESSION_LIFECYCLE_INTRODUCED_MINOR)
            is DaemonRequest.Windows,
            is DaemonRequest.AllNodes,
            is DaemonRequest.FindByTestTag,
            is DaemonRequest.Click,
            is DaemonRequest.TypeText,
            is DaemonRequest.Screenshot -> versionFor(SESSION_COMMANDS_INTRODUCED_MINOR)
            is DaemonRequest.ListJvmProcesses -> versionFor(LIST_JVM_PROCESSES_INTRODUCED_MINOR)
        }

    private fun versionFor(minor: Int): DaemonProtocolVersion =
        DaemonProtocolVersion(major = CurrentVersion.major, minor = minor)

    private const val MINIMUM_PROTOCOL_MINOR: Int = 0
    private const val SESSION_LIFECYCLE_INTRODUCED_MINOR: Int = 1
    private const val SESSION_COMMANDS_INTRODUCED_MINOR: Int = 2
    private const val LIST_JVM_PROCESSES_INTRODUCED_MINOR: Int = 3
}

@Serializable public data class DaemonProtocolVersion(public val major: Int, public val minor: Int)

public enum class VersionCompatibility {
    Compatible,
    MajorMismatch,
    DaemonTooOld,
}

@OptIn(ExperimentalSpectreAgentApi::class)
@Serializable
public sealed interface DaemonRequest {
    @Serializable
    public data class Hello(public val clientVersion: DaemonProtocolVersion) : DaemonRequest

    @Serializable
    @SerialName("attach")
    public data class Attach(public val targetPid: Long) : DaemonRequest

    @Serializable
    @SerialName("detach")
    public data class Detach(public val sessionId: String) : DaemonRequest

    @Serializable @SerialName("listSessions") public data object ListSessions : DaemonRequest

    @Serializable
    @SerialName("listJvmProcesses")
    public data class ListJvmProcesses(public val requesterPid: Long) : DaemonRequest

    @Serializable
    @SerialName("windows")
    public data class Windows(public val sessionId: String) : DaemonRequest

    @Serializable
    @SerialName("allNodes")
    public data class AllNodes(public val sessionId: String) : DaemonRequest

    @Serializable
    @SerialName("findByTestTag")
    public data class FindByTestTag(public val sessionId: String, public val tag: String) :
        DaemonRequest

    @Serializable
    @SerialName("click")
    public data class Click(public val sessionId: String, public val nodeKey: String) :
        DaemonRequest

    @Serializable
    @SerialName("typeText")
    public data class TypeText(public val sessionId: String, public val text: String) :
        DaemonRequest

    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(public val sessionId: String) : DaemonRequest

    @Serializable @SerialName("shutdown") public data object Shutdown : DaemonRequest
}

@OptIn(ExperimentalSpectreAgentApi::class)
@Serializable
public sealed interface DaemonResponse {
    @Serializable
    public data class Hello(public val daemonVersion: DaemonProtocolVersion) : DaemonResponse

    @Serializable
    @SerialName("attached")
    public data class Attached(public val sessionId: String, public val targetPid: Long) :
        DaemonResponse

    @Serializable
    @SerialName("detached")
    public data class Detached(public val sessionId: String) : DaemonResponse

    @Serializable
    @SerialName("sessions")
    public data class Sessions(public val sessions: List<DaemonSessionSummary>) : DaemonResponse

    @Serializable
    @SerialName("jvmProcesses")
    public data class JvmProcesses(public val processes: List<DaemonJvmProcessSummary>) :
        DaemonResponse

    @Serializable
    @SerialName("windows")
    public data class Windows(
        public val sessionId: String,
        public val windows: List<WindowSummaryDto>,
    ) : DaemonResponse

    @Serializable
    @SerialName("nodes")
    public data class Nodes(public val sessionId: String, public val nodes: List<NodeSnapshotDto>) :
        DaemonResponse

    @Serializable
    @SerialName("completed")
    public data class Completed(public val sessionId: String) : DaemonResponse

    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(public val sessionId: String, public val pngBytes: ByteArray) :
        DaemonResponse {
        override fun equals(other: Any?): Boolean =
            other is Screenshot &&
                sessionId == other.sessionId &&
                pngBytes.contentEquals(other.pngBytes)

        override fun hashCode(): Int = 31 * sessionId.hashCode() + pngBytes.contentHashCode()
    }

    @Serializable @SerialName("shuttingDown") public data object ShuttingDown : DaemonResponse

    @Serializable
    @SerialName("error")
    public data class Error(public val code: DaemonErrorCode, public val message: String) :
        DaemonResponse
}

@Serializable
public data class DaemonSessionSummary(public val sessionId: String, public val targetPid: Long)

/** A JVM process that can be targeted through the local daemon. */
@Serializable
public data class DaemonJvmProcessSummary(public val pid: Long, public val displayName: String)

public enum class DaemonErrorCode {
    SessionNotFound,
    AttachFailed,
    ProtocolError,
    ShutdownInProgress,
    OperationFailed,
}

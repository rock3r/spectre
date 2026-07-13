package dev.sebastiano.spectre.cli.daemon

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** Shared client/daemon wire protocol metadata for Spectre's agent-facing entrypoints. */
@OptIn(ExperimentalSerializationApi::class)
public object DaemonProtocol {
    public val CurrentVersion: DaemonProtocolVersion = DaemonProtocolVersion(major = 1, minor = 1)

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
}

@Serializable public data class DaemonProtocolVersion(public val major: Int, public val minor: Int)

public enum class VersionCompatibility {
    Compatible,
    MajorMismatch,
    DaemonTooOld,
}

@Serializable
public sealed interface DaemonRequest {
    @Serializable
    public data class Hello(public val clientVersion: DaemonProtocolVersion) : DaemonRequest

    @Serializable public data class Attach(public val targetPid: Long) : DaemonRequest

    @Serializable public data class Detach(public val sessionId: String) : DaemonRequest

    @Serializable public data object ListSessions : DaemonRequest

    @Serializable public data object Shutdown : DaemonRequest
}

@Serializable
public sealed interface DaemonResponse {
    @Serializable
    public data class Hello(public val daemonVersion: DaemonProtocolVersion) : DaemonResponse

    @Serializable
    public data class Attached(public val sessionId: String, public val targetPid: Long) :
        DaemonResponse

    @Serializable public data class Detached(public val sessionId: String) : DaemonResponse

    @Serializable
    public data class Sessions(public val sessions: List<DaemonSessionSummary>) : DaemonResponse

    @Serializable public data object ShuttingDown : DaemonResponse

    @Serializable
    public data class Error(public val code: DaemonErrorCode, public val message: String) :
        DaemonResponse
}

@Serializable
public data class DaemonSessionSummary(public val sessionId: String, public val targetPid: Long)

public enum class DaemonErrorCode {
    SessionNotFound,
    AttachFailed,
    ProtocolError,
    ShutdownInProgress,
}

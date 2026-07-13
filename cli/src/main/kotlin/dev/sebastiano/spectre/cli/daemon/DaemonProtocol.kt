package dev.sebastiano.spectre.cli.daemon

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** Shared client/daemon wire protocol metadata for Spectre's agent-facing entrypoints. */
@OptIn(ExperimentalSerializationApi::class)
public object DaemonProtocol {
    public val CurrentVersion: DaemonProtocolVersion = DaemonProtocolVersion(major = 1, minor = 0)

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
}

@Serializable
public sealed interface DaemonResponse {
    @Serializable
    public data class Hello(public val daemonVersion: DaemonProtocolVersion) : DaemonResponse
}

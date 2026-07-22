package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable error taxonomy for the agent transport (#199), shared conceptually with HTTP status
 * mapping in `:server`.
 *
 * Clients use [wireName] (and [fromWire]) to distinguish "runtime too old for this op" from "timed
 * out" from "node not found" without parsing free-text messages.
 */
@ExperimentalSpectreAgentApi
@Serializable
public enum class AgentErrorCategory {
    /** Runtime does not implement this operation (unknown wire discriminator / too-old agent). */
    @SerialName("unsupportedOperation") UnsupportedOperation,

    /** Handshake or framing/schema mismatch between attacher and runtime. */
    @SerialName("protocolMismatch") ProtocolMismatch,

    /** Selector / node key could not be parsed or is not valid for this request. */
    @SerialName("invalidSelector") InvalidSelector,

    /** No matching node for a well-formed key or selector. */
    @SerialName("nodeNotFound") NodeNotFound,

    /** Operation exceeded its deadline. */
    @SerialName("timeout") Timeout,

    /** Input was refused (focus, permissions, Robot backend rejection). */
    @SerialName("inputRejected") InputRejected,

    /** Unexpected failure inside the agent or reflective bridge. */
    @SerialName("internalError") InternalError;

    /** Wire form used in [AgentResponse.Error.category] and HTTP error bodies. */
    public val wireName: String
        get() =
            when (this) {
                UnsupportedOperation -> "unsupportedOperation"
                ProtocolMismatch -> "protocolMismatch"
                InvalidSelector -> "invalidSelector"
                NodeNotFound -> "nodeNotFound"
                Timeout -> "timeout"
                InputRejected -> "inputRejected"
                InternalError -> "internalError"
            }

    public companion object {
        /** Parse a wire category string; unknown values map to [InternalError]. */
        public fun fromWire(value: String?): AgentErrorCategory {
            if (value.isNullOrBlank()) return InternalError
            return entries.firstOrNull { it.wireName == value } ?: InternalError
        }
    }
}

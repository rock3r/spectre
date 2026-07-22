package dev.sebastiano.spectre.agent.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multiplexed request envelope (#200). After the Hello handshake, every client→server frame is an
 * [OpRequest] so multiple ops can share the connection and be cancelled by [opId].
 *
 * [deadlineEpochMs] is an absolute wall-clock deadline (epoch millis). When set, the server aborts
 * the op with category `timeout` once the deadline is reached.
 */
@Serializable
@SerialName("opRequest")
internal data class OpRequest(
    val opId: Long,
    val deadlineEpochMs: Long? = null,
    val body: AgentRequest,
)

/**
 * Partial decode of [OpRequest] that ignores `body`. Used when full decode fails (unknown body
 * discriminator) so the server can still correlate the error to the client's [opId].
 */
@Serializable internal data class OpRequestShell(val opId: Long, val deadlineEpochMs: Long? = null)

/** Multiplexed response envelope correlating to [OpRequest.opId]. */
@Serializable
@SerialName("opResponse")
internal data class OpResponse(val opId: Long, val body: AgentResponse)

@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol between Spectre's attach-side client and the in-target agent runtime.
 *
 * The transport carries length-prefixed CBOR-encoded frames over a Unix Domain Socket (or a paired
 * in-process channel for tests — see `FramingTest`). The format intentionally mirrors (but doesn't
 * share types with) the HTTP transport at `:server`; see plan UC-2 in
 * `.plans/2026-05-22-issue-153-agent-attach-workshop.md`.
 *
 * The v1 operation surface (D-4 in the plan) is exactly the operations the HTTP transport exposes
 * today: windows, allNodes, findByTestTag, click, typeText, screenshot, plus the new detach.
 * Streaming/long-poll ops (`waitForVisualIdle`, idling resources, `withTracing`) are deferred to
 * v1.1 (Q-3 resolution).
 */
@Serializable
internal sealed interface AgentRequest {
    /** Liveness check. Server replies with [AgentResponse.Pong]. */
    @Serializable @SerialName("ping") data object Ping : AgentRequest

    /** Read the current window list. Server replies with [AgentResponse.Windows]. */
    @Serializable @SerialName("windows") data object Windows : AgentRequest

    /**
     * Read the full semantics tree across all known windows. Server replies with
     * [AgentResponse.Nodes].
     */
    @Serializable @SerialName("allNodes") data object AllNodes : AgentRequest

    /**
     * Find nodes by their `testTag` semantic. Server replies with [AgentResponse.Nodes] containing
     * the matches (possibly empty).
     */
    @Serializable
    @SerialName("findByTestTag")
    data class FindByTestTag(val tag: String) : AgentRequest

    /**
     * Synthesize a click on the node identified by [nodeKey] (the canonical
     * `surfaceId:ownerIndex:nodeId` string). Server replies with [AgentResponse.Ok] on success or
     * [AgentResponse.Error] otherwise.
     */
    @Serializable @SerialName("click") data class Click(val nodeKey: String) : AgentRequest

    /**
     * Synthesize a sequence of key events that types [text] into whatever currently holds focus.
     * Server replies with [AgentResponse.Ok] or [AgentResponse.Error].
     */
    @Serializable @SerialName("typeText") data class TypeText(val text: String) : AgentRequest

    /**
     * Capture a PNG of the current target JVM's screen content. Server replies with
     * [AgentResponse.Screenshot] containing the PNG bytes.
     */
    @Serializable @SerialName("screenshot") data object Screenshot : AgentRequest

    /**
     * Signal the agent runtime to drain in-flight requests, stop accepting new ones, release the
     * in-target `ComposeAutomator`, unlink the UDS path, and remove its shutdown hook. Server
     * replies with [AgentResponse.Detached] and then closes the channel.
     */
    @Serializable @SerialName("detach") data object Detach : AgentRequest
}

/** Server-to-client response envelope. */
@Serializable
internal sealed interface AgentResponse {
    /** Reply to [AgentRequest.Ping]. */
    @Serializable @SerialName("pong") data object Pong : AgentResponse

    /** Generic OK signal for void operations (click, typeText). */
    @Serializable @SerialName("ok") data object Ok : AgentResponse

    /** Reply to [AgentRequest.Windows]. */
    @Serializable
    @SerialName("windows")
    data class Windows(val windows: List<WindowSummaryDto>) : AgentResponse

    /** Reply to [AgentRequest.AllNodes] and [AgentRequest.FindByTestTag]. */
    @Serializable
    @SerialName("nodes")
    data class Nodes(val nodes: List<NodeSnapshotDto>) : AgentResponse

    /** Reply to [AgentRequest.Screenshot] — raw PNG bytes (not base64). */
    @Serializable
    @SerialName("screenshot")
    data class Screenshot(val pngBytes: ByteArray) : AgentResponse {
        // ByteArray needs explicit equals/hashCode because Kotlin uses array identity by default
        // for data class auto-generated equals. Tests rely on structural comparison.
        override fun equals(other: Any?): Boolean =
            other is Screenshot && pngBytes.contentEquals(other.pngBytes)

        override fun hashCode(): Int = pngBytes.contentHashCode()
    }

    /** Reply to [AgentRequest.Detach] — sent just before the agent closes the channel. */
    @Serializable @SerialName("detached") data object Detached : AgentResponse

    /**
     * Server-side failure. Carries a human-readable [message]; the structured failure type isn't
     * exposed across the wire (yet) to keep the v1 protocol small.
     */
    @Serializable @SerialName("error") data class Error(val message: String) : AgentResponse
}

/**
 * Compact summary of one tracked window — the only window-level data the agent transport exposes.
 *
 * Carried in the public return type of [dev.sebastiano.spectre.agent.AttachedAutomator.windows], so
 * it stays public but is annotated `@ExperimentalSpectreAgentApi` like the rest of the agent
 * surface.
 */
@ExperimentalSpectreAgentApi
@Serializable
public data class WindowSummaryDto(
    public val index: Int,
    public val surfaceId: String,
    public val title: String?,
    public val isPopup: Boolean,
    public val bounds: RectDto,
)

/**
 * Read-only projection of an `AutomatorNode`. The [key] follows the canonical
 * `surfaceId:ownerIndex:nodeId` form used by Spectre's selector contract.
 */
@ExperimentalSpectreAgentApi
@Serializable
public data class NodeSnapshotDto(
    public val key: String,
    public val testTag: String?,
    public val texts: List<String>,
    public val editableText: String? = null,
    public val role: String?,
    public val contentDescription: String?,
    public val isFocused: Boolean = false,
    public val isVisible: Boolean,
    public val bounds: RectDto,
)

/** Integer screen-space rectangle. */
@ExperimentalSpectreAgentApi
@Serializable
public data class RectDto(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
)

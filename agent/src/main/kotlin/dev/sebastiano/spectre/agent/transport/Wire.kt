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
 * The current operation surface (D-4 in the plan) mirrors the operations the HTTP transport exposes
 * today: windows, allNodes, findByTestTag, click, typeText, screenshot, plus detach.
 * Streaming/long-poll ops (`waitForVisualIdle`, idling resources, `withTracing`) are deferred
 * follow-ups (Q-3 resolution).
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
     * Atomic capture of one window: semantics tree + window PNG taken back-to-back. Server replies
     * with [AgentResponse.Capture] carrying the versioned capture JSON, PNG bytes, and summary
     * counters. Default [windowIndex] is the first tracked window.
     */
    @Serializable @SerialName("capture") data class Capture(val windowIndex: Int = 0) : AgentRequest

    /**
     * Describe native window identity so an out-of-process (daemon) recorder can target capture.
     *
     * When [windowIndex] is null, every tracked window is described. When set, only that index is
     * returned (empty list if out of range). Replies with [AgentResponse.WindowIdentities].
     */
    @Serializable
    @SerialName("windowIdentity")
    data class WindowIdentity(val windowIndex: Int? = null) : AgentRequest

    /**
     * Signal the agent runtime to drain in-flight requests, stop accepting new ones, release the
     * in-target `ComposeAutomator`, unlink the UDS path, and remove its shutdown hook. Server
     * replies with [AgentResponse.Detached] and then closes the channel.
     */
    @Serializable @SerialName("detach") data object Detach : AgentRequest
}

/** Payload-free operation label for diagnostics. Never include caller-controlled request data. */
internal val AgentRequest.logLabel: String
    get() =
        when (this) {
            AgentRequest.Ping -> "ping"
            AgentRequest.Windows -> "windows"
            AgentRequest.AllNodes -> "allNodes"
            is AgentRequest.FindByTestTag -> "findByTestTag"
            is AgentRequest.Click -> "click"
            is AgentRequest.TypeText -> "typeText"
            AgentRequest.Screenshot -> "screenshot"
            is AgentRequest.Capture -> "capture"
            is AgentRequest.WindowIdentity -> "windowIdentity"
            AgentRequest.Detach -> "detach"
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

    /**
     * Reply to [AgentRequest.Capture] — versioned capture JSON UTF-8 bytes, PNG bytes, and summary
     * counters. CLI/MCP front-ends write these to disk and return only the summary + paths to
     * agents.
     */
    @Serializable
    @SerialName("capture")
    data class Capture(
        val windowIndex: Int,
        val schemaVersion: Int,
        val captureJsonUtf8: ByteArray,
        val pngBytes: ByteArray,
        val nodeCount: Int,
        val taggedNodeCount: Int,
        val textedNodeCount: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val captureDurationMs: Long,
    ) : AgentResponse {
        override fun equals(other: Any?): Boolean =
            other is Capture &&
                windowIndex == other.windowIndex &&
                schemaVersion == other.schemaVersion &&
                captureJsonUtf8.contentEquals(other.captureJsonUtf8) &&
                pngBytes.contentEquals(other.pngBytes) &&
                nodeCount == other.nodeCount &&
                taggedNodeCount == other.taggedNodeCount &&
                textedNodeCount == other.textedNodeCount &&
                imageWidth == other.imageWidth &&
                imageHeight == other.imageHeight &&
                captureDurationMs == other.captureDurationMs

        override fun hashCode(): Int {
            var result = windowIndex
            result = 31 * result + schemaVersion
            result = 31 * result + captureJsonUtf8.contentHashCode()
            result = 31 * result + pngBytes.contentHashCode()
            result = 31 * result + nodeCount
            result = 31 * result + taggedNodeCount
            result = 31 * result + textedNodeCount
            result = 31 * result + imageWidth
            result = 31 * result + imageHeight
            result = 31 * result + captureDurationMs.hashCode()
            return result
        }
    }

    /** Reply to [AgentRequest.Detach] — sent just before the agent closes the channel. */
    @Serializable @SerialName("detached") data object Detached : AgentResponse

    /**
     * Reply to [AgentRequest.WindowIdentity] — native handles and geometry for out-of-process
     * recording.
     */
    @Serializable
    @SerialName("windowIdentities")
    data class WindowIdentities(val windows: List<WindowIdentityDto>) : AgentResponse

    /**
     * Server-side failure. Carries a human-readable [message]; the structured failure type isn't
     * exposed across the wire yet to keep the protocol small. This response goes back to the
     * same-UID client that sent the request, so messages may include caller-controlled selectors or
     * node keys for debugging. Persistent diagnostics must use [AgentRequest.logLabel] instead.
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

/**
 * Wire projection of a tracked window's native identity for daemon-side recording.
 *
 * Coordinate spaces (must match core `WindowIdentitySnapshot` KDoc):
 * - [windowBoundsOnScreen] / [surfaceBoundsOnScreen]: AWT user-space screen coordinates
 *   (`locationOnScreen` / Robot / same as [WindowSummaryDto.bounds]). On HiDPI this is logical
 *   space; multiply by [scaleX]/[scaleY] for device pixels.
 * - [surfaceBoundsInWindow]: surface origin/size relative to [windowBoundsOnScreen] top-left in the
 *   same AWT units (crop rect; scale if the backend crops in device pixels).
 * - [scaleX] / [scaleY] / [translateX] / [translateY]: `GraphicsConfiguration.defaultTransform`
 *   affine components. Device-pixel conversion: point `(x, y) → (x * scaleX + translateX, y *
 *   scaleY + translateY)`; scale widths/heights by [scaleX]/[scaleY] only (no translation).
 *
 * When [cropRequired] is true, [nativeHandle] is the host top-level window and capture must crop to
 * the surface rect.
 */
@ExperimentalSpectreAgentApi
@Serializable
public data class WindowIdentityDto(
    public val index: Int,
    public val surfaceId: String,
    public val title: String?,
    public val isPopup: Boolean,
    /** Platform-native window id (HWND / NSWindow* / X11 XID bits), or null if unknown. */
    public val nativeHandle: Long?,
    public val cropRequired: Boolean,
    public val windowBoundsOnScreen: RectDto,
    public val surfaceBoundsOnScreen: RectDto,
    public val surfaceBoundsInWindow: RectDto,
    public val scaleX: Double,
    public val scaleY: Double,
    public val translateX: Double = 0.0,
    public val translateY: Double = 0.0,
)

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
     * Find nodes by text (#202). [exact] true = case-sensitive equality; false = substring,
     * case-insensitive (matches in-process `findByText`).
     */
    @Serializable
    @SerialName("findByText")
    data class FindByText(val text: String, val exact: Boolean = true) : AgentRequest

    /** Find nodes by content description (#202). */
    @Serializable
    @SerialName("findByContentDescription")
    data class FindByContentDescription(val description: String) : AgentRequest

    /**
     * Find nodes by role name (#202). [role] is the `Role` enum name as string (e.g. `Button`).
     * Unknown role names yield [AgentErrorCategory.InvalidSelector].
     */
    @Serializable @SerialName("findByRole") data class FindByRole(val role: String) : AgentRequest

    /**
     * Synthesize a click on the node identified by [nodeKey] (the canonical
     * `surfaceId:ownerIndex:nodeId` string). Server replies with [AgentResponse.Ok] on success or
     * [AgentResponse.Error] otherwise.
     */
    @Serializable @SerialName("click") data class Click(val nodeKey: String) : AgentRequest

    /** Double-click the node identified by [nodeKey] (#203). */
    @Serializable
    @SerialName("doubleClick")
    data class DoubleClick(val nodeKey: String) : AgentRequest

    /**
     * Long-press the node identified by [nodeKey] for [holdForMs] milliseconds (#203). Default
     * matches in-process `longClick` (500 ms).
     */
    @Serializable
    @SerialName("longClick")
    data class LongClick(val nodeKey: String, val holdForMs: Long = 500) : AgentRequest

    /**
     * Drag / swipe (#203). Provide either both [fromNodeKey]/[toNodeKey] (node centres) or all of
     * [startX]/[startY]/[endX]/[endY] (screen coords). Mixing or partial sets is
     * [AgentErrorCategory.InvalidSelector].
     */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val fromNodeKey: String? = null,
        val toNodeKey: String? = null,
        val startX: Int? = null,
        val startY: Int? = null,
        val endX: Int? = null,
        val endY: Int? = null,
        val steps: Int = 12,
        val durationMs: Long = 200,
    ) : AgentRequest

    /**
     * Mouse-wheel scroll at [nodeKey]'s centre (#203). Positive [wheelClicks] scrolls down;
     * negative scrolls up.
     */
    @Serializable
    @SerialName("scrollWheel")
    data class ScrollWheel(val nodeKey: String, val wheelClicks: Int) : AgentRequest

    /**
     * Raw key event with optional AWT modifier mask (#203). [keyCode] is a `KeyEvent.VK_*`
     * constant; [modifiers] is an `InputEvent.*_DOWN_MASK` bitfield (e.g. CTRL_DOWN_MASK).
     */
    @Serializable
    @SerialName("pressKey")
    data class PressKey(val keyCode: Int, val modifiers: Int = 0) : AgentRequest

    /**
     * Synthesize a sequence of key events that types [text] into whatever currently holds focus.
     * Server replies with [AgentResponse.Ok] or [AgentResponse.Error].
     */
    @Serializable @SerialName("typeText") data class TypeText(val text: String) : AgentRequest

    /**
     * Capture a PNG of a tracked window (default) or the full desktop when [fullscreen] is true.
     *
     * Default targets window index 0 of the attached session. Pass [windowIndex] and/or [surfaceId]
     * to select a specific surface from `windows`. Full-desktop capture is opt-in only via
     * [fullscreen]; the server must not silently fall back to the full desktop when window capture
     * fails (#289).
     *
     * Serial name is `screenshot_v2` (not the pre-#289 payload-free `screenshot`) so an older
     * agent-runtime JAR that still maps `screenshot` → full-desktop `screenshot(null)` rejects this
     * request instead of ignoring new fields and silently grabbing the whole desktop.
     */
    @Serializable
    @SerialName("screenshot_v2")
    data class Screenshot(
        val windowIndex: Int? = null,
        val surfaceId: String? = null,
        val fullscreen: Boolean = false,
    ) : AgentRequest

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

    /**
     * First frame after connect (#199). Client announces [protocolVersion]; server replies with
     * [AgentResponse.HelloAck] on exact match (experimental rule) or [AgentResponse.Error] with
     * [AgentErrorCategory.ProtocolMismatch].
     */
    @Serializable
    @SerialName("hello")
    // No default on protocolVersion: WireCodec uses encodeDefaults=false, so a default would
    // omit the field on the wire and peers would fill their own CURRENT — breaking exact-match
    // negotiation across version bumps (#199 / Bugbot).
    data class Hello(val protocolVersion: Int) : AgentRequest

    /**
     * Cancel an in-flight op by [opId] (#200). Server replies with [AgentResponse.Error] category
     * `cancelled` for the target op (and [AgentResponse.Ok] for this cancel frame itself when the
     * cancel was accepted).
     */
    @Serializable @SerialName("cancel") data class Cancel(val opId: Long) : AgentRequest

    /**
     * Wait until a semantics node matches [tag] and/or [text] (AND when both set), same semantics
     * as in-process `ComposeAutomator.waitForNode` (#201).
     *
     * Timeouts are milliseconds; null uses the automator defaults (5s timeout, 100ms poll). Replies
     * with [AgentResponse.Nodes] (single match) or [AgentResponse.Error] (`timeout` /
     * `invalidSelector`).
     */
    @Serializable
    @SerialName("waitForNode")
    data class WaitForNode(
        val tag: String? = null,
        val text: String? = null,
        val timeoutMs: Long? = null,
        val pollIntervalMs: Long? = null,
    ) : AgentRequest

    /**
     * Wait until consecutive visual frames are stable (#201). Same semantics as in-process
     * `ComposeAutomator.waitForVisualIdle`. Null durations use automator defaults. Replies with
     * [AgentResponse.Ok] or [AgentResponse.Error] category `timeout`.
     */
    @Serializable
    @SerialName("waitForVisualIdle")
    data class WaitForVisualIdle(
        val timeoutMs: Long? = null,
        val stableFrames: Int? = null,
        val pollIntervalMs: Long? = null,
    ) : AgentRequest
}

/** Payload-free operation label for diagnostics. Never include caller-controlled request data. */
internal val AgentRequest.logLabel: String
    get() =
        when (this) {
            AgentRequest.Ping -> "ping"
            AgentRequest.Windows -> "windows"
            AgentRequest.AllNodes -> "allNodes"
            is AgentRequest.FindByTestTag -> "findByTestTag"
            is AgentRequest.FindByText -> "findByText"
            is AgentRequest.FindByContentDescription -> "findByContentDescription"
            is AgentRequest.FindByRole -> "findByRole"
            is AgentRequest.Click -> "click"
            is AgentRequest.DoubleClick -> "doubleClick"
            is AgentRequest.LongClick -> "longClick"
            is AgentRequest.Swipe -> "swipe"
            is AgentRequest.ScrollWheel -> "scrollWheel"
            is AgentRequest.PressKey -> "pressKey"
            is AgentRequest.TypeText -> "typeText"
            is AgentRequest.Screenshot -> "screenshot"
            is AgentRequest.Capture -> "capture"
            is AgentRequest.WindowIdentity -> "windowIdentity"
            AgentRequest.Detach -> "detach"
            is AgentRequest.Hello -> "hello"
            is AgentRequest.Cancel -> "cancel"
            is AgentRequest.WaitForNode -> "waitForNode"
            is AgentRequest.WaitForVisualIdle -> "waitForVisualIdle"
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
     * Server-side failure with a stable [category] from the #199 error taxonomy plus a
     * human-readable [message].
     *
     * [category] is the wire name (e.g. `unsupportedOperation`). Defaults to `internalError` so
     * older runtimes that only sent [message] still decode on new clients. This response goes back
     * to the same-UID client that sent the request, so messages may include caller-controlled
     * selectors or node keys for debugging. Persistent diagnostics must use [AgentRequest.logLabel]
     * instead.
     */
    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
        val category: String = AgentErrorCategory.InternalError.wireName,
    ) : AgentResponse

    /**
     * Reply to [AgentRequest.Hello] when versions match (experimental exact-match rule).
     * [protocolVersion] echoes the runtime's [ProtocolVersion.CURRENT].
     */
    @Serializable
    @SerialName("helloAck")
    // No default — see [AgentRequest.Hello] (must be on the wire for exact-match).
    data class HelloAck(val protocolVersion: Int) : AgentResponse
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
 * Read-only projection of an `AutomatorNode` (#202 DTO convergence). The [key] is
 * `surfaceId:ownerIndex:nodeId`.
 *
 * Field set aligns with HTTP `NodeSnapshotDto` where practical: [contentDescriptions], state flags.
 * [contentDescription] is the first description (legacy singular). [bounds] is on-screen integer
 * AWT coords (agent/Robot); HTTP keeps window+screen double rects — intentional divergence.
 */
@ExperimentalSpectreAgentApi
@Serializable
public data class NodeSnapshotDto(
    public val key: String,
    public val testTag: String?,
    public val texts: List<String>,
    public val editableText: String? = null,
    public val role: String?,
    // Required nullable (no default): encodeDefaults=false would omit a defaulted null and break
    // older v2 clients whose decoder still requires contentDescription on every node snapshot.
    public val contentDescription: String?,
    public val contentDescriptions: List<String> = emptyList(),
    public val isFocused: Boolean = false,
    public val isDisabled: Boolean = false,
    public val isSelected: Boolean = false,
    // Required (no default): encodeDefaults=false would omit a defaulted true and break older
    // v2 clients whose NodeSnapshotDto decoder still requires this field on the wire.
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

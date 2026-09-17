package dev.sebastiano.spectre.server.dto

import dev.sebastiano.spectre.server.ExperimentalSpectreHttpApi
import kotlinx.serialization.Serializable

/**
 * Wire shape for a tracked window.
 *
 * Mirrors the in-process `TrackedWindow` minus the live AWT references (which can't cross a JVM
 * boundary). The `index` field matches the position in the in-process `windows` list, so HTTP
 * callers can address a specific window the same way in-process callers do.
 */
@ExperimentalSpectreHttpApi
@Serializable
public data class WindowSummaryDto(
    val index: Int,
    val surfaceId: String,
    val isPopup: Boolean,
    val composeSurfaceBounds: RectangleDto,
)

/**
 * Wire shape for a single semantics node.
 *
 * Captures the set of properties that are useful for cross-JVM queries and assertions: the compound
 * `key` (matches `NodeKey.toString()`), text/content-description/role/state flags, and both
 * window-local and screen bounds. Fields that don't HTTP cleanly (the underlying `SemanticsNode`,
 * the live `TrackedWindow`) are intentionally omitted — callers that need them have to use the
 * in-process automator.
 */
@ExperimentalSpectreHttpApi
@Serializable
public data class NodeSnapshotDto(
    val key: String,
    val testTag: String? = null,
    val texts: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val editableText: String? = null,
    val role: String? = null,
    val isFocused: Boolean = false,
    val isDisabled: Boolean = false,
    val isSelected: Boolean = false,
    val boundsInWindow: RectangleDto,
    val boundsOnScreen: RectangleDto,
)

/**
 * AWT rectangle in screen / window coordinates. Doubles are used for the in-window form to preserve
 * sub-pixel precision; the on-screen form is rounded into ints by callers when feeding Robot APIs.
 */
@ExperimentalSpectreHttpApi
@Serializable
public data class RectangleDto(val x: Double, val y: Double, val width: Double, val height: Double)

/** Request body for `POST /click`. */
@ExperimentalSpectreHttpApi @Serializable public data class ClickRequest(val nodeKey: String)

/** Request body for `POST /doubleClick` (#203). */
@ExperimentalSpectreHttpApi @Serializable public data class DoubleClickRequest(val nodeKey: String)

/** Request body for `POST /longClick` (#203). */
@ExperimentalSpectreHttpApi
@Serializable
public data class LongClickRequest(val nodeKey: String, val holdForMs: Long = 500)

/**
 * Request body for `POST /swipe` (#203). Either node-to-node ([fromNodeKey]/[toNodeKey]) or screen
 * coordinates ([startX]/[startY]/[endX]/[endY]).
 */
@ExperimentalSpectreHttpApi
@Serializable
public data class SwipeRequest(
    val fromNodeKey: String? = null,
    val toNodeKey: String? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val steps: Int = 12,
    val durationMs: Long = 200,
)

/** Request body for `POST /scrollWheel` (#203). */
@ExperimentalSpectreHttpApi
@Serializable
public data class ScrollWheelRequest(val nodeKey: String, val wheelClicks: Int)

/** Request body for `POST /pressKey` (#203). */
@ExperimentalSpectreHttpApi
@Serializable
public data class PressKeyRequest(val keyCode: Int, val modifiers: Int = 0)

/** Request body for `POST /typeText`. */
@ExperimentalSpectreHttpApi @Serializable public data class TypeTextRequest(val text: String)

/** Request body for `POST /clearAndTypeText` (#96). */
@ExperimentalSpectreHttpApi
@Serializable
public data class ClearAndTypeTextRequest(val nodeKey: String, val text: String)

/**
 * Structured text selector matching in-process [dev.sebastiano.spectre.core.TextQuery] (#96).
 *
 * On the wire this is expressed as `GET /nodes` (or `GET /node`) query parameters `text`,
 * `matchType`, and `ignoreCase`. Combining these with the shorthand `exact` flag is
 * `invalidSelector`.
 */
@ExperimentalSpectreHttpApi
@Serializable
public data class TextQueryDto(
    val value: String,
    val matchType: TextMatchTypeDto = TextMatchTypeDto.Exact,
    val ignoreCase: Boolean = false,
)

/** Wire form of [dev.sebastiano.spectre.core.TextMatchType]. */
@ExperimentalSpectreHttpApi
@Serializable
public enum class TextMatchTypeDto {
    Exact,
    Substring,
}

/** Response body for `GET /screenshot` — the captured image as base64-encoded PNG bytes. */
@ExperimentalSpectreHttpApi
@Serializable
public data class ScreenshotResponse(val pngBase64: String, val width: Int, val height: Int)

/** Response body for `GET /node` (`findOneBy*`): a single match or JSON null. */
@ExperimentalSpectreHttpApi @Serializable public data class NodeResponse(val node: NodeSnapshotDto?)

/** Response body for `GET /printTree`. */
@ExperimentalSpectreHttpApi @Serializable public data class PrintTreeResponse(val dump: String)

/** Nested semantics node inside [WindowTreeDto]. */
@ExperimentalSpectreHttpApi
@Serializable
public data class TreeNodeDto(
    val node: NodeSnapshotDto,
    val children: List<TreeNodeDto> = emptyList(),
)

/**
 * Wire shape for one [dev.sebastiano.spectre.core.AutomatorWindow]: window identity plus the nested
 * semantics tree rather than a flat node list.
 */
@ExperimentalSpectreHttpApi
@Serializable
public data class WindowTreeDto(
    val index: Int,
    val surfaceId: String,
    val isPopup: Boolean,
    val composeSurfaceBounds: RectangleDto,
    val roots: List<TreeNodeDto> = emptyList(),
)

/** Response body for `GET /tree`. */
@ExperimentalSpectreHttpApi
@Serializable
public data class TreeResponse(val windows: List<WindowTreeDto>)

/** Response body for `GET /nodes` and similar list endpoints. */
@ExperimentalSpectreHttpApi
@Serializable
public data class NodesResponse(val nodes: List<NodeSnapshotDto>)

/** Response body for `GET /windows`. */
@ExperimentalSpectreHttpApi
@Serializable
public data class WindowsResponse(val windows: List<WindowSummaryDto>)

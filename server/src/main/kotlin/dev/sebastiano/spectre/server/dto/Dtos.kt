package dev.sebastiano.spectre.server.dto

import kotlinx.serialization.Serializable

/**
 * Wire shape for a tracked window.
 *
 * Mirrors the in-process `TrackedWindow` minus the live AWT references (which can't cross a JVM
 * boundary). The `index` field matches the position in the in-process `windows` list, so HTTP
 * callers can address a specific window the same way in-process callers do.
 */
@Serializable
data class WindowSummaryDto(
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
@Serializable
data class NodeSnapshotDto(
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
@Serializable
data class RectangleDto(val x: Double, val y: Double, val width: Double, val height: Double)

/** Request body for `POST /click`. */
@Serializable data class ClickRequest(val nodeKey: String)

/** Request body for `POST /typeText`. */
@Serializable data class TypeTextRequest(val text: String)

/** Response body for `GET /screenshot` — the captured image as base64-encoded PNG bytes. */
@Serializable data class ScreenshotResponse(val pngBase64: String, val width: Int, val height: Int)

/** Response body for `GET /nodes` and similar list endpoints. */
@Serializable data class NodesResponse(val nodes: List<NodeSnapshotDto>)

/** Response body for `GET /windows`. */
@Serializable data class WindowsResponse(val windows: List<WindowSummaryDto>)

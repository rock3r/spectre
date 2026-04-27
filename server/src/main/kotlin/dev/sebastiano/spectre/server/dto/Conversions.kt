package dev.sebastiano.spectre.server.dto

import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.TrackedWindow
import java.awt.Rectangle

internal fun TrackedWindow.toDto(index: Int): WindowSummaryDto =
    WindowSummaryDto(
        index = index,
        surfaceId = surfaceId,
        isPopup = isPopup,
        composeSurfaceBounds = composeSurfaceBoundsOnScreen.toDto(),
    )

internal fun AutomatorNode.toDto(): NodeSnapshotDto {
    // Snapshot live bounds once. AutomatorNode.boundsInWindow is a getter that bounces to the
    // EDT each call, so reading .left, .top, .right, .bottom separately would issue six
    // independent reads — layout could shift between them and produce an internally
    // inconsistent rectangle (e.g. negative width).
    val rect = boundsInWindow
    return NodeSnapshotDto(
        key = key.toString(),
        testTag = testTag,
        texts = texts,
        contentDescriptions = contentDescriptions,
        editableText = editableText,
        role = role?.toString(),
        isFocused = isFocused,
        isDisabled = isDisabled,
        isSelected = isSelected,
        boundsInWindow =
            RectangleDto(
                x = rect.left.toDouble(),
                y = rect.top.toDouble(),
                width = (rect.right - rect.left).toDouble(),
                height = (rect.bottom - rect.top).toDouble(),
            ),
        boundsOnScreen = boundsOnScreen.toDto(),
    )
}

internal fun Rectangle.toDto(): RectangleDto =
    RectangleDto(
        x = x.toDouble(),
        y = y.toDouble(),
        width = width.toDouble(),
        height = height.toDouble(),
    )

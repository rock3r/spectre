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

internal fun AutomatorNode.toDto(): NodeSnapshotDto =
    NodeSnapshotDto(
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
                x = boundsInWindow.left.toDouble(),
                y = boundsInWindow.top.toDouble(),
                width = (boundsInWindow.right - boundsInWindow.left).toDouble(),
                height = (boundsInWindow.bottom - boundsInWindow.top).toDouble(),
            ),
        boundsOnScreen = boundsOnScreen.toDto(),
    )

internal fun Rectangle.toDto(): RectangleDto =
    RectangleDto(
        x = x.toDouble(),
        y = y.toDouble(),
        width = width.toDouble(),
        height = height.toDouble(),
    )

internal fun RectangleDto.toAwt(): Rectangle =
    Rectangle(x.toInt(), y.toInt(), width.toInt(), height.toInt())

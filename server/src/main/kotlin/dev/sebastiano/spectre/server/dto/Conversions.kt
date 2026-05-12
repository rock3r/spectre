@file:OptIn(InternalSpectreApi::class, ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server.dto

import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow
import dev.sebastiano.spectre.server.ExperimentalSpectreHttpApi
import java.awt.Rectangle

internal fun TrackedWindow.toDto(index: Int): WindowSummaryDto =
    WindowSummaryDto(
        index = index,
        surfaceId = surfaceId,
        isPopup = isPopup,
        composeSurfaceBounds = composeSurfaceBoundsOnScreen.toDto(),
    )

internal fun AutomatorNode.toDto(): NodeSnapshotDto {
    // bothBounds() reads in-window and on-screen bounds in a single EDT round-trip so the two
    // rectangles in the DTO are guaranteed to come from the same underlying layout state.
    // Calling the individual `boundsInWindow` / `boundsOnScreen` getters separately would
    // issue independent EDT bounces and could produce a spatially inconsistent snapshot if
    // layout shifted between the two reads.
    val bounds = bothBounds()
    val inWindow = bounds.inWindow
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
                x = inWindow.left.toDouble(),
                y = inWindow.top.toDouble(),
                width = (inWindow.right - inWindow.left).toDouble(),
                height = (inWindow.bottom - inWindow.top).toDouble(),
            ),
        boundsOnScreen = bounds.onScreen.toDto(),
    )
}

internal fun Rectangle.toDto(): RectangleDto =
    RectangleDto(
        x = x.toDouble(),
        y = y.toDouble(),
        width = width.toDouble(),
        height = height.toDouble(),
    )

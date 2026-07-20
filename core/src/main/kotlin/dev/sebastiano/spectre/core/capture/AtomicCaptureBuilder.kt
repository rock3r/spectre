package dev.sebastiano.spectre.core.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow
import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.time.TimeSource

/**
 * Builds an [AtomicCapture] from an already-snapshotted node list, window metadata, and PNG image.
 *
 * Kept separate from [dev.sebastiano.spectre.core.ComposeAutomator] so the pure mapping (screen →
 * image space, summary counts, JSON-ready model) is unit-testable without a live window.
 */
@OptIn(InternalSpectreApi::class)
public object AtomicCaptureBuilder {

    public fun build(
        windowIndex: Int,
        trackedWindow: TrackedWindow,
        nodes: List<AutomatorNode>,
        image: BufferedImage,
        captureRegion: Rectangle,
        densityScaleX: Double,
        densityScaleY: Double,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        capturedAt: Instant = Instant.now(),
    ): AtomicCapture {
        val captureNodes = nodes.map { node ->
            val screenBounds = node.boundsOnScreen
            CaptureNode(
                key = node.key.toString(),
                testTag = node.testTag,
                text = node.text,
                texts = node.texts,
                contentDescription = node.contentDescription,
                role = node.role?.toString(),
                enabled = !node.isDisabled,
                clickable = nodeIsClickable(node),
                focused = node.isFocused,
                selected = node.isSelected,
                boundsImage =
                    screenRectToImageRect(
                        screen = screenBounds,
                        captureOriginX = captureRegion.x,
                        captureOriginY = captureRegion.y,
                        captureAwtWidth = captureRegion.width,
                        captureAwtHeight = captureRegion.height,
                        imageWidth = image.width,
                        imageHeight = image.height,
                    ),
                boundsScreen =
                    CaptureRect(
                        x = screenBounds.x,
                        y = screenBounds.y,
                        width = screenBounds.width,
                        height = screenBounds.height,
                    ),
            )
        }
        val summary =
            CaptureSummary(
                nodeCount = captureNodes.size,
                taggedNodeCount = captureNodes.count { it.testTag != null },
                textedNodeCount = captureNodes.count { it.texts.isNotEmpty() },
                imageWidth = image.width,
                imageHeight = image.height,
                captureDurationMs = startedAt.elapsedNow().inWholeMilliseconds,
            )
        val document =
            CaptureDocument(
                schemaVersion = CaptureDocument.SCHEMA_VERSION,
                capturedAt = capturedAt.toString(),
                window =
                    CaptureWindow(
                        index = windowIndex,
                        surfaceId = trackedWindow.surfaceId,
                        title = (trackedWindow.window as? Frame)?.title,
                        isPopup = trackedWindow.isPopup,
                        boundsScreen =
                            CaptureRect(
                                x = captureRegion.x,
                                y = captureRegion.y,
                                width = captureRegion.width,
                                height = captureRegion.height,
                            ),
                        densityScaleX = densityScaleX,
                        densityScaleY = densityScaleY,
                        imageWidth = image.width,
                        imageHeight = image.height,
                    ),
                nodes = captureNodes,
                summary = summary,
            )
        return AtomicCapture(image = image, pngBytes = encodePng(image), document = document)
    }

    private fun nodeIsClickable(node: AutomatorNode): Boolean =
        node.semanticsNode.config.getOrNull(SemanticsActions.OnClick) != null

    private fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "ImageIO failed to encode capture PNG" }
        return out.toByteArray()
    }
}

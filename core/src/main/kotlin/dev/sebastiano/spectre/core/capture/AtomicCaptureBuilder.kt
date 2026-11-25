package dev.sebastiano.spectre.core.capture

import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.time.TimeSource

/**
 * Eagerly snapshotted node data for [AtomicCaptureBuilder].
 *
 * Geometry and clickability must be frozen **before** the window PNG is taken so the JSON describes
 * the same UI moment as the pixels.
 */
public data class CaptureNodeSnapshot(
    public val key: String,
    public val testTag: String?,
    public val text: String?,
    public val texts: List<String>,
    public val editableText: String?,
    public val contentDescription: String?,
    public val role: String?,
    public val enabled: Boolean,
    public val clickable: Boolean,
    public val focused: Boolean,
    public val selected: Boolean,
    public val boundsScreen: Rectangle,
    public val parentKey: String? = null,
    public val id: String? = null,
    public val platformRole: String? = null,
    public val states: List<String> = emptyList(),
    public val value: String? = null,
) {
    /** Binary/source-compatible schema-v1 constructor. */
    public constructor(
        key: String,
        testTag: String?,
        text: String?,
        texts: List<String>,
        editableText: String?,
        contentDescription: String?,
        role: String?,
        enabled: Boolean,
        clickable: Boolean,
        focused: Boolean,
        selected: Boolean,
        boundsScreen: Rectangle,
    ) : this(
        key,
        testTag,
        text,
        texts,
        editableText,
        contentDescription,
        role,
        enabled,
        clickable,
        focused,
        selected,
        boundsScreen,
        null,
        testTag,
        null,
        emptyList(),
        null,
    )

    /** JVM bridge for callers compiled against the schema-v1 data-class copy function. */
    @JvmName("copy")
    public fun copyV1(
        key: String = this.key,
        testTag: String? = this.testTag,
        text: String? = this.text,
        texts: List<String> = this.texts,
        editableText: String? = this.editableText,
        contentDescription: String? = this.contentDescription,
        role: String? = this.role,
        enabled: Boolean = this.enabled,
        clickable: Boolean = this.clickable,
        focused: Boolean = this.focused,
        selected: Boolean = this.selected,
        boundsScreen: Rectangle = this.boundsScreen,
    ): CaptureNodeSnapshot =
        CaptureNodeSnapshot(
            key,
            testTag,
            text,
            texts,
            editableText,
            contentDescription,
            role,
            enabled,
            clickable,
            focused,
            selected,
            boundsScreen,
            parentKey,
            id,
            platformRole,
            states,
            value,
        )
}

/**
 * Builds an [AtomicCapture] from pre-snapshotted nodes, window metadata, and a PNG image.
 *
 * Kept separate from [dev.sebastiano.spectre.core.ComposeAutomator] so the pure mapping (screen →
 * image space, summary counts, JSON-ready model) is unit-testable without a live window.
 */
@OptIn(InternalSpectreApi::class)
public object AtomicCaptureBuilder {

    public fun build(
        windowIndex: Int,
        trackedWindow: TrackedWindow,
        nodeSnapshots: List<CaptureNodeSnapshot>,
        image: BufferedImage,
        captureRegion: Rectangle,
        densityScaleX: Double,
        densityScaleY: Double,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        capturedAt: Instant = Instant.now(),
    ): AtomicCapture {
        val captureNodes = nodeSnapshots.map { snap ->
            CaptureNode(
                key = snap.key,
                parentKey = snap.parentKey,
                id = snap.id,
                testTag = snap.testTag,
                text = snap.text,
                texts = snap.texts,
                editableText = snap.editableText,
                contentDescription = snap.contentDescription,
                role = snap.role,
                platformRole = snap.platformRole,
                states = snap.states,
                value = snap.value,
                enabled = snap.enabled,
                clickable = snap.clickable,
                focused = snap.focused,
                selected = snap.selected,
                boundsImage =
                    screenRectToImageRect(
                        screen = snap.boundsScreen,
                        captureOriginX = captureRegion.x,
                        captureOriginY = captureRegion.y,
                        captureAwtWidth = captureRegion.width,
                        captureAwtHeight = captureRegion.height,
                        imageWidth = image.width,
                        imageHeight = image.height,
                    ),
                boundsScreen =
                    CaptureRect(
                        x = snap.boundsScreen.x,
                        y = snap.boundsScreen.y,
                        width = snap.boundsScreen.width,
                        height = snap.boundsScreen.height,
                    ),
                onScreen = captureRegion.intersects(snap.boundsScreen),
            )
        }
        val summary =
            CaptureSummary(
                nodeCount = captureNodes.size,
                taggedNodeCount = captureNodes.count { it.testTag != null },
                textedNodeCount =
                    captureNodes.count { it.texts.isNotEmpty() || it.editableText != null },
                imageWidth = image.width,
                imageHeight = image.height,
                captureDurationMs = startedAt.elapsedNow().inWholeMilliseconds,
            )
        val document =
            CaptureDocument(
                schemaVersion = CaptureDocument.SCHEMA_VERSION,
                source = CaptureSource.ComposeSemantics,
                capturedAt = capturedAt.toString(),
                window =
                    CaptureWindow(
                        index = windowIndex,
                        surfaceId = trackedWindow.surfaceId,
                        title = trackedWindow.windowTitle,
                        isPopup = trackedWindow.isPopup,
                        boundsScreen =
                            CaptureRect(
                                x = captureRegion.x,
                                y = captureRegion.y,
                                width = captureRegion.width,
                                height = captureRegion.height,
                            ),
                        boundsSpace = BoundsSpace.Screen,
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

    private fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "ImageIO failed to encode capture PNG" }
        return out.toByteArray()
    }
}

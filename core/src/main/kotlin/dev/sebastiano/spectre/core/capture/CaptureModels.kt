package dev.sebastiano.spectre.core.capture

import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Versioned atomic-capture document written as `capture.json`.
 *
 * Schema version [SCHEMA_VERSION] is stable API: bumps must update golden fixtures, the agent
 * capture skill, and docs together.
 */
public data class CaptureDocument(
    public val schemaVersion: Int,
    public val source: CaptureSource,
    public val capturedAt: String,
    public val window: CaptureWindow,
    public val nodes: List<CaptureNode>,
    public val summary: CaptureSummary,
) {
    /** Binary/source-compatible schema-v1 constructor. New captures still encode as v2. */
    public constructor(
        schemaVersion: Int,
        capturedAt: String,
        window: CaptureWindow,
        nodes: List<CaptureNode>,
        summary: CaptureSummary,
    ) : this(schemaVersion, CaptureSource.ComposeSemantics, capturedAt, window, nodes, summary)

    public companion object {
        public const val SCHEMA_VERSION: Int = 2
    }
}

/** Metadata for the window whose surface was captured into the PNG. */
public data class CaptureWindow(
    public val index: Int,
    public val surfaceId: String,
    public val title: String?,
    public val isPopup: Boolean,
    public val boundsScreen: CaptureRect,
    public val boundsSpace: BoundsSpace,
    public val densityScaleX: Double,
    public val densityScaleY: Double,
    public val imageWidth: Int,
    public val imageHeight: Int,
) {
    /** Binary/source-compatible schema-v1 constructor. */
    public constructor(
        index: Int,
        surfaceId: String,
        title: String?,
        isPopup: Boolean,
        boundsScreen: CaptureRect,
        densityScaleX: Double,
        densityScaleY: Double,
        imageWidth: Int,
        imageHeight: Int,
    ) : this(
        index,
        surfaceId,
        title,
        isPopup,
        boundsScreen,
        BoundsSpace.Screen,
        densityScaleX,
        densityScaleY,
        imageWidth,
        imageHeight,
    )
}

/**
 * One semantics node in the capture tree.
 *
 * [key] is the owner-scoped node key accepted by `click(nodeKey)` and friends. [boundsImage] is
 * primary (pixels of the capture PNG); [boundsScreen] is secondary (input targeting).
 */
public data class CaptureNode(
    public val key: String,
    public val parentKey: String?,
    public val id: String?,
    public val testTag: String?,
    public val text: String?,
    public val texts: List<String>,
    public val editableText: String?,
    public val contentDescription: String?,
    public val role: String?,
    public val platformRole: String?,
    public val states: List<String>,
    public val value: String?,
    public val enabled: Boolean,
    public val clickable: Boolean,
    public val focused: Boolean,
    public val selected: Boolean,
    public val boundsImage: CaptureRect,
    public val boundsScreen: CaptureRect,
    public val onScreen: Boolean = true,
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
        boundsImage: CaptureRect,
        boundsScreen: CaptureRect,
    ) : this(
        key,
        null,
        testTag,
        testTag,
        text,
        texts,
        editableText,
        contentDescription,
        role,
        null,
        emptyList(),
        null,
        enabled,
        clickable,
        focused,
        selected,
        boundsImage,
        boundsScreen,
        true,
    )
}

public enum class CaptureSource(public val wireName: String) {
    ComposeSemantics("compose-semantics"),
    OsAccessibility("os-accessibility"),
}

public enum class BoundsSpace(public val wireName: String) {
    Screen("screen"),
    Window("window"),
}

/** Decision-grade summary safe to return inline without reading the full tree. */
public data class CaptureSummary(
    public val nodeCount: Int,
    public val taggedNodeCount: Int,
    public val textedNodeCount: Int,
    public val imageWidth: Int,
    public val imageHeight: Int,
    public val captureDurationMs: Long,
)

/** Integer rectangle used in both image-pixel and screen-pixel spaces. */
public data class CaptureRect(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
)

/**
 * In-memory result of [dev.sebastiano.spectre.core.ComposeAutomator.capture]: PNG pixels plus the
 * versioned document/summary. Callers that want files on disk use [CaptureArtifactsWriter].
 *
 * [captureJson] is the UTF-8 form of [document] (same bytes [CaptureArtifactsWriter] would write),
 * pre-encoded so reflective agent handlers do not need to load [CaptureJson].
 */
public data class AtomicCapture(
    public val image: BufferedImage,
    public val pngBytes: ByteArray,
    public val document: CaptureDocument,
    public val captureJson: String = CaptureJson.encode(document),
) {
    public val summary: CaptureSummary
        get() = document.summary

    override fun equals(other: Any?): Boolean =
        other is AtomicCapture &&
            image === other.image &&
            pngBytes.contentEquals(other.pngBytes) &&
            document == other.document &&
            captureJson == other.captureJson

    override fun hashCode(): Int {
        var result = image.hashCode()
        result = 31 * result + pngBytes.contentHashCode()
        result = 31 * result + document.hashCode()
        result = 31 * result + captureJson.hashCode()
        return result
    }
}

/** Paths written by [CaptureArtifactsWriter] for one capture directory. */
public data class CaptureArtifactPaths(
    public val directory: Path,
    public val captureJsonPath: Path,
    public val screenshotPngPath: Path,
)

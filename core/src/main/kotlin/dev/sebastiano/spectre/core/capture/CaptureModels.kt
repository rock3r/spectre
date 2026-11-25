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
    public val capturedAt: String,
    public val window: CaptureWindow,
    public val nodes: List<CaptureNode>,
    public val summary: CaptureSummary,
    public val source: CaptureSource = CaptureSource.ComposeSemantics,
) {
    /** Binary/source-compatible schema-v1 constructor. New captures still encode as v2. */
    public constructor(
        schemaVersion: Int,
        capturedAt: String,
        window: CaptureWindow,
        nodes: List<CaptureNode>,
        summary: CaptureSummary,
    ) : this(schemaVersion, capturedAt, window, nodes, summary, CaptureSource.ComposeSemantics)

    /** JVM bridge for callers compiled against the schema-v1 data-class copy function. */
    @JvmName("copy")
    public fun copyV1(
        schemaVersion: Int = this.schemaVersion,
        capturedAt: String = this.capturedAt,
        window: CaptureWindow = this.window,
        nodes: List<CaptureNode> = this.nodes,
        summary: CaptureSummary = this.summary,
    ): CaptureDocument = CaptureDocument(schemaVersion, capturedAt, window, nodes, summary, source)

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
    public val densityScaleX: Double,
    public val densityScaleY: Double,
    public val imageWidth: Int,
    public val imageHeight: Int,
    public val boundsSpace: BoundsSpace = BoundsSpace.Screen,
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
        densityScaleX,
        densityScaleY,
        imageWidth,
        imageHeight,
        BoundsSpace.Screen,
    )

    /** JVM bridge for callers compiled against the schema-v1 data-class copy function. */
    @JvmName("copy")
    public fun copyV1(
        index: Int = this.index,
        surfaceId: String = this.surfaceId,
        title: String? = this.title,
        isPopup: Boolean = this.isPopup,
        boundsScreen: CaptureRect = this.boundsScreen,
        densityScaleX: Double = this.densityScaleX,
        densityScaleY: Double = this.densityScaleY,
        imageWidth: Int = this.imageWidth,
        imageHeight: Int = this.imageHeight,
    ): CaptureWindow =
        CaptureWindow(
            index,
            surfaceId,
            title,
            isPopup,
            boundsScreen,
            densityScaleX,
            densityScaleY,
            imageWidth,
            imageHeight,
            boundsSpace,
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
    public val boundsImage: CaptureRect,
    public val boundsScreen: CaptureRect,
    public val parentKey: String? = null,
    public val id: String? = null,
    public val platformRole: String? = null,
    public val states: List<String> = emptyList(),
    public val value: String? = null,
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
        boundsImage,
        boundsScreen,
        null,
        testTag,
        null,
        emptyList(),
        null,
        true,
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
        boundsImage: CaptureRect = this.boundsImage,
        boundsScreen: CaptureRect = this.boundsScreen,
    ): CaptureNode =
        CaptureNode(
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
            boundsImage,
            boundsScreen,
            parentKey,
            id,
            platformRole,
            states,
            value,
            onScreen,
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

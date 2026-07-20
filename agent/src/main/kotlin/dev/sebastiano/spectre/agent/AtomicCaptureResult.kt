package dev.sebastiano.spectre.agent

/**
 * Client-side view of an atomic capture returned by [AttachedAutomator.capture].
 *
 * [captureJson] is the full versioned tree document; [pngBytes] is the window PNG. Prefer writing
 * both to disk and keeping only the summary counters in agent context.
 */
@ExperimentalSpectreAgentApi
public data class AtomicCaptureResult(
    public val windowIndex: Int,
    public val schemaVersion: Int,
    public val captureJson: String,
    public val pngBytes: ByteArray,
    public val nodeCount: Int,
    public val taggedNodeCount: Int,
    public val textedNodeCount: Int,
    public val imageWidth: Int,
    public val imageHeight: Int,
    public val captureDurationMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is AtomicCaptureResult &&
            windowIndex == other.windowIndex &&
            schemaVersion == other.schemaVersion &&
            captureJson == other.captureJson &&
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
        result = 31 * result + captureJson.hashCode()
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

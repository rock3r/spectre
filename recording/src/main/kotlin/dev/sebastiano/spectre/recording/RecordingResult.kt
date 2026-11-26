package dev.sebastiano.spectre.recording

import java.nio.file.Path

/**
 * Finalized recording and backend observations. Null means the backend did not report the value.
 */
public data class RecordingResult(
    public val output: Path,
    public val droppedFrames: Long? = null,
    public val cropFixedAtStart: Boolean? = null,
)

/**
 * Stops this handle and returns its diagnostics. Existing handle implementations report only the
 * path.
 */
public fun RecordingHandle.stopWithResult(): RecordingResult {
    stop()
    return (this as? RecordingResultProvider)?.recordingResult ?: RecordingResult(output)
}

internal interface RecordingResultProvider {
    val recordingResult: RecordingResult
}

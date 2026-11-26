package dev.sebastiano.spectre.recording

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal fun validateRecordingOutput(
    output: Path,
    reportedSizeBytes: Long? = null,
    previous: RecordingFileStamp? = null,
) {
    check(Files.isRegularFile(output)) {
        "Recording stopped without creating its output file at $output."
    }
    val actualSize = Files.size(output)
    check(actualSize > 0) { "Recording output at $output is empty after stop()." }
    check(previous == null || recordingFileStamp(output) != previous) {
        "Recording helper left a pre-existing output unchanged at $output."
    }
    if (reportedSizeBytes != null) {
        check(reportedSizeBytes > 0) {
            "Recording helper reported an empty or missing output at $output " +
                "(reported size: $reportedSizeBytes bytes; actual size: $actualSize bytes)."
        }
        check(actualSize == reportedSizeBytes) {
            "Recording helper reported $reportedSizeBytes bytes for $output, but found $actualSize."
        }
    }
}

/** Capture before spawning the writer so a successful exit cannot claim an old movie. */
internal data class RecordingFileStamp(val key: Any?, val modified: FileTime, val size: Long)

internal fun recordingFileStamp(output: Path): RecordingFileStamp? =
    try {
        val attrs = Files.readAttributes(output, BasicFileAttributes::class.java)
        RecordingFileStamp(attrs.fileKey(), attrs.lastModifiedTime(), attrs.size())
    } catch (_: NoSuchFileException) {
        null
    }

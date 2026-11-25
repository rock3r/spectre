package dev.sebastiano.spectre.recording

import java.nio.file.Files
import java.nio.file.Path

internal fun validateRecordingOutput(output: Path, reportedSizeBytes: Long? = null) {
    check(Files.isRegularFile(output)) {
        "Recording stopped without creating its output file at $output."
    }
    val actualSize = Files.size(output)
    check(actualSize > 0) { "Recording output at $output is empty after stop()." }
    if (reportedSizeBytes != null) {
        check(reportedSizeBytes > 0) {
            "Recording helper reported an empty or missing output at $output " +
                "(reported size: $reportedSizeBytes bytes; actual size: $actualSize bytes)."
        }
    }
}

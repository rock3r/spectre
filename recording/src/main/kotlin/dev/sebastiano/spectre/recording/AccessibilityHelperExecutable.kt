package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.screencapturekit.HelperBinaryExtractor
import dev.sebastiano.spectre.recording.windows.WindowsGraphicsCaptureHelperBinaryExtractor
import java.nio.file.Path

/** Resolves the packaged native helper used for daemon-owned accessibility snapshots. */
public object AccessibilityHelperExecutable {
    /** Extracts and returns the helper executable for the current platform. */
    public fun resolve(): Path =
        when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
                HelperBinaryExtractor().extract()
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
                WindowsGraphicsCaptureHelperBinaryExtractor().extract()
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
                WaylandHelperBinaryExtractor().extract()
            else ->
                throw UnsupportedOperationException(
                    "Accessibility snapshots are not supported on ${System.getProperty("os.name")}"
                )
        }
}

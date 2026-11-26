package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.DefaultWaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.screencapturekit.HelperBinaryExtractor
import dev.sebastiano.spectre.recording.windows.DefaultWindowsGraphicsCaptureHelperBinaryExtractor
import java.nio.file.Path

/** Resolves the packaged native helper which also implements the accessibility protocol. */
public fun locateAccessibilityHelper(): Path =
    when {
        System.getProperty("os.name").startsWith("Mac") -> HelperBinaryExtractor().extract()
        System.getProperty("os.name").startsWith("Windows") ->
            DefaultWindowsGraphicsCaptureHelperBinaryExtractor.instance.extract()
        System.getProperty("os.name").startsWith("Linux") ->
            DefaultWaylandHelperBinaryExtractor.instance.extract()
        else -> error("Accessibility snapshots are unsupported on ${System.getProperty("os.name")}")
    }

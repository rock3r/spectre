package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

internal fun assumeLiveAwtAvailable() {
    if (detectMacOs()) {
        assumeTrue(
            System.getProperty(LIVE_AWT_OPT_IN_PROPERTY).toBoolean(),
            "Live AWT tests are opt-in on macOS because AppKit initialisation can hang in " +
                "non-interactive workers; rerun with -D$LIVE_AWT_OPT_IN_PROPERTY=true to exercise them.",
        )
    }
    assumeFalse(GraphicsEnvironment.isHeadless(), "Live AWT tests need a non-headless JVM")
}

private const val LIVE_AWT_OPT_IN_PROPERTY = "spectre.test.liveAwt"

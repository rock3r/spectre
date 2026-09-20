@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCaptureHelperUnusableTest {

    @Test
    fun `native capture helper-unusable recognises Linux gst-launch failures`() {
        assertTrue(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "Linux screenshot helper failed",
                    IOException(
                        "Cannot run program \"gst-launch-1.0\": error=2, No such file or directory"
                    ),
                )
            )
        )
        assertTrue(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "spectre-wayland-helper reported an error during screenshot capture: " +
                        "kind=CaptureFailed message=spawning gst-launch from argv: " +
                        "[\"gst-launch-1.0\", \"ximagesrc\"]"
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "Linux screenshot helper failed",
                    IOException("Cannot run program \"gst-launch-1.0\""),
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "Linux screenshot helper failed",
                    IOException(
                        "Cannot run program \"gst-launch-1.0\": error=13, Permission denied"
                    ),
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "Linux screenshot helper failed",
                    IOException("error=24, Too many open files"),
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "Linux screenshot helper failed",
                    IOException("simulated EPIPE"),
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "spectre-wayland-helper reported an error during screenshot capture: " +
                        "kind=CaptureFailed message=gst-launch did not exit within 8s " +
                        "while capturing screenshot"
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException(
                    "spectre-wayland-helper reported an error during screenshot capture: " +
                        "kind=CaptureFailed message=gst-launch screenshot pipeline " +
                        "exited with status ExitStatus(code: 1)"
                )
            )
        )
        assertFalse(
            isNativeCaptureHelperUnusable(
                IllegalStateException("duplicate window title 'same title'")
            )
        )
        assertFalse(isNativeCaptureHelperUnusable(UnsupportedOperationException("not a Frame")))
    }
}

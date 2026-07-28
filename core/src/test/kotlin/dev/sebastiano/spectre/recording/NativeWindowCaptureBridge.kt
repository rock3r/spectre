package dev.sebastiano.spectre.recording

import java.awt.Frame
import java.awt.image.BufferedImage

/** Test-only stand-in proving core discovers the optional bridge reflectively. */
public object NativeWindowCaptureBridge {
    @JvmStatic
    public fun captureWindow(frame: Frame): BufferedImage =
        BufferedImage(
            frame.width.coerceAtLeast(1),
            frame.height.coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
}

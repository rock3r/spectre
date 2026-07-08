package dev.sebastiano.spectre.core

import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.util.Locale
import java.util.concurrent.TimeUnit
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

/**
 * Skips (rather than fails) the calling test when the current desktop session can't present a
 * freshly rendered window into the framebuffer that `java.awt.Robot.createScreenCapture` reads.
 *
 * [assumeLiveAwtAvailable] only proves the JVM is non-headless; it can't distinguish a genuinely
 * interactive, frontmost desktop from a locked console, an RDP/VNC session with no on-screen
 * framebuffer, or an occluded/minimised worker. In those sessions a window can be shown and laid
 * out yet never composited onto the captured pixels, so an on-screen pixel assertion samples the
 * desktop background instead of the window — the exact symptom that made the synthetic-screenshot
 * parity test fail on a non-interactive Windows box (capture returned the desktop's dark-grey
 * background rather than the panel's red).
 *
 * The caller shows a solid-[expected]-colour [region] on screen first, then hands its screen bounds
 * here. This probes with a *raw* [Robot] — independent of the code under test — polling until the
 * region's centre pixel matches [expected] (exact, masked to RGB, so the probe never passes where
 * the driver's own exact-match assertion would then fail) or the budget elapses. A match proves the
 * session presents windows for capture and the caller proceeds. Because the probe is independent of
 * the driver, a genuine capture-path regression (the raw probe sees the colour but the driver's
 * screenshot does not) still fails the caller's assertion instead of being masked as a skip.
 */
internal fun assumeOnScreenCaptureReflects(
    region: Rectangle,
    expected: Color,
    timeoutMillis: Long = CAPTURE_PROBE_TIMEOUT_MS,
    pollMillis: Long = CAPTURE_PROBE_POLL_MS,
) {
    val robot = Robot()
    val expectedRgb = expected.rgb and 0x00FFFFFF
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    var lastRgb = robot.captureCenterRgb(region)
    while (lastRgb != expectedRgb && System.nanoTime() < deadline) {
        Thread.sleep(pollMillis)
        lastRgb = robot.captureCenterRgb(region)
    }
    assumeTrue(
        lastRgb == expectedRgb,
        "On-screen framebuffer capture did not reflect a freshly shown window in this session " +
            "(sampled #${"%06X".format(Locale.ROOT, lastRgb)} at the region centre, expected " +
            "#${"%06X".format(Locale.ROOT, expectedRgb)}). This is expected on a locked console, " +
            "an RDP/VNC session with no on-screen framebuffer, or an occluded worker; the live " +
            "on-screen capture assertion is skipped there.",
    )
}

/**
 * Captures [region] and returns its centre pixel's RGB (alpha masked off). Samples the returned
 * image's own centre rather than deriving coordinates from [region]: `createScreenCapture` sizes
 * the image to the requested logical rectangle even under DPI scaling, so the two coincide today,
 * but keying off the actual image keeps this correct if that ever changes.
 */
private fun Robot.captureCenterRgb(region: Rectangle): Int {
    val image = createScreenCapture(region)
    return image.getRGB(image.width / 2, image.height / 2) and 0x00FFFFFF
}

private const val LIVE_AWT_OPT_IN_PROPERTY = "spectre.test.liveAwt"
private const val CAPTURE_PROBE_TIMEOUT_MS = 5_000L
private const val CAPTURE_PROBE_POLL_MS = 25L

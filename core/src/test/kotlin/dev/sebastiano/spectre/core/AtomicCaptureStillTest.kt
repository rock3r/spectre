package dev.sebastiano.spectre.core

import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #559: atomic [ComposeAutomator.capture] must never substitute a `java.awt.Robot` desktop crop for
 * a window still. It either takes a native window-scoped still or fails with an actionable reason.
 */
class AtomicCaptureStillTest {

    private val noRecordingClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader()

    @Test
    fun `no reason is reported when the native bridge is present`() {
        assertNull(
            atomicCaptureNativeStillUnavailableReason(
                allowsPlatformCapture = true,
                osName = "Mac OS X",
                getenv = { null },
            )
        )
    }

    @Test
    fun `missing spectre-recording fails with an actionable reason instead of a desktop crop`() {
        val error =
            assertFailsWith<UnsupportedOperationException> {
                requireAtomicCaptureNativeStill(
                    classLoader = noRecordingClassLoader,
                    allowsPlatformCapture = true,
                    osName = "Mac OS X",
                    getenv = { null },
                )
            }
        val message = error.message.orEmpty()
        assertTrue(message.contains("spectre-recording"), message)
        assertTrue(message.contains("spectre capture"), message)
        assertTrue(message.contains("desktop crop"), message)
    }

    @Test
    fun `a headless robot driver fails instead of cropping the desktop`() {
        val error =
            assertFailsWith<UnsupportedOperationException> {
                requireAtomicCaptureNativeStill(
                    allowsPlatformCapture = false,
                    osName = "Mac OS X",
                    getenv = { null },
                )
            }
        assertTrue(error.message.orEmpty().contains("RobotDriver.headless()"), error.message)
    }

    @Test
    fun `GitHub-hosted Windows fails instead of cropping the desktop`() {
        val error =
            assertFailsWith<UnsupportedOperationException> {
                requireAtomicCaptureNativeStill(
                    allowsPlatformCapture = true,
                    osName = "Windows 11",
                    getenv = { if (it == "RUNNER_ENVIRONMENT") "github-hosted" else null },
                )
            }
        assertTrue(error.message.orEmpty().contains("GitHub-hosted"), error.message)
    }

    @Test
    fun `an external window still is cropped to the Compose surface at device scale`() {
        val windowBounds = Rectangle(100, 200, 400, 300)
        // A 2x native still of the whole window, including a 28dp title bar.
        val still = BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB)
        val surface = Rectangle(100, 228, 400, 272)

        val capture =
            externalWindowStill(
                image = still,
                stillWindowBounds = windowBounds,
                liveWindowBounds = Rectangle(windowBounds),
                frameInsets = Insets(28, 0, 0, 0),
                captureRegion = surface,
                osName = "Mac OS X",
                isWayland = false,
            )

        assertEquals(surface, capture.boundsOnScreen)
        assertEquals(800, capture.image.width)
        assertEquals(544, capture.image.height)
    }

    @Test
    fun `an external window still is rejected when the window moved after it was taken`() {
        val error =
            assertFailsWith<IllegalStateException> {
                externalWindowStill(
                    image = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 400, 300),
                    liveWindowBounds = Rectangle(10, 0, 400, 300),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(10, 0, 400, 300),
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("moved or resized"), error.message)
    }
}

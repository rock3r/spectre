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
                titleCollisions = emptyList(),
                expectedSurfaceId = "s",
                expectedTitle = "App",
                liveTitle = "App",
                liveSurfaceId = "s",
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
                    titleCollisions = emptyList(),
                    expectedSurfaceId = "s",
                    expectedTitle = "App",
                    liveTitle = "App",
                    liveSurfaceId = "s",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("moved or resized"), error.message)
    }

    @Test
    fun `an external window still is rejected when another window could have been captured`() {
        val error =
            assertFailsWith<IllegalStateException> {
                externalWindowStill(
                    image = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 400, 300),
                    liveWindowBounds = Rectangle(0, 0, 400, 300),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(0, 0, 400, 300),
                    titleCollisions = listOf("App Settings"),
                    expectedSurfaceId = "s",
                    expectedTitle = "App",
                    liveTitle = "App",
                    liveSurfaceId = "s",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("App Settings"), error.message)
    }

    @Test
    fun `an external window still is rejected when the index now resolves to another window`() {
        val error =
            assertFailsWith<IllegalStateException> {
                externalWindowStill(
                    image = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 400, 300),
                    liveWindowBounds = Rectangle(0, 0, 400, 300),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(0, 0, 400, 300),
                    titleCollisions = emptyList(),
                    expectedTitle = "App",
                    liveTitle = "App",
                    expectedSurfaceId = "window-a",
                    liveSurfaceId = "window-b",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("window-a"), error.message)
    }

    @Test
    fun `an external window still is rejected when the window title changed after it was taken`() {
        val error =
            assertFailsWith<IllegalStateException> {
                externalWindowStill(
                    image = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 400, 300),
                    liveWindowBounds = Rectangle(0, 0, 400, 300),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(0, 0, 400, 300),
                    titleCollisions = emptyList(),
                    expectedTitle = "App",
                    liveTitle = "Document",
                    expectedSurfaceId = "s",
                    liveSurfaceId = "s",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("Document"), error.message)
    }

    @Test
    fun `an external untitled window still is cropped to the Compose surface`() {
        val windowBounds = Rectangle(100, 200, 240, 160)
        val still = BufferedImage(480, 320, BufferedImage.TYPE_INT_ARGB)

        val capture =
            externalUntitledWindowStill(
                image = still,
                stillWindowBounds = windowBounds,
                liveWindowBounds = Rectangle(windowBounds),
                frameInsets = Insets(0, 0, 0, 0),
                captureRegion = Rectangle(windowBounds),
                liveTitle = null,
                expectedSurfaceId = "popup",
                liveSurfaceId = "popup",
                osName = "Mac OS X",
                isWayland = false,
            )

        assertEquals(windowBounds, capture.boundsOnScreen)
        assertEquals(480, capture.image.width)
        assertEquals(320, capture.image.height)
    }

    @Test
    fun `an external untitled window still is rejected when the window moved after it was taken`() {
        // The daemon found the window by its frame, so a moved window may mean another window
        // now sits at the frame the still shows.
        val error =
            assertFailsWith<IllegalStateException> {
                externalUntitledWindowStill(
                    image = BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 240, 160),
                    liveWindowBounds = Rectangle(0, 10, 240, 160),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(0, 10, 240, 160),
                    liveTitle = null,
                    expectedSurfaceId = "popup",
                    liveSurfaceId = "popup",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("moved or resized"), error.message)
    }

    @Test
    fun `an external untitled window still is rejected when the index now resolves elsewhere`() {
        val error =
            assertFailsWith<IllegalStateException> {
                externalUntitledWindowStill(
                    image = BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 240, 160),
                    liveWindowBounds = Rectangle(0, 0, 240, 160),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(0, 0, 240, 160),
                    liveTitle = null,
                    expectedSurfaceId = "popup-a",
                    liveSurfaceId = "popup-b",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("popup-a"), error.message)
    }

    @Test
    fun `an external untitled window still is rejected for a titled window`() {
        // A Frame or Dialog is found by its title; a still found by frame must not be paired
        // with it.
        val error =
            assertFailsWith<IllegalStateException> {
                externalUntitledWindowStill(
                    image = BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB),
                    stillWindowBounds = Rectangle(0, 0, 240, 160),
                    liveWindowBounds = Rectangle(0, 0, 240, 160),
                    frameInsets = Insets(0, 0, 0, 0),
                    captureRegion = Rectangle(0, 0, 240, 160),
                    liveTitle = "App",
                    expectedSurfaceId = "s",
                    liveSurfaceId = "s",
                    osName = "Mac OS X",
                    isWayland = false,
                )
            }
        assertTrue(error.message.orEmpty().contains("title"), error.message)
    }

    @Test
    fun `macOS title collisions use the helper's title-contains match`() {
        assertEquals(
            listOf("App Settings"),
            remoteCaptureTitleCollisions(
                targetTitle = "App",
                others =
                    listOf(
                        CaptureTitleCandidate("App Settings", isShowing = true),
                        CaptureTitleCandidate("Other", isShowing = true),
                        CaptureTitleCandidate(null, isShowing = true),
                    ),
                osName = "Mac OS X",
            ),
        )
    }

    @Test
    fun `macOS title collisions ignore hidden windows the helper cannot select`() {
        assertEquals(
            emptyList(),
            remoteCaptureTitleCollisions(
                targetTitle = "App",
                others = listOf(CaptureTitleCandidate("App Settings", isShowing = false)),
                osName = "Mac OS X",
            ),
        )
    }

    @Test
    fun `Windows title collisions use the helper's exact title match`() {
        assertEquals(
            listOf("App"),
            remoteCaptureTitleCollisions(
                targetTitle = "App",
                others =
                    listOf(
                        CaptureTitleCandidate("App Settings", isShowing = true),
                        // The Windows helper enumerates hidden windows too.
                        CaptureTitleCandidate("App", isShowing = false),
                    ),
                osName = "Windows 11",
            ),
        )
    }
}

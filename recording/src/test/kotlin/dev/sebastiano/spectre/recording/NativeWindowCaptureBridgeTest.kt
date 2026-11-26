package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import java.awt.Color
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

class NativeWindowCaptureBridgeTest {
    @Test
    fun `vertical mixed-scale displays are composed in desktop coordinates`() {
        val displays =
            listOf(
                RegionDisplay(0, Rectangle(0, 0, 100, 100), 2.0, 2.0),
                RegionDisplay(1, Rectangle(0, 100, 100, 100), 1.0, 1.0),
            )
        val calls = mutableListOf<Pair<Rectangle, Int>>()

        val image =
            composeScreenRegion(Rectangle(10, 90, 20, 20), displays, 1.0, 1.0) { local, index ->
                calls += Rectangle(local) to index
                BufferedImage(
                        local.width * displays[index].scaleX.toInt(),
                        local.height * displays[index].scaleY.toInt(),
                        BufferedImage.TYPE_INT_ARGB,
                    )
                    .apply {
                        val graphics = createGraphics()
                        try {
                            graphics.color = if (index == 0) Color.RED else Color.BLUE
                            graphics.fillRect(0, 0, width, height)
                        } finally {
                            graphics.dispose()
                        }
                    }
            }

        assertEquals(listOf(Rectangle(10, 90, 20, 10) to 0, Rectangle(10, 0, 20, 10) to 1), calls)
        assertEquals(20, image.width)
        assertEquals(20, image.height)
        assertEquals(Color.RED.rgb, image.getRGB(5, 5))
        assertEquals(Color.BLUE.rgb, image.getRGB(5, 15))
    }

    @Test
    fun `display gaps fail with requested and uncovered rectangles`() {
        val displays =
            listOf(
                RegionDisplay(0, Rectangle(0, 0, 100, 90), 1.0, 1.0),
                RegionDisplay(1, Rectangle(0, 100, 100, 100), 1.0, 1.0),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                composeScreenRegion(Rectangle(10, 80, 20, 30), displays, 1.0, 1.0) { _, _ ->
                    error("capture must not start before coverage validation")
                }
            }

        assertTrue(failure.message.orEmpty().contains("x=10,y=80,width=20,height=30"))
        assertTrue(failure.message.orEmpty().contains("x=10,y=90,width=20,height=10"))
    }

    @Test
    fun `wrong native dimensions fail before pixels are scaled`() {
        val display = RegionDisplay(4, Rectangle(0, 0, 100, 100), 2.0, 2.0)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                composeScreenRegion(Rectangle(10, 10, 20, 10), listOf(display), 1.0, 1.0) { _, _ ->
                    BufferedImage(39, 20, BufferedImage.TYPE_INT_ARGB)
                }
            }

        assertTrue(failure.message.orEmpty().contains("39x20"))
        assertTrue(failure.message.orEmpty().contains("expected 40x20"))
    }

    @Test
    fun `mirrored displays do not shift native display indices`() {
        val displays =
            uniqueRegionDisplays(
                listOf(
                    RegionDisplay(0, Rectangle(0, 0, 20, 20), 1.0, 1.0),
                    RegionDisplay(0, Rectangle(0, 0, 20, 20), 1.0, 1.0),
                    RegionDisplay(0, Rectangle(20, 0, 20, 20), 1.0, 1.0),
                )
            )
        var capturedIndex = -1

        composeScreenRegion(Rectangle(20, 0, 20, 20), displays, 1.0, 1.0) { local, index ->
            capturedIndex = index
            BufferedImage(local.width, local.height, BufferedImage.TYPE_INT_ARGB)
        }

        assertEquals(2, displays.size)
        assertEquals(1, capturedIndex)
    }

    @Test
    fun `capture lock does not use the frame monitor`() {
        assumeLiveAwtAvailable()
        val frame = Frame()
        val captureLock = NativeWindowCaptureBridge.captureLockFor(frame)
        val enteredCaptureLock = CountDownLatch(1)

        try {
            synchronized(frame) {
                Thread { synchronized(captureLock) { enteredCaptureLock.countDown() } }.start()

                assertTrue(
                    enteredCaptureLock.await(5, TimeUnit.SECONDS),
                    "the capture lock must not wait for AWT's Frame monitor",
                )
            }
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `untitled JWindow host is captured by its frame`() {
        assumeLiveAwtAvailable()
        val owner = Frame("bridge owner")
        val popupBounds = Rectangle(40, 50, 300, 200)
        val popup = JWindow(owner).apply { bounds = popupBounds }
        popup.isVisible = true

        try {
            stabilizeMappedAwtFrames(popup to popupBounds)
            val target = NativeWindowCaptureBridge.captureTargetFor(popup)

            val framed = assertIs<FramedWindow>(target, "a JWindow has no title to select it by")
            assertEquals(popupBounds, framed.logicalFrame)
        } finally {
            popup.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `untitled Frame host is captured by its frame`() {
        assumeLiveAwtAvailable()
        val frameBounds = Rectangle(41, 52, 303, 204)
        val frame = Frame().apply { bounds = frameBounds }
        frame.isVisible = true

        try {
            stabilizeMappedAwtFrames(frame to frameBounds)
            val target =
                NativeWindowCaptureBridge.captureTargetFor(
                    frame,
                    selectsSharedTitlesByFrame = true,
                    selectsClientArea = false,
                )

            val framed = assertIs<FramedWindow>(target, "an empty title cannot select a Frame")
            assertEquals(frameBounds, framed.logicalFrame)
            assertEquals("", frame.title, "selecting the Frame must not stamp a temporary title")
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `untitled Dialog host is captured by its client frame on X11`() {
        assumeLiveAwtAvailable()
        val owner = Frame("dialog owner").apply { isVisible = true }
        val dialogBounds = Rectangle(43, 54, 305, 206)
        val dialog = Dialog(owner).apply { bounds = dialogBounds }
        dialog.isVisible = true

        try {
            stabilizeMappedAwtFrames(dialog to dialogBounds)
            val target =
                NativeWindowCaptureBridge.captureTargetFor(
                    dialog,
                    selectsSharedTitlesByFrame = true,
                    selectsClientArea = true,
                )

            val framed = assertIs<FramedWindow>(target, "an empty title cannot select a Dialog")
            val insets = dialog.insets
            assertEquals(
                Rectangle(
                    dialogBounds.x + insets.left,
                    dialogBounds.y + insets.top,
                    dialogBounds.width - insets.left - insets.right,
                    dialogBounds.height - insets.top - insets.bottom,
                ),
                framed.logicalFrame,
            )
            assertEquals("", dialog.title, "selecting the Dialog must not stamp a temporary title")
        } finally {
            dialog.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `titled Frame host keeps the title selector`() {
        assumeLiveAwtAvailable()
        val frame = Frame("bridge titled frame").apply { isVisible = true }

        try {
            val target = NativeWindowCaptureBridge.captureTargetFor(frame)

            assertFalse(target is FramedWindow, "a Frame keeps its title selector: $target")
            assertEquals("bridge titled frame", target.title)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `a Frame whose title another window shares is captured by its frame`() {
        // #611: Windows and Linux X11 select by owner pid and frame when the title is not unique.
        assumeLiveAwtAvailable()
        val frame = Frame("bridge shared title").apply { setBounds(40, 50, 300, 200) }
        val twin = Frame("bridge shared title").apply { setBounds(400, 50, 300, 200) }
        frame.isVisible = true
        twin.isVisible = true
        val frameBounds = Rectangle(40, 50, 300, 200)
        val twinBounds = Rectangle(400, 50, 300, 200)

        try {
            stabilizeMappedAwtFrames(frame to frameBounds, twin to twinBounds)
            val target =
                NativeWindowCaptureBridge.captureTargetFor(
                    frame,
                    selectsSharedTitlesByFrame = true,
                    selectsClientArea = false,
                )

            val framed = assertIs<FramedWindow>(target, "a shared title cannot select one window")
            assertEquals(frameBounds, framed.logicalFrame)
        } finally {
            frame.dispose()
            twin.dispose()
        }
    }

    @Test
    fun `on Linux the frame selector of a decorated window is its client area`() {
        // The Linux X11 helper matches client windows, which exclude the window-manager frame.
        assumeLiveAwtAvailable()
        val frame = Frame("bridge client area").apply { setBounds(40, 50, 300, 200) }
        val twin = Frame("bridge client area").apply { setBounds(400, 50, 300, 200) }
        frame.isVisible = true
        twin.isVisible = true
        val frameBounds = Rectangle(40, 50, 300, 200)
        val twinBounds = Rectangle(400, 50, 300, 200)

        try {
            stabilizeMappedAwtFrames(frame to frameBounds, twin to twinBounds)
            val insets = frame.insets
            val target =
                NativeWindowCaptureBridge.captureTargetFor(
                    frame,
                    selectsSharedTitlesByFrame = true,
                    selectsClientArea = true,
                )

            val framed = assertIs<FramedWindow>(target)
            assertEquals(
                Rectangle(
                    frameBounds.x + insets.left,
                    frameBounds.y + insets.top,
                    frameBounds.width - insets.left - insets.right,
                    frameBounds.height - insets.top - insets.bottom,
                ),
                framed.logicalFrame,
            )
        } finally {
            frame.dispose()
            twin.dispose()
        }
    }

    @Test
    fun `EDT capture does not wait for another capture lock`() {
        assumeLiveAwtAvailable()
        val frame = Frame()
        val captureLock = NativeWindowCaptureBridge.captureLockFor(frame)
        var actionRan = false

        try {
            captureLock.lock()
            EventQueue.invokeAndWait {
                assertFailsWith<IllegalStateException> {
                    NativeWindowCaptureBridge.withCaptureLock(frame) { actionRan = true }
                }
                assertFalse(actionRan, "the EDT must fail before entering the capture action")
            }
        } finally {
            captureLock.unlock()
            frame.dispose()
        }
    }
}

private fun assumeLiveAwtAvailable() {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        assumeTrue(
            System.getProperty("spectre.test.liveAwt").toBoolean(),
            "AWT Frame tests are opt-in on macOS because AppKit initialisation can hang in " +
                "non-interactive workers",
        )
    }
    assumeFalse(GraphicsEnvironment.isHeadless(), "AWT Frame requires a graphical environment")
}

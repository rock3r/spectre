package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
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
        val popup = JWindow(owner).apply { setBounds(40, 50, 300, 200) }

        try {
            val target = NativeWindowCaptureBridge.captureTargetFor(popup)

            val framed = assertIs<FramedWindow>(target, "a JWindow has no title to select it by")
            assertEquals(Rectangle(40, 50, 300, 200), framed.logicalFrame)
        } finally {
            popup.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `titled Frame host keeps the title selector`() {
        assumeLiveAwtAvailable()
        val frame = Frame("bridge titled frame")

        try {
            val target = NativeWindowCaptureBridge.captureTargetFor(frame)

            assertFalse(target is FramedWindow, "a Frame keeps its title selector: $target")
            assertEquals("bridge titled frame", target.title)
        } finally {
            frame.dispose()
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

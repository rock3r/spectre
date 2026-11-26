package dev.sebastiano.spectre.core

import java.awt.Component
import java.awt.MouseInfo
import java.awt.Panel
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Exercises the shipped Toolkit AWT listener, not a reimplementation. Distinguishes **dispatched in
 * this JVM** from **never dispatched** independently of `clickCount` / `typeText` — the
 * diagnostic #470 needs for an unfocused Xvfb click miss.
 *
 * Bypasses [inputDeliveryVerifiable] via [AwtInputDeliveryWitness.attachListener] so the oracle can
 * be proven on hosts where the production gate declines (macOS).
 */
class AwtInputDeliveryWitnessTest {

    @Test
    fun `shipped AWT listener reports dispatched when a matching press is posted`() {
        val observation = AwtInputDeliveryWitness.attachListener(DispatchedInput.Press)
        assumeTrue(observation != null, "toolkit refused the AWT listener")
        checkNotNull(observation).use { watch ->
            val press =
                MouseEvent(
                    Panel(),
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    1,
                    1,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            Toolkit.getDefaultToolkit().systemEventQueue.postEvent(press)
            val dispatched = watch.awaitDelivery(INPUT_WITNESS_WAIT_MS)
            println("AWT click verdict: dispatched in this JVM (posted MOUSE_PRESSED)=$dispatched")
            assertTrue(dispatched, "posted MOUSE_PRESSED must count as dispatched in this JVM")
        }
    }

    @Test
    fun `listener rejects wrong button and wrong screen position`() {
        val observation =
            AwtInputDeliveryWitness.attachListener(
                DispatchedInput.Press,
                screenX = 100,
                screenY = 200,
            )
        assumeTrue(observation != null, "toolkit refused the AWT listener")
        checkNotNull(observation).use { watch ->
            postPress(Panel(), MouseEvent.BUTTON3, 100, 200)
            postPress(Panel(), MouseEvent.BUTTON1, 120, 200)
            assertFalse(watch.awaitDelivery(NO_EVENT_WAIT_MS))
        }
    }

    @Test
    fun `listener accepts only a primary press at the requested visible host and position`() {
        assumeLiveAwtAvailable()
        val fixture = onEdt {
            val target = JPanel()
            val child = JPanel()
            val frame = JFrame("input witness fixture")
            target.add(child)
            frame.contentPane.add(target)
            frame.setSize(160, 120)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
            frame.validate()
            check(child.isShowing && child.width > 0 && child.height > 0) {
                "input witness child must be showing and laid out before events are posted"
            }
            val point = child.locationOnScreen
            InputWitnessFixture(frame, target, child, JPanel(), point.x + 1, point.y + 1)
        }
        try {
            val observation =
                AwtInputDeliveryWitness.attachListener(
                    DispatchedInput.Press,
                    fixture.target,
                    fixture.x,
                    fixture.y,
                )
            assumeTrue(observation != null, "toolkit refused the AWT listener")
            checkNotNull(observation).use { watch ->
                postPress(fixture.unrelated, MouseEvent.BUTTON1, fixture.x, fixture.y)
                postPress(fixture.child, MouseEvent.BUTTON3, fixture.x, fixture.y)
                postPress(fixture.child, MouseEvent.BUTTON1, fixture.x + 20, fixture.y)
                awaitEdtFifoMarker()
                assertFalse(watch.awaitDelivery(0))
                postPress(fixture.child, MouseEvent.BUTTON1, fixture.x, fixture.y)
                assertTrue(watch.awaitDelivery(INPUT_WITNESS_WAIT_MS))
            }
        } finally {
            onEdt { fixture.frame.dispose() }
        }
    }

    @Test
    fun `real wheel input is delivered as a targeted AWT wheel event`() {
        assumeLiveAwtAvailable()
        val osName = System.getProperty("os.name").orEmpty()
        assumeTrue(
            osName.contains("linux", ignoreCase = true) ||
                osName.contains("mac", ignoreCase = true),
            "wheel backend fixture is for Linux and macOS",
        )
        val robot = Robot()
        // Keep the initial pointer outside the fixture before it is mapped. This also prevents the
        // X event thread from racing Swing validation while MouseEvent is initialized.
        robot.mouseMove(0, 0)
        robot.waitForIdle()
        val (frame, target) =
            onEdt {
                val target = JPanel()
                val frame = JFrame("wheel witness fixture")
                frame.contentPane.add(target)
                frame.setSize(160, 120)
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
                frame to target
            }
        try {
            requestAndVerifyWindowFocus(frame)
            val expected = onEdt {
                check(target.isShowing && target.width > 0 && target.height > 0) {
                    "wheel target must be showing and laid out before input is injected"
                }
                target.locationOnScreen.let {
                    Point(it.x + target.width / 2, it.y + target.height / 2)
                }
            }
            establishNativePointerReadiness(frame, target, expected, robot)

            val wheelDeliveries = AtomicInteger()
            val wheelPositions = ConcurrentLinkedQueue<Point>()
            val wheelDelivered = CountDownLatch(1)
            onEdt {
                target.addMouseWheelListener { event ->
                    wheelDeliveries.incrementAndGet()
                    wheelPositions.add(Point(event.xOnScreen, event.yOnScreen))
                    wheelDelivered.countDown()
                }
            }
            val observation =
                AwtInputDeliveryWitness.attachListener(
                    DispatchedInput.Wheel,
                    target,
                    expected.x,
                    expected.y,
                )
            assumeTrue(observation != null, "toolkit refused the AWT listener")
            checkNotNull(observation).use { watch ->
                assertTrue(wheelDeliveries.get() == 0, "wheel witness must start empty")

                robot.mouseWheel(1)
                val targetReceived =
                    wheelDelivered.await(INPUT_WITNESS_WAIT_MS, TimeUnit.MILLISECONDS)
                val witnessed = watch.awaitDelivery(0)
                assertTrue(
                    targetReceived && wheelDeliveries.get() > 0,
                    "pointed-at target did not receive the commanded real wheel; " +
                        wheelDiagnostic(
                            frame,
                            expected,
                            wheelPositions,
                            wheelDeliveries.get(),
                        ),
                )
                assertTrue(
                    witnessed,
                    "production witness did not report the real target wheel delivery; " +
                        wheelDiagnostic(
                            frame,
                            expected,
                            wheelPositions,
                            wheelDeliveries.get(),
                        ),
                )
            }
        } finally {
            onEdt { frame.dispose() }
        }
    }

    private fun postPress(component: Component, button: Int, x: Int, y: Int) {
        val event =
            MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.getMaskForButton(button),
                1,
                1,
                x,
                y,
                1,
                false,
                button,
            )
        Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event)
    }

    private fun awaitEdtFifoMarker() {
        val marker = CountDownLatch(1)
        SwingUtilities.invokeLater { marker.countDown() }
        assertTrue(
            marker.await(TEST_EDT_WAIT_MS, TimeUnit.MILLISECONDS),
            "EDT did not dispatch the test FIFO marker",
        )
    }

    @Test
    fun `shipped AWT listener reports never dispatched when the queue drains with no press`() {
        val observation = AwtInputDeliveryWitness.attachListener(DispatchedInput.Press)
        assumeTrue(observation != null, "toolkit refused the AWT listener")
        checkNotNull(observation).use { watch ->
            val seen = watch.awaitDelivery(NO_EVENT_WAIT_MS)
            val drained = watch.awaitQueueDrain(DRAIN_WAIT_MS)
            val late = watch.awaitDelivery(0)
            val neverDispatched = !seen && drained && !late
            println(
                "AWT click verdict: never dispatched in this JVM=$neverDispatched " +
                    "(seen=$seen drained=$drained late=$late; no press posted)"
            )
            assertFalse(seen, "no press was posted, so none should have been dispatched")
            assertTrue(drained, "EDT must drain for a never-dispatched verdict to be trustworthy")
            assertFalse(late)
            assertTrue(neverDispatched)
        }
    }

    private fun Point.isNear(expected: Point): Boolean =
        abs(x - expected.x) <= INPUT_POINT_TOLERANCE_PX &&
            abs(y - expected.y) <= INPUT_POINT_TOLERANCE_PX

    private fun establishNativePointerReadiness(
        frame: JFrame,
        target: JPanel,
        expected: Point,
        robot: Robot,
    ) {
        val motionPositions = ConcurrentLinkedQueue<Point>()
        val arrivedAtExpected = CountDownLatch(1)
        val listener =
            object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    val position = Point(event.xOnScreen, event.yOnScreen)
                    motionPositions.add(position)
                    if (position.isNear(expected)) arrivedAtExpected.countDown()
                }
            }
        val approach = onEdt {
            target.addMouseMotionListener(listener)
            target.locationOnScreen.let { Point(it.x + target.width / 4, it.y + target.height / 2) }
        }
        check(!approach.isNear(expected)) { "pointer approach must be distinct from target center" }

        try {
            robot.mouseMove(approach.x, approach.y)
            robot.waitForIdle()
            waitForNativePointer(approach)
            val approachDeadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INPUT_WITNESS_WAIT_MS)
            while (motionPositions.isEmpty() && System.nanoTime() < approachDeadline) {
                Thread.sleep(10)
            }
            robot.mouseMove(expected.x, expected.y)
            robot.waitForIdle()
            val ready = arrivedAtExpected.await(INPUT_WITNESS_WAIT_MS, TimeUnit.MILLISECONDS)
            if (!ready) {
                assertTrue(
                    ready,
                    "target did not observe native pointer motion at its center; " +
                        wheelDiagnostic(frame, expected, motionPositions, deliveries = 0),
                )
            }
        } finally {
            onEdt { target.removeMouseMotionListener(listener) }
        }
    }

    private fun waitForNativePointer(expected: Point) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INPUT_WITNESS_WAIT_MS)
        while (System.nanoTime() < deadline) {
            if (MouseInfo.getPointerInfo()?.location?.isNear(expected) == true) return
            Thread.sleep(10)
        }
        error(
            "native pointer did not reach $expected; actual=${MouseInfo.getPointerInfo()?.location}"
        )
    }

    private fun wheelDiagnostic(
        frame: JFrame,
        expected: Point,
        observedPositions: Collection<Point>,
        deliveries: Int,
    ): String {
        val actualPointer = MouseInfo.getPointerInfo()?.location
        val focus = onEdt {
            val owner = frame.focusOwner
            "window focused=${frame.isFocused}, window active=${frame.isActive}, " +
                "focus owner=${owner?.javaClass?.name}:${owner?.name}"
        }
        return "expected point=$expected, actual pointer=$actualPointer, $focus, " +
            "observed positions=${observedPositions.toList()}, target deliveries=$deliveries"
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()

        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private data class InputWitnessFixture(
        val frame: JFrame,
        val target: JPanel,
        val child: JPanel,
        val unrelated: JPanel,
        val x: Int,
        val y: Int,
    )

    private companion object {
        init {
            // Initialize MouseEvent before any fixture maps a native window. On X11 the native
            // event thread can otherwise initialize it concurrently with Swing validation and
            // deadlock on the AWT lock/class-initialization lock pair.
            MouseEvent(Panel(), MouseEvent.MOUSE_MOVED, 0, 0, 0, 0, 0, false)
        }

        const val INPUT_WITNESS_WAIT_MS = 2_000L
        const val NO_EVENT_WAIT_MS = 50L
        const val DRAIN_WAIT_MS = 500L
        const val TEST_EDT_WAIT_MS = 5_000L
        const val INPUT_POINT_TOLERANCE_PX = 2
    }
}

package dev.sebastiano.spectre.core

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class SyntheticInputTest {

    @Test
    fun `synthetic key char mapping covers RobotDriver typeText punctuation`() {
        assertEquals('-', keyCharFor(KeyEvent.VK_MINUS))
        assertEquals('_', keyCharFor(KeyEvent.VK_MINUS, shift = true))
        assertEquals('=', keyCharFor(KeyEvent.VK_EQUALS))
        assertEquals('+', keyCharFor(KeyEvent.VK_EQUALS, shift = true))
        assertEquals('[', keyCharFor(KeyEvent.VK_OPEN_BRACKET))
        assertEquals('{', keyCharFor(KeyEvent.VK_OPEN_BRACKET, shift = true))
        assertEquals(';', keyCharFor(KeyEvent.VK_SEMICOLON))
        assertEquals(':', keyCharFor(KeyEvent.VK_SEMICOLON, shift = true))
        assertEquals('.', keyCharFor(KeyEvent.VK_PERIOD))
        assertEquals('?', keyCharFor(KeyEvent.VK_SLASH, shift = true))
    }

    /**
     * Regression test for the EDT deadlock between [RobotDriver]'s `runOffEdt` and the synthetic
     * adapter's `runOnEdt`.
     *
     * Before the original fix, `RobotDriver.runOffEdt` always spawned a worker and
     * `Thread.join()`-ed on it even when the underlying [RobotAdapter] was the synthetic one. The
     * synthetic adapter then called `SwingUtilities.invokeAndWait` to marshal the dispatch back to
     * the EDT — which was blocked on the join. Calls from a UI callback (any code path that runs
     * `automator.click(...)` inside `SwingUtilities.invokeAndWait { ... }`) would deadlock
     * indefinitely.
     *
     * The synthetic adapter doesn't need the off-EDT worker (it does its own EDT marshalling inside
     * `runOnEdt`), so [RobotAdapter.requiresOffEdt] lets `runOffEdt` skip the dispatcher switch
     * (today: `withContext(Dispatchers.IO)`) for synthetic and headless adapters. The suspend
     * conversion in #93 preserved that contract — calling `driver.click(...)` from an EDT coroutine
     * via `runBlocking` still runs the synthetic dispatch inline on the EDT.
     *
     * Uses a plain Swing [JFrame] rather than a Compose `Window` because:
     * - the deadlock is purely an AWT/EDT scheduling bug; it surfaces identically for any AWT
     *   window the synthetic adapter targets
     * - core's test classpath doesn't pull in compose-desktop (`Window`/`application` belong to
     *   `compose.desktop.currentOs`), and adding it would require the Compose compiler plugin too.
     *   The end-to-end Compose path is already covered by the sample-desktop validation tests.
     */
    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `click from EDT does not deadlock with synthetic adapter`() {
        assumeLiveAwtAvailable()

        val frame = createTestFrame()
        try {
            val targetCenter = frame.targetCenterOnScreen()
            val driver = RobotDriver.synthetic(frame.frame)
            val invokeAndWaitFinished = CountDownLatch(1)

            // Run the click on a non-EDT thread that itself blocks the EDT via invokeAndWait.
            // This mirrors the production deadlock: a UI callback (EDT) drives an automator
            // method, which drives the synthetic adapter's EDT marshalling.
            val driverThread =
                Thread(
                        {
                            SwingUtilities.invokeAndWait {
                                // runBlocking on EDT is a deliberate choice here — the test
                                // pins the contract that a synthetic-adapter click invoked from
                                // an EDT coroutine does not deadlock. With requiresOffEdt = false
                                // for the synthetic adapter, runOffEdt skips the dispatcher
                                // switch and the synthetic adapter inlines its own runOnEdt.
                                runBlocking {
                                    driver.click(targetCenter.first, targetCenter.second)
                                }
                            }
                            invokeAndWaitFinished.countDown()
                        },
                        "synthetic-click-deadlock-test",
                    )
                    .apply { isDaemon = true }
            driverThread.start()

            val completed = invokeAndWaitFinished.await(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(completed, "RobotDriver.click from the EDT deadlocked")
            assertTrue(
                frame.target.clickLatch.await(2, TimeUnit.SECONDS),
                "Synthetic click was not dispatched",
            )
        } finally {
            // Use invokeLater (not invokeAndWait) for cleanup so a deadlocked EDT — the
            // pre-fix failure mode — doesn't also deadlock the cleanup path. The frame is
            // a daemon-thread JVM resource; it will go away with the JVM if the EDT can't
            // process the dispose now.
            SwingUtilities.invokeLater {
                frame.frame.isVisible = false
                frame.frame.dispose()
            }
        }
    }

    private fun createTestFrame(): TestFrame {
        val target = ClickTargetPanel()
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait {
            frame =
                JFrame("SyntheticInputTest").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    isUndecorated = true
                    contentPane.layout = BorderLayout()
                    contentPane.add(target, BorderLayout.CENTER)
                    size = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                    setLocation(FRAME_OFFSET_PX, FRAME_OFFSET_PX)
                    isVisible = true
                }
        }
        // Wait until the frame is showing so locationOnScreen is valid.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && !target.isShowing) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        check(target.isShowing) { "Test JFrame did not become visible" }
        return TestFrame(frame!!, target)
    }

    private fun TestFrame.targetCenterOnScreen(): Pair<Int, Int> {
        var center = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = target.locationOnScreen
            center = (origin.x + target.width / 2) to (origin.y + target.height / 2)
        }
        return center
    }

    private class TestFrame(val frame: JFrame, val target: ClickTargetPanel)

    private class ClickTargetPanel : JPanel() {
        val clickLatch = CountDownLatch(1)

        init {
            preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        clickLatch.countDown()
                    }
                }
            )
        }
    }

    private companion object {
        // Backstop in case the production fix regresses — `@Timeout` aborts the test rather
        // than letting a blocked EDT pin the JVM forever (the EDT is non-daemon, so a stuck
        // SwingUtilities.invokeAndWait would otherwise prevent JVM exit).
        const val TIMEOUT_SECONDS: Long = 15L
        const val DEADLOCK_TIMEOUT_SECONDS: Long = 5L
        const val SHOW_TIMEOUT_SECONDS: Long = 5L
        const val POLL_INTERVAL_MS: Long = 25L
        const val FRAME_SIZE_PX: Int = 200
        const val FRAME_OFFSET_PX: Int = 100
    }
}

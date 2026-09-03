@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * Locks the public default: every no-driver `ComposeAutomator.inProcess()` call constructs
 * [RobotDriver.synthetic], not a real OS [RobotDriver].
 */
class InProcessDefaultDriverTest {

    @Test
    fun `inProcess default argument is RobotDriver synthetic`() {
        val source =
            Path.of("src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt").readText()
        assertTrue(
            source.contains("robotDriver: RobotDriver = RobotDriver.synthetic()"),
            "ComposeAutomator.inProcess() must default to RobotDriver.synthetic()",
        )
        assertFalse(
            source.contains("robotDriver: RobotDriver = RobotDriver()"),
            "ComposeAutomator.inProcess() must not default to the real-OS RobotDriver()",
        )
    }

    @Test
    fun `synthetic companion exposes a no-window factory`() {
        val noArg =
            RobotDriver.Companion::class.java.methods.filter { method ->
                method.name == "synthetic" && method.parameterCount == 0
            }
        assertTrue(
            noArg.isNotEmpty(),
            "RobotDriver.synthetic() must exist so inProcess() can default to it without a window",
        )
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `inProcess without a driver reports synthetic capabilities`() {
        assumeLiveAwtAvailable()
        val automator = ComposeAutomator.inProcess()
        assertFalse(
            automator.inputCapabilities.realOsInput,
            "Default inProcess() must not use real OS input",
        )
        assertTrue(
            automator.inputCapabilities.sharedSystemClipboard,
            "Default synthetic driver still uses the system clipboard for pasteText",
        )
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `explicit RobotDriver remains real OS input`() {
        assumeLiveAwtAvailable()
        val automator = ComposeAutomator.inProcess(robotDriver = RobotDriver())
        assertTrue(
            automator.inputCapabilities.realOsInput,
            "RobotDriver() is the explicit real-OS opt-in",
        )
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `no-window synthetic factory hits a window created after construction`() {
        assumeLiveAwtAvailable()
        val companion = RobotDriver.Companion
        val noArg =
            companion.javaClass.methods.singleOrNull { method ->
                method.name == "synthetic" && method.parameterCount == 0
            }
        assertNotNull(noArg, "RobotDriver.synthetic() factory is required")
        val driver = noArg.invoke(companion) as RobotDriver
        assertFalse(driver.inputCapabilities.realOsInput)

        val clicks = AtomicInteger(0)
        val shown = CountDownLatch(1)
        val target =
            JPanel().apply {
                isOpaque = true
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            clicks.incrementAndGet()
                        }
                    }
                )
            }
        val frame = showFrameOnEdt(target, shown)
        try {
            assertTrue(shown.await(SHOW_TIMEOUT_SECONDS, TimeUnit.SECONDS), "frame did not show")
            val (x, y) = targetCenterOnScreen(target)
            runBlocking { driver.click(x, y) }
            drainEdt()
            assertEquals(1, clicks.get(), "expected one synthetic click on a later-created window")
        } finally {
            disposeFrame(frame)
        }
    }

    private fun showFrameOnEdt(target: JPanel, shown: CountDownLatch): JFrame {
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame =
                JFrame("InProcessDefaultDriverTest").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    isUndecorated = true
                    contentPane.layout = BorderLayout()
                    contentPane.add(target, BorderLayout.CENTER)
                    size = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                    setLocation(FRAME_OFFSET_PX, FRAME_OFFSET_PX)
                    isVisible = true
                }
            shown.countDown()
        }
        return frame
    }

    private fun targetCenterOnScreen(target: JPanel): Pair<Int, Int> {
        var center = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = target.locationOnScreen
            center = (origin.x + target.width / 2) to (origin.y + target.height / 2)
        }
        return center
    }

    private fun drainEdt() {
        repeat(2) { SwingUtilities.invokeAndWait {} }
    }

    private fun disposeFrame(frame: JFrame) {
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 15
        const val SHOW_TIMEOUT_SECONDS: Long = 5
        const val FRAME_SIZE_PX: Int = 160
        const val FRAME_OFFSET_PX: Int = 80
    }
}

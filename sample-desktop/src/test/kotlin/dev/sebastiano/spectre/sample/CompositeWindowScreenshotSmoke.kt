@file:JvmName("CompositeWindowScreenshotSmoke")
@file:OptIn(dev.sebastiano.spectre.core.InternalSpectreApi::class)

package dev.sebastiano.spectre.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.WindowScreenshotResult
import java.awt.Dimension
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * End-to-end native composite screenshot smoke. It opens a Compose owner and an overlapping Swing
 * dialog, discovers both through [ComposeAutomator], captures each through the platform native
 * backend, and verifies union geometry, complete source extents, and front-to-back pixel order.
 */
fun main() {
    var exitCode = 0
    try {
        runSmoke()
    } catch (failure: Throwable) {
        System.err.println("Composite screenshot smoke failed: ${failure.message}")
        System.err.println(failure.stackTraceToString())
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runSmoke() {
    val windows = openWindows()
    try {
        Thread.sleep(WINDOW_SETTLE_MS)
        val automator = ComposeAutomator.inProcess()
        automator.refreshWindows()
        val ownerIndex = automator.windows.indexOfFirst { it.window === windows.owner }
        val dialogIndex = automator.windows.indexOfFirst { it.window === windows.dialog }
        check(ownerIndex >= 0) { "Compose owner was not discovered: ${automator.surfaceIds()}" }
        check(dialogIndex >= 0) {
            "Owned Swing dialog was not discovered: ${automator.surfaceIds()}"
        }

        val result = automator.screenshotWindows(listOf(ownerIndex, dialogIndex))
        val owner =
            result.windows[0] as? WindowScreenshotResult.Success
                ?: error("Owner capture failed: ${result.windows[0]}")
        val dialog =
            result.windows[1] as? WindowScreenshotResult.Success
                ?: error("Dialog capture failed: ${result.windows[1]}")
        val composite =
            checkNotNull(result.composite) { "Composite was omitted despite two successes" }

        check(owner.boundsOnScreen.contains(windows.owner.bounds)) {
            "Owner capture does not contain the full owner: ${owner.boundsOnScreen} vs ${windows.owner.bounds}"
        }
        check(dialog.boundsOnScreen.contains(windows.dialog.bounds)) {
            "Dialog capture does not contain the full dialog: ${dialog.boundsOnScreen} vs ${windows.dialog.bounds}"
        }
        val expectedUnion = owner.boundsOnScreen.union(dialog.boundsOnScreen)
        check(composite.boundsOnScreen == expectedUnion) {
            "Composite bounds ${composite.boundsOnScreen} do not equal source union $expectedUnion"
        }

        assertColor(composite, ownerOnlyPoint(windows), OWNER_COLOR, "owner-only pixel")
        assertColor(composite, dialogCenter(windows), DIALOG_COLOR, "dialog overlap pixel")

        val output =
            System.getenv("SPECTRE_COMPOSITE_SMOKE_OUTPUT")?.let(Path::of)
                ?: Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "spectre-composite-window-smoke.png",
                )
        output.parent?.let(Files::createDirectories)
        Files.deleteIfExists(output)
        check(ImageIO.write(composite.image, "png", output.toFile())) { "Could not write $output" }
        check(Files.size(output) > 0L) { "Composite PNG is empty: $output" }
        println(
            "Composite screenshot smoke PASS: ${composite.image.width}x${composite.image.height} -> $output"
        )
    } finally {
        SwingUtilities.invokeAndWait {
            windows.dialog.dispose()
            windows.owner.dispose()
        }
    }
}

private fun assertColor(
    composite: dev.sebastiano.spectre.core.CompositedWindowImage,
    screenPoint: Point,
    expected: java.awt.Color,
    label: String,
) {
    val x = ((screenPoint.x - composite.boundsOnScreen.x) * composite.densityScale).roundToInt()
    val y = ((screenPoint.y - composite.boundsOnScreen.y) * composite.densityScale).roundToInt()
    val actual = java.awt.Color(composite.image.getRGB(x, y), true)
    val distance =
        kotlin.math.abs(actual.red - expected.red) +
            kotlin.math.abs(actual.green - expected.green) +
            kotlin.math.abs(actual.blue - expected.blue)
    check(distance <= COLOR_DISTANCE_TOLERANCE) {
        "$label expected $expected at $screenPoint, got $actual (distance=$distance)"
    }
}

private fun openWindows(): SmokeWindows {
    val ready = CountDownLatch(1)
    var result: SmokeWindows? = null
    SwingUtilities.invokeLater {
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(OWNER_WIDTH, OWNER_HEIGHT)
                setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            }
        val owner =
            JFrame(OWNER_TITLE).apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = composePanel
                pack()
                setLocation(OWNER_X, OWNER_Y)
                isVisible = true
            }
        val dialog =
            JDialog(owner, DIALOG_TITLE, false).apply {
                contentPane =
                    ComposePanel().apply {
                        setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                    }
                size = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
                setLocation(owner.x + DIALOG_OFFSET_X, owner.y + DIALOG_OFFSET_Y)
                isVisible = true
                toFront()
            }
        result = SmokeWindows(owner, dialog)
        ready.countDown()
    }
    check(ready.await(WINDOW_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Timed out opening windows"
    }
    return checkNotNull(result)
}

private fun ownerOnlyPoint(windows: SmokeWindows): Point =
    Point(windows.owner.x + OWNER_SAMPLE_X, windows.owner.y + OWNER_SAMPLE_Y)

private fun dialogCenter(windows: SmokeWindows): Point =
    Point(windows.dialog.x + windows.dialog.width / 2, windows.dialog.y + windows.dialog.height / 2)

private data class SmokeWindows(val owner: JFrame, val dialog: JDialog)

private val OWNER_COLOR = java.awt.Color.RED
private val DIALOG_COLOR = java.awt.Color.BLUE
private const val OWNER_TITLE = "Spectre composite owner"
private const val DIALOG_TITLE = "Spectre composite dialog"
private const val OWNER_WIDTH = 420
private const val OWNER_HEIGHT = 280
private const val DIALOG_WIDTH = 220
private const val DIALOG_HEIGHT = 140
private const val OWNER_X = 120
private const val OWNER_Y = 100
private const val DIALOG_OFFSET_X = 180
private const val DIALOG_OFFSET_Y = 90
private const val OWNER_SAMPLE_X = 80
private const val OWNER_SAMPLE_Y = 100
private const val COLOR_DISTANCE_TOLERANCE = 30
private const val WINDOW_SETTLE_MS = 1_000L
private const val WINDOW_READY_TIMEOUT_SECONDS = 10L

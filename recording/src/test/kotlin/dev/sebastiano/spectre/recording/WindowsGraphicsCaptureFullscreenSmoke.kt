@file:JvmName("WindowsGraphicsCaptureFullscreenSmoke")

package dev.sebastiano.spectre.recording

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Manual Windows-only smoke for primary-monitor WGC recording. Run via `./gradlew
 * :recording:runWindowsGraphicsCaptureFullscreenSmoke`.
 */
fun main() {
    var exitCode = 0
    try {
        runFullscreenSmoke()
    } catch (t: Throwable) {
        System.err.println("Smoke failed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runFullscreenSmoke() {
    check(System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
        "Windows Graphics Capture fullscreen smoke only runs on Windows."
    }

    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-wgc-fullscreen-smoke.mp4")
    Files.deleteIfExists(output)

    val region = primaryMonitorBounds()
    val handle =
        AutoRecorder()
            .startRegion(
                region = region,
                output = output,
                options = RecordingOptions(frameRate = 15, captureCursor = true),
            )
    try {
        sweepCursor(region)
        Thread.sleep(RECORDING_AFTER_INPUT_MS)
    } finally {
        handle.stop()
    }

    val bytes = Files.size(output)
    check(bytes > MINIMUM_RECORDING_BYTES) {
        "Expected a non-empty MP4 larger than $MINIMUM_RECORDING_BYTES bytes, got $bytes at $output"
    }
    println(
        "Windows Graphics Capture fullscreen smoke recorded " +
            "${region.width}x${region.height} primary monitor, $bytes bytes -> $output"
    )
}

private fun primaryMonitorBounds(): Rectangle =
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds

private fun sweepCursor(region: Rectangle) {
    val robot =
        Robot().apply {
            autoDelay = ROBOT_AUTO_DELAY_MS
            isAutoWaitForIdle = true
        }
    repeat(CURSOR_SWEEP_STEPS) { index ->
        val fraction = index.toDouble() / (CURSOR_SWEEP_STEPS - 1).coerceAtLeast(1)
        robot.mouseMove(
            region.x + (region.width * fraction).toInt().coerceIn(0, region.width - 1),
            region.y + (region.height * fraction).toInt().coerceIn(0, region.height - 1),
        )
    }
}

private const val RECORDING_AFTER_INPUT_MS: Long = 800
private const val ROBOT_AUTO_DELAY_MS: Int = 60
private const val CURSOR_SWEEP_STEPS: Int = 12
private const val MINIMUM_RECORDING_BYTES: Long = 8_192

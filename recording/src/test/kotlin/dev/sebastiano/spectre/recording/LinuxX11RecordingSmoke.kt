@file:JvmName("LinuxX11RecordingSmoke")

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * End-to-end smoke for the Linux helper's Xorg/Xvfb recording path. Run via `./gradlew
 * :recording:runLinuxX11RecordingSmoke` from an X display (CI uses `xvfb-run`).
 *
 * Covers:
 * - region capture of a small undecorated window
 * - named-window capture via `AutoRecorder.startWindow` (middle-dot title, #513)
 * - fail-closed lookup when the title does not exist
 */
fun main() {
    var exitCode = 0
    try {
        runRegionSmoke()
        runNamedWindowSmoke()
        runMissingTitleFailsClosed()
    } catch (t: Throwable) {
        System.err.println("Smoke failed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runRegionSmoke() {
    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-linux-x11-smoke.mp4")
    val midRecordingPng =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-linux-x11-smoke.png")
    Files.deleteIfExists(output)
    Files.deleteIfExists(midRecordingPng)

    val (window, label) = openRegionSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)
    waitForVisibleFrame(window)

    val frameBoundsAtStart = window.bounds
    val handle =
        AutoRecorder()
            .startRegion(
                region = frameBoundsAtStart,
                output = output,
                options = RecordingOptions(frameRate = 30, captureCursor = true),
            )

    println("Region recording started -> $output (pid=${ProcessHandle.current().pid()})")

    var ticks = 0
    val animator =
        Thread.ofPlatform().daemon().name("spectre-linux-x11-smoke-animator").start {
            val deadline = System.nanoTime() + RECORD_DURATION_MS * 1_000_000L
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                ticks += 1
                val current = ticks
                SwingUtilities.invokeLater { label.text = "tick=$current" }
                Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
            }
        }

    Thread.sleep(RECORD_DURATION_MS / 2)
    val frameBounds = window.bounds
    val robotShot = Robot().createScreenCapture(frameBounds)
    ImageIO.write(robotShot, "png", File(midRecordingPng.toString()))
    checkVisibleReferenceFrame(robotShot, frameBounds)
    println("Robot screenshot of smoke window bounds $frameBounds -> $midRecordingPng")

    animator.join()
    println("Region animation done; ticks fired: $ticks")

    handle.stop()
    val sizeBytes = if (Files.exists(output)) Files.size(output) else -1
    println("Region recording stopped -> $output ($sizeBytes bytes)")

    SwingUtilities.invokeLater {
        window.isVisible = false
        window.dispose()
    }
}

private fun runNamedWindowSmoke() {
    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-linux-x11-window-smoke.mp4")
    Files.deleteIfExists(output)

    val (frame, label) = openNamedSmokeWindow()
    try {
        Thread.sleep(WINDOW_SETTLE_MS)
        waitForVisibleFrame(frame)
        SwingUtilities.invokeAndWait {
            frame.toFront()
            frame.requestFocus()
            label.text = "window tick"
        }
        Thread.sleep(WINDOW_SETTLE_MS)

        val windowBounds = frame.bounds
        val screen = Toolkit.getDefaultToolkit().screenSize
        println("Named-window smoke: title='${frame.title}' bounds=$windowBounds screen=$screen")

        val handle =
            AutoRecorder()
                .startWindow(
                    window = frame.asTitledWindow(),
                    output = output,
                    options = RecordingOptions(frameRate = 15, captureCursor = false),
                )
        println("Named-window recording started -> $output")
        Thread.sleep(NAMED_WINDOW_RECORD_MS)
        handle.stop()

        val sizeBytes = Files.size(output)
        check(sizeBytes > 0) { "Named-window recording wrote an empty file: $output" }
        val (mp4Width, mp4Height) = readMp4VideoSize(output)
        logFfprobe(output)
        println(
            "Named-window recording stopped -> $output ($sizeBytes bytes, " +
                "ffprobe-equivalent ${mp4Width}x${mp4Height})"
        )
        assertWindowSizedMp4(
            mp4Width = mp4Width,
            mp4Height = mp4Height,
            windowBounds = windowBounds,
            screenWidth = screen.width,
            screenHeight = screen.height,
        )
        println(
            "PASS named-window: MP4 ${mp4Width}x${mp4Height} matches window $windowBounds " +
                "(decoration tolerance ${DECORATION_TOLERANCE_PX}px), not the " +
                "${screen.width}x${screen.height} framebuffer"
        )
    } finally {
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }
}

private fun runMissingTitleFailsClosed() {
    val output =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-linux-x11-missing-title.mp4")
    Files.deleteIfExists(output)
    val missing =
        object : TitledWindow {
            override var title: String? = MISSING_WINDOW_TITLE
            override val bounds: Rectangle = Rectangle(0, 0, 120, 80)
        }
    val error = runCatching {
        LinuxX11Recorder()
            .start(
                window = missing,
                windowOwnerPid = ProcessHandle.current().pid(),
                output = output,
                options = RecordingOptions(frameRate = 15, captureCursor = false),
            )
    }
        .exceptionOrNull()
    val failed =
        checkNotNull(error) {
            "Missing title must fail closed; helper started a recording instead of erroring"
        }
    val message = generateSequence(failed) { it.cause }.mapNotNull { it.message }.joinToString("\n")
    check(message.contains("was not found") || message.contains("not found")) {
        "Missing-title error must mention the unresolved window, got: $message"
    }
    check(message.contains("root") || message.contains("desktop")) {
        "Missing-title error must mention the silent root/desktop hazard, got: $message"
    }
    check(!Files.exists(output) || Files.size(output) == 0L) {
        "Fail-closed missing title must not leave a desktop-sized MP4 at $output"
    }
    println("PASS missing-title: helper failed closed (${failed.javaClass.simpleName})")
}

private fun assertWindowSizedMp4(
    mp4Width: Int,
    mp4Height: Int,
    windowBounds: Rectangle,
    screenWidth: Int,
    screenHeight: Int,
) {
    check(mp4Width > 1 && mp4Height > 1) {
        "Named-window MP4 has invalid dimensions ${mp4Width}x${mp4Height}"
    }
    val fullDesktop =
        mp4Width == screenWidth &&
            mp4Height == screenHeight &&
            (windowBounds.width + DECORATION_TOLERANCE_PX < screenWidth ||
                windowBounds.height + DECORATION_TOLERANCE_PX < screenHeight)
    check(!fullDesktop) {
        "Named-window MP4 is the full framebuffer ${mp4Width}x${mp4Height} " +
            "(window was ${windowBounds.width}x${windowBounds.height} at " +
            "${windowBounds.x},${windowBounds.y}). ximagesrc fell back to the root window."
    }
    check(abs(mp4Width - windowBounds.width) <= DECORATION_TOLERANCE_PX) {
        "Named-window MP4 width $mp4Width is not within ${DECORATION_TOLERANCE_PX}px of " +
            "window width ${windowBounds.width}"
    }
    check(abs(mp4Height - windowBounds.height) <= DECORATION_TOLERANCE_PX) {
        "Named-window MP4 height $mp4Height is not within ${DECORATION_TOLERANCE_PX}px of " +
            "window height ${windowBounds.height}"
    }
}

private fun waitForVisibleFrame(window: Window) {
    val robot = Robot()
    val deadline = System.nanoTime() + VISIBLE_FRAME_TIMEOUT_MS * NANOS_PER_MILLI
    var lastShot: BufferedImage? = null
    while (System.nanoTime() < deadline) {
        val bounds = window.bounds
        val shot = robot.createScreenCapture(bounds)
        if (hasEnoughVisiblePixels(shot)) return
        lastShot = shot
        SwingUtilities.invokeAndWait {
            window.toFront()
            window.repaint()
            Toolkit.getDefaultToolkit().sync()
        }
        robot.waitForIdle()
        Thread.sleep(VISIBLE_FRAME_POLL_MS)
    }
    checkVisibleReferenceFrame(checkNotNull(lastShot), window.bounds)
}

private fun checkVisibleReferenceFrame(image: BufferedImage, bounds: Rectangle) {
    val visiblePixels = visiblePixelCount(image)
    val totalPixels = totalPixelCount(image)
    val minimumVisiblePixels = totalPixels / MIN_VISIBLE_PIXEL_DIVISOR
    check(visiblePixels >= minimumVisiblePixels) {
        "X11 smoke reference screenshot for $bounds is effectively black " +
            "($visiblePixels/$totalPixels visible pixels). If this is running inside a native " +
            "Wayland session with XWayland, the X11 root framebuffer may not contain the " +
            "composited desktop; run this smoke under a real Xorg/Xvfb display, or validate the " +
            "normal Wayland portal route instead."
    }
}

private fun hasEnoughVisiblePixels(image: BufferedImage): Boolean =
    visiblePixelCount(image) >= totalPixelCount(image) / MIN_VISIBLE_PIXEL_DIVISOR

private fun totalPixelCount(image: BufferedImage): Int = image.width * image.height

private fun visiblePixelCount(image: BufferedImage): Int {
    var visiblePixels = 0
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val red = rgb shr 16 and COLOR_MASK
            val green = rgb shr 8 and COLOR_MASK
            val blue = rgb and COLOR_MASK
            if (red + green + blue > MIN_VISIBLE_RGB_SUM) visiblePixels += 1
        }
    }
    return visiblePixels
}

private fun openRegionSmokeWindow(): Pair<Window, JLabel> {
    val ready = CountDownLatch(1)
    var windowRef: Window? = null
    var labelRef: JLabel? = null
    SwingUtilities.invokeLater {
        val label = smokeLabel("tick=0")
        val window =
            JWindow().apply {
                contentPane = smokePanel(label)
                size = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
                setBounds(WINDOW_X, WINDOW_Y, WINDOW_WIDTH, WINDOW_HEIGHT)
                isVisible = true
                toFront()
                repaint()
                Toolkit.getDefaultToolkit().sync()
            }
        windowRef = window
        labelRef = label
        ready.countDown()
    }
    check(ready.await(WINDOW_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Smoke window never came up within ${WINDOW_OPEN_TIMEOUT_SECONDS}s"
    }
    return checkNotNull(windowRef) to checkNotNull(labelRef)
}

private fun openNamedSmokeWindow(): Pair<JFrame, JLabel> {
    val ready = CountDownLatch(1)
    var frameRef: JFrame? = null
    var labelRef: JLabel? = null
    SwingUtilities.invokeLater {
        val label = smokeLabel("starting")
        val frame =
            JFrame(NAMED_WINDOW_TITLE).apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = smokePanel(label)
                size = Dimension(NAMED_WINDOW_WIDTH, NAMED_WINDOW_HEIGHT)
                setBounds(NAMED_WINDOW_X, NAMED_WINDOW_Y, NAMED_WINDOW_WIDTH, NAMED_WINDOW_HEIGHT)
                isVisible = true
                toFront()
                requestFocus()
                Toolkit.getDefaultToolkit().sync()
            }
        frameRef = frame
        labelRef = label
        ready.countDown()
    }
    check(ready.await(WINDOW_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Named smoke window never came up within ${WINDOW_OPEN_TIMEOUT_SECONDS}s"
    }
    return checkNotNull(frameRef) to checkNotNull(labelRef)
}

private fun smokeLabel(text: String): JLabel =
    JLabel(text, SwingConstants.CENTER).apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, LABEL_FONT_SIZE)
        foreground = Color.BLACK
    }

private fun smokePanel(label: JLabel): JPanel =
    JPanel(BorderLayout()).apply {
        background = Color.WHITE
        isOpaque = true
        add(label, BorderLayout.CENTER)
    }

private fun logFfprobe(output: Path) {
    val ffprobe = findOnPath("ffprobe") ?: return
    val process =
        ProcessBuilder(
                ffprobe,
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height",
                "-of",
                "csv=p=0",
                output.toAbsolutePath().toString(),
            )
            .redirectErrorStream(true)
            .start()
    val stdout = process.inputStream.bufferedReader().readText().trim()
    process.waitFor(5, TimeUnit.SECONDS)
    if (stdout.isNotEmpty()) println("ffprobe $output -> $stdout")
}

private fun findOnPath(name: String): String? {
    val path = System.getenv("PATH") ?: return null
    return path
        .split(File.pathSeparator)
        .asSequence()
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

private fun readMp4VideoSize(path: Path): Pair<Int, Int> {
    val bytes = Files.readAllBytes(path)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return findTkhdSize(buffer, 0, bytes.size) ?: error("Could not find an MP4 tkhd atom in $path")
}

private fun findTkhdSize(buffer: ByteBuffer, start: Int, end: Int): Pair<Int, Int>? {
    var offset = start
    while (offset + BOX_HEADER_SIZE <= end) {
        buffer.position(offset)
        var size = buffer.int.toLong() and 0xffff_ffffL
        val typeBytes = ByteArray(4)
        buffer.get(typeBytes)
        val type = String(typeBytes, StandardCharsets.US_ASCII)
        var header = BOX_HEADER_SIZE
        if (size == 1L) {
            if (offset + BOX_HEADER_SIZE + 8 > end) return null
            size = buffer.long
            header += 8
        } else if (size == 0L) {
            size = (end - offset).toLong()
        }
        if (size < header || offset + size.toInt() > end) return null
        val payloadStart = offset + header
        val payloadEnd = offset + size.toInt()
        when (type) {
            "moov",
            "trak" ->
                findTkhdSize(buffer, payloadStart, payloadEnd)?.let {
                    return it
                }
            "tkhd" -> return parseTkhd(buffer, payloadStart, payloadEnd)
        }
        offset = payloadEnd
    }
    return null
}

private fun parseTkhd(buffer: ByteBuffer, start: Int, end: Int): Pair<Int, Int>? {
    if (end - start < TKHD_MIN_VERSION0_SIZE) return null
    buffer.position(start)
    val version = buffer.get().toInt() and 0xff
    val widthOffset =
        when (version) {
            0 -> start + TKHD_V0_WIDTH_OFFSET
            1 -> start + TKHD_V1_WIDTH_OFFSET
            else -> return null
        }
    if (widthOffset + 8 > end) return null
    buffer.position(widthOffset)
    val width = (buffer.int ushr 16) and 0xffff
    val height = (buffer.int ushr 16) and 0xffff
    if (width <= 0 || height <= 0) return null
    return width to height
}

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_X = 400
private const val WINDOW_Y = 280
private const val NAMED_WINDOW_WIDTH = 320
private const val NAMED_WINDOW_HEIGHT = 180
private const val NAMED_WINDOW_X = 48
private const val NAMED_WINDOW_Y = 48
private const val NAMED_WINDOW_TITLE = "Spectre · x11 window smoke"
private const val MISSING_WINDOW_TITLE = "spectre-no-such-window-513"
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
private const val RECORD_DURATION_MS = 3_000L
private const val NAMED_WINDOW_RECORD_MS = 2_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 48
private const val VISIBLE_FRAME_TIMEOUT_MS = 5_000L
private const val VISIBLE_FRAME_POLL_MS = 100L
private const val NANOS_PER_MILLI = 1_000_000L
private const val COLOR_MASK = 0xff
private const val MIN_VISIBLE_RGB_SUM = 60
private const val MIN_VISIBLE_PIXEL_DIVISOR = 20
private const val DECORATION_TOLERANCE_PX = 96
private const val BOX_HEADER_SIZE = 8
private const val TKHD_MIN_VERSION0_SIZE = 84
private const val TKHD_V0_WIDTH_OFFSET = 76
private const val TKHD_V1_WIDTH_OFFSET = 88

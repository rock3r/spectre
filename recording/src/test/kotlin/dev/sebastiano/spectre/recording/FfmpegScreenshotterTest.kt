package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmpegScreenshotterTest {

    @Test
    fun `window screenshotter spawns one-frame gdigrab title capture`() {
        val factory = ScreenshotProcessFactory()
        val screenshotter =
            FfmpegWindowScreenshotter(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = factory,
            )

        val image = screenshotter.captureWindow(ScreenshotWindow(title = "Spectre Sample"))

        assertEquals(3, image.width)
        assertEquals(2, image.height)
        val argv = factory.lastArgv
        assertContains(argv, "gdigrab")
        assertContainsSequence(argv, listOf("-i", "title=Spectre Sample"))
        assertContainsSequence(argv, listOf("-frames:v", "1"))
        assertContainsSequence(argv, listOf("-f", "image2"))
    }

    @Test
    fun `window screenshotter rejects blank titles`() {
        val screenshotter =
            FfmpegWindowScreenshotter(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = ScreenshotProcessFactory(),
            )

        assertFailsWith<IllegalArgumentException> {
            screenshotter.captureWindow(ScreenshotWindow(title = ""))
        }
    }

    @Test
    fun `region screenshotter spawns one-frame x11grab capture`() {
        val factory = ScreenshotProcessFactory()
        val screenshotter =
            FfmpegRegionScreenshotter(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = factory,
                displayNameProvider = { ":99" },
            )

        val image = screenshotter.captureRegion(Rectangle(10, 20, 30, 40))

        assertEquals(3, image.width)
        assertEquals(2, image.height)
        val argv = factory.lastArgv
        assertContains(argv, "x11grab")
        assertContainsSequence(argv, listOf("-video_size", "30x40"))
        assertContainsSequence(argv, listOf("-i", ":99+10,20"))
        assertContainsSequence(argv, listOf("-frames:v", "1"))
    }

    @Test
    fun `region screenshotter requires a display name`() {
        val screenshotter =
            FfmpegRegionScreenshotter(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = ScreenshotProcessFactory(),
                displayNameProvider = { null },
            )

        assertFailsWith<IllegalArgumentException> {
            screenshotter.captureRegion(Rectangle(0, 0, 10, 10))
        }
    }
}

private class ScreenshotWindow(title: String?) : TitledWindow {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class ScreenshotProcessFactory : FfmpegRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        writeTestPng(Path.of(argv.last()))
        return SuccessfulScreenshotProcess()
    }
}

private class SuccessfulScreenshotProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private fun writeTestPng(output: Path) {
    val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
    assertTrue(ImageIO.write(image, "png", output.toFile()))
}

private fun assertContainsSequence(actual: List<String>, expected: List<String>) {
    val window =
        actual.windowed(size = expected.size, step = 1, partialWindows = false).firstOrNull {
            it == expected
        }
    assertTrue(window != null, "Expected argv to contain $expected, got $actual")
}

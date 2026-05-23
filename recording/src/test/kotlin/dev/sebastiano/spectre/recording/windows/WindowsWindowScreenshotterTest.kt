package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsWindowScreenshotterTest {

    @Test
    fun `captureWindow spawns helper with title and output path`() {
        val helper = Files.createTempFile("spectre-helper", ".exe")
        val factory = ScreenshotProcessFactory()
        val screenshotter =
            WindowsWindowScreenshotter(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { null },
                        targetDirProvider = { helper.parent },
                        getenv = { helper.toString() },
                        osArch = { "amd64" },
                    ),
                processFactory = factory,
            )

        val image = screenshotter.captureWindow(ScreenshotWindow(title = "Spectre Sample"))

        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertContainsSequence(
            factory.lastArgv,
            listOf(
                helper.toString(),
                "--mode",
                "screenshot",
                "--source",
                "window",
                "--title",
                "Spectre Sample",
            ),
        )
        assertContainsSequence(
            factory.lastArgv,
            listOf("--owner-pid", ProcessHandle.current().pid().toString()),
        )
        assertContains(factory.lastArgv, "--output")
    }

    @Test
    fun `captureWindow rejects blank titles before helper extraction`() {
        val screenshotter =
            WindowsWindowScreenshotter(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { error("should not extract") },
                        targetDirProvider = { Path.of(".") },
                        osArch = { "amd64" },
                    ),
                processFactory = ScreenshotProcessFactory(),
            )

        assertFailsWith<IllegalArgumentException> {
            screenshotter.captureWindow(ScreenshotWindow(title = ""))
        }
    }

    @Test
    fun `captureWindow reports helper window lookup miss`() {
        val helper = Files.createTempFile("spectre-helper", ".exe")
        val screenshotter =
            WindowsWindowScreenshotter(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { null },
                        targetDirProvider = { helper.parent },
                        getenv = { helper.toString() },
                        osArch = { "amd64" },
                    ),
                processFactory = ScreenshotProcessFactory(process = ExitingProcess(exit = 3)),
            )

        val error =
            assertFailsWith<IllegalStateException> {
                screenshotter.captureWindow(ScreenshotWindow(title = "Missing"))
            }

        assertTrue(error.message.orEmpty().contains("could not find"))
    }

    @Test
    fun `extractor copies bundled helper bytes once`() {
        val targetDir = Files.createTempDirectory("spectre-windows-helper-test")
        var opened = 0
        val extractor =
            WindowsGraphicsCaptureHelperBinaryExtractor(
                resourceLocator = {
                    opened += 1
                    ByteArrayInputStream(byteArrayOf(1, 2, 3))
                },
                targetDirProvider = { targetDir },
                getenv = { null },
                osArch = { "aarch64" },
            )

        val first = extractor.extract()
        val second = extractor.extract()

        assertEquals(first, second)
        assertEquals(1, opened)
        assertEquals("arm64", first.parent.fileName.toString())
        assertTrue(Files.readAllBytes(first).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `extractor rejects unsupported architecture`() {
        val extractor =
            WindowsGraphicsCaptureHelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(byteArrayOf(1)) },
                targetDirProvider = { Path.of(".") },
                getenv = { null },
                osArch = { "riscv64" },
            )

        val error =
            assertFailsWith<WindowsGraphicsCaptureHelperNotBundledException> { extractor.extract() }

        assertContains(error.message.orEmpty(), "Unsupported Windows architecture")
    }
}

private class ScreenshotWindow(title: String?) : TitledWindow {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class ScreenshotProcessFactory(private val process: Process = ExitingProcess(exit = 0)) :
    WindowsWindowScreenshotter.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        writeTestPng(Path.of(argv.last()))
        return process
    }
}

private class ExitingProcess(private val exit: Int) : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = exit

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

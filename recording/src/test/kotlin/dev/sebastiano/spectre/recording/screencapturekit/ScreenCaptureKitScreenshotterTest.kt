package dev.sebastiano.spectre.recording.screencapturekit

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

class ScreenCaptureKitScreenshotterTest {

    @Test
    fun `captureWindow does not mutate a uniquely titled AWT window`() {
        val factory = ScreenshotHelperProcessFactory()
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor =
                    HelperBinaryExtractor(
                        envLookup = { null },
                        sysPropLookup = { null },
                        materialLocator = {
                            helperAppBundleMaterial(byteArrayOf(0x01), byteArrayOf())
                        },
                        targetDirProvider = { Path.of("/tmp") },
                    ),
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window =
            object : TitledWindow, TitleDiscriminationAware {
                override var title: String? = "Unique title"
                override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
                override val requiresTitleDiscriminator: Boolean = false
            }

        screenshotter.captureWindow(window, 4242L)

        assertEquals("Unique title", window.title)
        assertContainsSequence(factory.lastArgv, listOf("--title-contains", "Unique title"))
    }

    @Test
    fun `captureWindow selects an untitled window by its frame in points`() {
        val factory = ScreenshotHelperProcessFactory()
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor =
                    HelperBinaryExtractor(
                        envLookup = { null },
                        sysPropLookup = { null },
                        materialLocator = {
                            helperAppBundleMaterial(byteArrayOf(0x01), byteArrayOf())
                        },
                        targetDirProvider = { Path.of("/tmp") },
                    ),
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window =
            FramedWindow(
                logicalFrame = Rectangle(-10, 20, 300, 200),
                deviceFrame = Rectangle(-20, 40, 600, 400),
            )

        screenshotter.captureWindow(window, 4242L)

        assertContainsSequence(factory.lastArgv, listOf("--frame", "-10,20,300,200"))
        assertContainsSequence(factory.lastArgv, listOf("--pid", "4242"))
        assertTrue("--title-contains" !in factory.lastArgv, "argv: ${factory.lastArgv}")
    }

    @Test
    fun `captureWindow explains a frame that matches several windows`() {
        val factory =
            ScreenshotHelperProcessFactory(process = SuccessfulScreenshotHelperProcess(exit = 7))
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor =
                    HelperBinaryExtractor(
                        envLookup = { null },
                        sysPropLookup = { null },
                        materialLocator = {
                            helperAppBundleMaterial(byteArrayOf(0x01), byteArrayOf())
                        },
                        targetDirProvider = { Path.of("/tmp") },
                    ),
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window =
            FramedWindow(
                logicalFrame = Rectangle(0, 0, 300, 200),
                deviceFrame = Rectangle(0, 0, 600, 400),
            )

        val error =
            assertFailsWith<IllegalStateException> { screenshotter.captureWindow(window, 4242L) }

        assertTrue(error.message.orEmpty().contains("more than one window"), error.message)
    }

    @Test
    fun `captureWindow spawns helper in screenshot mode and restores title`() {
        val factory = ScreenshotHelperProcessFactory()
        val extractor =
            HelperBinaryExtractor(
                envLookup = { null },
                sysPropLookup = { null },
                materialLocator = {
                    helperAppBundleMaterial(
                        executable = byteArrayOf(0x01),
                        infoPlist = byteArrayOf(),
                    )
                },
                targetDirProvider = { Path.of("/tmp") },
            )
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor = extractor,
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window = ScreenshotFakeTitledWindow(initialTitle = "MyApp")

        val image = screenshotter.captureWindow(window = window, windowOwnerPid = 4242L)

        assertEquals(4, image.width)
        assertEquals(3, image.height)
        assertEquals("MyApp", window.title)
        val argv = factory.lastArgv
        assertContainsSequence(argv, listOf("--mode", "screenshot"))
        assertContainsSequence(argv, listOf("--pid", "4242"))
        assertContains(argv, "--title-contains")
        assertContainsSequence(argv, listOf("--cursor", "false"))
        assertEquals(
            "png",
            Path.of(argv[argv.indexOf("--output") + 1]).fileName.toString().substringAfterLast('.'),
        )
    }

    @Test
    fun `captureWindow destroys helper when wait is interrupted and restores title`() {
        val process = InterruptingScreenshotHelperProcess()
        val factory = ScreenshotHelperProcessFactory(process)
        val extractor =
            HelperBinaryExtractor(
                envLookup = { null },
                sysPropLookup = { null },
                materialLocator = {
                    helperAppBundleMaterial(
                        executable = byteArrayOf(0x01),
                        infoPlist = byteArrayOf(),
                    )
                },
                targetDirProvider = { Path.of("/tmp") },
            )
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor = extractor,
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window = ScreenshotFakeTitledWindow(initialTitle = "MyApp")

        try {
            assertFailsWith<InterruptedException> {
                screenshotter.captureWindow(window = window, windowOwnerPid = 4242L)
            }
            assertTrue(process.destroyedForcibly)
            assertEquals("MyApp", window.title)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `captureWindow allows discovery and first-frame budget before timing out`() {
        val process = TimedOutScreenshotHelperProcess()
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor =
                    HelperBinaryExtractor(
                        envLookup = { null },
                        sysPropLookup = { null },
                        materialLocator = {
                            helperAppBundleMaterial(
                                executable = byteArrayOf(0x01),
                                infoPlist = byteArrayOf(),
                            )
                        },
                        targetDirProvider = { Path.of("/tmp") },
                    ),
                processFactory = ScreenshotHelperProcessFactory(process),
                requireScreenCaptureAccess = {},
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                screenshotter.captureWindow(ScreenshotFakeTitledWindow("MyApp"), 4242L)
            }

        assertEquals(listOf(15L, 1L), process.waitTimeoutSeconds)
        assertTrue(process.destroyedForcibly)
        assertContains(failure.message.orEmpty(), "timed out after 15s")
    }
}

private class ScreenshotFakeTitledWindow(initialTitle: String?) : TitledWindow {
    override var title: String? = initialTitle
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class ScreenshotHelperProcessFactory(
    private val process: Process = SuccessfulScreenshotHelperProcess()
) : ScreenCaptureKitRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        val output = Path.of(argv[argv.indexOf("--output") + 1])
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        assertTrue(ImageIO.write(image, "png", output.toFile()))
        return process
    }
}

private class SuccessfulScreenshotHelperProcess(private val exit: Int = 0) : Process() {
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

private class InterruptingScreenshotHelperProcess : Process() {
    var destroyedForcibly: Boolean = false
        private set

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        throw InterruptedException("interrupted")
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        throw InterruptedException("interrupted")
    }

    override fun exitValue(): Int = throw IllegalThreadStateException("still running")

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
        destroyedForcibly = true
        return this
    }

    override fun isAlive(): Boolean = !destroyedForcibly

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private class TimedOutScreenshotHelperProcess : Process() {
    var destroyedForcibly: Boolean = false
        private set

    val waitTimeoutSeconds: MutableList<Long> = mutableListOf()

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = throw IllegalThreadStateException("still running")

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitTimeoutSeconds += unit.toSeconds(timeout)
        return destroyedForcibly
    }

    override fun exitValue(): Int = throw IllegalThreadStateException("still running")

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
        destroyedForcibly = true
        return this
    }

    override fun isAlive(): Boolean = !destroyedForcibly

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private fun assertContainsSequence(actual: List<String>, expected: List<String>) {
    val window =
        actual.windowed(size = expected.size, step = 1, partialWindows = false).firstOrNull {
            it == expected
        }
    assertTrue(window != null, "Expected argv to contain $expected, got $actual")
}

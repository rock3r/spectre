package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxNativeScreenshotterTest {

    @Test
    fun `captureRegion destroys helper when command write fails`() {
        val tempDir = Files.createTempDirectory("spectre-linux-screenshotter-test-")
        val process = FakeScreenshotHelperProcess(stdin = ThrowingOutputStream())
        val screenshotter = linuxScreenshotter(tempDir, process)

        try {
            assertFailsWith<IllegalStateException> {
                screenshotter.captureRegion(Rectangle(0, 0, 10, 10))
            }

            assertTrue(process.destroyed, "failed command writes must not leak the helper")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `captureRegion destroys helper when stdout event is malformed`() {
        val tempDir = Files.createTempDirectory("spectre-linux-screenshotter-test-")
        val process = FakeScreenshotHelperProcess(stdout = "not-json\n".byteInputStream())
        val screenshotter = linuxScreenshotter(tempDir, process)

        try {
            assertFailsWith<RuntimeException> {
                screenshotter.captureRegion(Rectangle(0, 0, 10, 10))
            }

            assertTrue(process.destroyed, "malformed helper events must not leak the helper")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `X11 captureWindow selects an untitled window by its device-pixel frame`() {
        val tempDir = Files.createTempDirectory("spectre-linux-screenshotter-test-")
        val stdin = ByteArrayOutputStream()
        val process =
            FakeScreenshotHelperProcess(stdin = stdin, stdout = "not-json\n".byteInputStream())
        val screenshotter = linuxScreenshotter(tempDir, process)
        val window =
            FramedWindow(
                logicalFrame = Rectangle(10, 20, 300, 200),
                deviceFrame = Rectangle(20, 40, 600, 400),
            )

        try {
            assertFailsWith<RuntimeException> { screenshotter.captureWindow(window, 4242L) }

            val command = stdin.toString(Charsets.UTF_8)
            assertTrue(
                command.contains(
                    "\"window_frame\":{\"x\":20,\"y\":40,\"width\":600,\"height\":400}"
                ),
                command,
            )
            assertTrue(command.contains("\"window_owner_pid\":4242"), command)
            assertTrue(command.contains("\"target\":\"window\""), command)
            assertTrue(!command.contains("window_title"), command)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Wayland captureWindow refuses an untitled window before starting the helper`() {
        val tempDir = Files.createTempDirectory("spectre-linux-screenshotter-test-")
        val screenshotter =
            linuxScreenshotter(
                tempDir,
                processFactory =
                    object : LinuxNativeScreenshotter.ProcessFactory {
                        override fun start(helperPath: Path): Process =
                            error("the helper must not start for an untitled Wayland window")
                    },
                isWayland = true,
            )
        val window =
            FramedWindow(
                logicalFrame = Rectangle(10, 20, 300, 200),
                deviceFrame = Rectangle(20, 40, 600, 400),
            )

        try {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    screenshotter.captureWindow(window, 4242L)
                }

            assertTrue(error.message.orEmpty().contains("Wayland"), error.message)
            assertTrue(error.message.orEmpty().contains("untitled"), error.message)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun linuxScreenshotter(tempDir: Path, process: Process): LinuxNativeScreenshotter =
        linuxScreenshotter(
            tempDir,
            processFactory =
                object : LinuxNativeScreenshotter.ProcessFactory {
                    override fun start(helperPath: Path): Process = process
                },
        )

    private fun linuxScreenshotter(
        tempDir: Path,
        processFactory: LinuxNativeScreenshotter.ProcessFactory,
        isWayland: Boolean = false,
    ): LinuxNativeScreenshotter =
        LinuxNativeScreenshotter(
            helperExtractor =
                WaylandHelperBinaryExtractor(
                    envLookup = { null },
                    resourceLocator = { ByteArrayInputStream(byteArrayOf(0x7f)) },
                    targetDirProvider = { tempDir },
                    archProvider = { "x86_64" },
                ),
            processFactory = processFactory,
            isWayland = { isWayland },
            displayNameProvider = { ":99" },
            frameExtentsLookup = { null },
        )
}

private class ThrowingOutputStream : OutputStream() {
    override fun write(b: Int) {
        throw IOException("simulated EPIPE")
    }
}

private class FakeScreenshotHelperProcess(
    private val stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
    private val stdin: OutputStream = ByteArrayOutputStream(),
    private val exit: Int = 1,
) : Process() {

    var destroyed: Boolean = false
        private set

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = exit

    override fun destroy() {
        destroyed = true
    }

    override fun destroyForcibly(): Process {
        destroyed = true
        stdout.close()
        return this
    }

    override fun isAlive(): Boolean = !destroyed

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

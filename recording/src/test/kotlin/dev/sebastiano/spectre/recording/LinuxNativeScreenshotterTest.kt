package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor
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

    private fun linuxScreenshotter(tempDir: Path, process: Process): LinuxNativeScreenshotter =
        LinuxNativeScreenshotter(
            helperExtractor =
                WaylandHelperBinaryExtractor(
                    envLookup = { null },
                    resourceLocator = { ByteArrayInputStream(byteArrayOf(0x7f)) },
                    targetDirProvider = { tempDir },
                    archProvider = { "x86_64" },
                ),
            processFactory =
                object : LinuxNativeScreenshotter.ProcessFactory {
                    override fun start(helperPath: Path): Process = process
                },
            isWayland = { false },
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

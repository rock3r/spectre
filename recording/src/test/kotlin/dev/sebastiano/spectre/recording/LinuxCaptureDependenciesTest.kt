package dev.sebastiano.spectre.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxCaptureDependenciesTest {

    @Test
    fun `gst-launch probe is true when the version process exits zero`() {
        assertTrue(LinuxCaptureDependencies.isGstLaunchAvailable { FakeGstLaunchProcess(exit = 0) })
    }

    @Test
    fun `gst-launch probe is false when the binary cannot start`() {
        assertFalse(
            LinuxCaptureDependencies.isGstLaunchAvailable {
                throw IOException("Cannot run program \"gst-launch-1.0\"")
            }
        )
    }

    @Test
    fun `gst-launch probe is false when the version process exits non-zero`() {
        assertFalse(
            LinuxCaptureDependencies.isGstLaunchAvailable { FakeGstLaunchProcess(exit = 127) }
        )
    }

    @Test
    fun `gst-launch probe is false when the version process hangs`() {
        val process = FakeGstLaunchProcess(exit = 0, finished = false)
        assertFalse(LinuxCaptureDependencies.isGstLaunchAvailable { process })
        assertTrue(process.destroyed, "a hung gst-launch --version probe must be destroyed")
    }
}

private class FakeGstLaunchProcess(private val exit: Int, private val finished: Boolean = true) :
    Process() {

    var destroyed: Boolean = false
        private set

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished

    override fun exitValue(): Int = exit

    override fun destroy() {
        destroyed = true
    }

    override fun destroyForcibly(): Process {
        destroyed = true
        return this
    }

    override fun isAlive(): Boolean = !destroyed && !finished

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

package dev.sebastiano.spectre.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxCaptureDependenciesTest {

    @Test
    fun `gst-launch probe is true when the version process exits zero`() {
        assertEquals(
            true,
            LinuxCaptureDependencies.isGstLaunchAvailable { FakeGstLaunchProcess(exit = 0) },
        )
    }

    @Test
    fun `gst-launch probe is false when the binary cannot start`() {
        assertEquals(
            false,
            LinuxCaptureDependencies.isGstLaunchAvailable {
                throw IOException("Cannot run program \"gst-launch-1.0\"")
            },
        )
    }

    @Test
    fun `gst-launch probe is false when the version process exits non-zero`() {
        assertEquals(
            false,
            LinuxCaptureDependencies.isGstLaunchAvailable { FakeGstLaunchProcess(exit = 127) },
        )
    }

    @Test
    fun `gst-launch probe timeout is inconclusive not missing`() {
        val process = FakeGstLaunchProcess(exit = 0, finished = false)
        assertNull(
            LinuxCaptureDependencies.isGstLaunchAvailable { process },
            "a slow gst-launch --version must not be cached as unavailable",
        )
        assertTrue(process.destroyed, "a hung gst-launch --version probe must be destroyed")
    }

    @Test
    fun `gst-launch probe does not treat interruption as missing binary`() {
        val process = FakeGstLaunchProcess(exit = 0, interruptWait = true)
        assertFailsWith<InterruptedException> {
            LinuxCaptureDependencies.isGstLaunchAvailable { process }
        }
        assertTrue(process.destroyed, "an interrupted gst-launch probe must still be destroyed")
        assertTrue(Thread.interrupted(), "interrupt status must be restored so the wait can abort")
        assertEquals(
            true,
            LinuxCaptureDependencies.isGstLaunchAvailable { FakeGstLaunchProcess(exit = 0) },
            "a later probe must be allowed to succeed after an interrupted one",
        )
    }
}

private class FakeGstLaunchProcess(
    private val exit: Int,
    private val finished: Boolean = true,
    private val interruptWait: Boolean = false,
) : Process() {

    var destroyed: Boolean = false
        private set

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (interruptWait) throw InterruptedException("gst-launch probe cancelled")
        return finished
    }

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

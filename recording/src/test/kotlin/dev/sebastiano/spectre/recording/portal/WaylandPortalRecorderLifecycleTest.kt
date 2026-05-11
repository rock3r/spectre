package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fake-process lifecycle coverage for [WaylandPortalRecorder]. The recorder talks to the helper
 * over stdin/stdout via newline-delimited JSON; this test stands up a [FakeHelperProcess] that lets
 * the test write events and observe the recorder's stop/force-kill behaviour without ever exec'ing
 * the real Rust helper binary.
 *
 * Every test that exercises a failed-start path asserts both `destroyForcibly()` and `waitFor`
 * actually ran — the recorder's contract is that a failed start reaps the helper before propagating
 * the exception, not just that it called `destroyForcibly()`.
 */
class WaylandPortalRecorderLifecycleTest {

    private val tempOutputs = mutableListOf<Path>()
    private val helperTempDir: Path = Files.createTempDirectory("spectre-r2-helper")

    @AfterTest
    fun cleanup() {
        tempOutputs.forEach { it.deleteIfExists() }
        helperTempDir.toFile().deleteRecursively()
    }

    @Test
    fun `start happy path returns a handle when helper emits Started`() {
        val fake = FakeHelperProcess()
        val recorder = recorder(fake)
        val handle = fake.runStartConcurrently { recorder.start(REGION, output(), OPTIONS) }

        // Recorder waited for Started; on receipt, returned a usable handle. The reader thread
        // is still running, the process is still alive.
        assertTrue(handle.output.toString().isNotBlank())
        assertEquals(false, fake.destroyForciblyCalled.get())
        assertEquals(true, fake.isAlive())
        // Stop the recording so the test doesn't leave a thread/process running.
        fake.runStopConcurrently { handle.stop() }
    }

    @Test
    fun `start fails clearly when helper emits Error before Started`() {
        val fake = FakeHelperProcess()
        val recorder = recorder(fake)
        val error =
            assertFailsWith<IllegalStateException> {
                fake.runStartWithError { recorder.start(REGION, output(), OPTIONS) }
            }
        assertTrue(
            error.message!!.contains("reported an error before recording could start"),
            "wrong error message: ${error.message}",
        )
        assertReapedAfterFailedStart(fake)
    }

    @Test
    fun `start fails when helper closes stdout before Started`() {
        val fake = FakeHelperProcess()
        val recorder = recorder(fake)
        val error =
            assertFailsWith<IllegalStateException> {
                fake.runStartWithEofBeforeStarted { recorder.start(REGION, output(), OPTIONS) }
            }
        assertTrue(
            error.message!!.contains("reported an error before recording could start"),
            "wrong error message: ${error.message}",
        )
        assertReapedAfterFailedStart(fake)
    }

    @Test
    fun `start fails when helper writes nothing within startedTimeout`() {
        val fake = FakeHelperProcess()
        // 50ms timeout — the fake helper writes nothing, so the recorder must time out.
        val recorder = recorder(fake, startedTimeout = 50L)
        val error =
            assertFailsWith<IllegalStateException> { recorder.start(REGION, output(), OPTIONS) }
        assertTrue(
            error.message!!.contains("did not emit a Started or Error event within 50ms"),
            "wrong error message: ${error.message}",
        )
        assertReapedAfterFailedStart(fake)
    }

    @Test
    fun `stop happy path waits for Stopped and validates exit code`() {
        val fake = FakeHelperProcess()
        val recorder = recorder(fake)
        val handle = fake.runStartConcurrently { recorder.start(REGION, output(), OPTIONS) }
        fake.runStopConcurrently { handle.stop() }
        assertEquals(true, handle.isStopped)
        assertEquals(false, fake.destroyForciblyCalled.get(), "graceful stop must not force-kill")
        assertEquals(false, fake.isAlive())
    }

    @Test
    fun `stop force-kills and reaps when helper ignores the Stop command`() {
        val fake = FakeHelperProcess()
        // 0s grace so the test doesn't wait the production 30s. Inject via the internal seam
        // — production callers can't reach this constant.
        val recorder = recorder(fake, shutdownGraceMillis = 0L)
        val handle = fake.runStartConcurrently { recorder.start(REGION, output(), OPTIONS) }

        // Don't call completeStop() — the fake helper stays alive and silent. Recorder must
        // hit the grace timeout, force-kill, reap, and throw a clear error.
        val error = assertFailsWith<IllegalStateException> { handle.stop() }
        assertTrue(
            error.message!!.contains("did not emit Stopped within"),
            "wrong error message: ${error.message}",
        )
        assertEquals(true, fake.destroyForciblyCalled.get(), "stop timeout must force-kill")
        assertEquals(false, fake.isAlive(), "process must be reaped before stop() returns")
    }

    @Test
    fun `stop is idempotent`() {
        val fake = FakeHelperProcess()
        val recorder = recorder(fake)
        val handle = fake.runStartConcurrently { recorder.start(REGION, output(), OPTIONS) }
        fake.runStopConcurrently { handle.stop() }
        // Second stop is a no-op; should not re-touch the (already exited) fake process.
        handle.stop()
        assertEquals(true, handle.isStopped)
    }

    private fun output(): Path = Files.createTempFile("spectre-r2", ".mp4").also(tempOutputs::add)

    private fun recorder(
        fake: FakeHelperProcess,
        startedTimeout: Long = 5_000L,
        shutdownGraceMillis: Long = 5_000L,
        processExitTimeoutMillis: Long = 5_000L,
    ): WaylandPortalRecorder =
        WaylandPortalRecorder.createForInternalUse(
            helperExtractor = fakeHelperBinaryExtractor(helperTempDir),
            processFactory = FakeProcessFactory(fake),
            sourceTypes = listOf(SourceType.MONITOR),
            startedTimeout = startedTimeout,
            shutdownGraceMillis = shutdownGraceMillis,
            processExitTimeoutMillis = processExitTimeoutMillis,
        )

    private fun assertReapedAfterFailedStart(fake: FakeHelperProcess) {
        assertEquals(
            true,
            fake.destroyForciblyCalled.get(),
            "failed start must call destroyForcibly()",
        )
        assertEquals(true, fake.waitForCalled.get(), "failed start must waitFor after force-kill")
        assertEquals(false, fake.isAlive(), "failed start must reap the process before throwing")
    }

    private companion object {
        val REGION: Rectangle = Rectangle(0, 0, 100, 100)
        val OPTIONS: RecordingOptions = RecordingOptions()
    }
}

/**
 * Builds a real [WaylandHelperBinaryExtractor] whose classpath lookup returns an empty
 * `InputStream`. `extract()` then copies that empty stream to a temp file, marks it executable, and
 * returns the path — enough to satisfy the recorder, without needing the actual Rust helper binary
 * on the classpath.
 */
private fun fakeHelperBinaryExtractor(tempDir: Path): WaylandHelperBinaryExtractor =
    WaylandHelperBinaryExtractor(
        envLookup = { null },
        resourceLocator = { ByteArrayInputStream(ByteArray(0)) },
        targetDirProvider = { tempDir },
        archProvider = { "x86_64" },
    )

private class FakeProcessFactory(private val process: FakeHelperProcess) :
    WaylandPortalRecorder.ProcessFactory {
    override fun start(helperPath: Path): Process = process
}

/**
 * A [Process] stand-in that the test drives directly: events go through [emit]; stop is observed
 * via [waitForStopCommand]; reaping is exposed via [destroyForciblyCalled] / [waitForCalled].
 *
 * Writes Started/Stopped/Error JSON to its stdout pipe via [emit] so the recorder's reader thread
 * parses real protocol events.
 */
private class FakeHelperProcess : Process() {

    /**
     * Stdout buffer that the recorder's reader thread reads from. Backed by a blocking queue of
     * bytes (one per chunk) and an EOF sentinel — this avoids `PipedOutputStream`'s requirement
     * that the writer thread stay alive, which would crash the reader the moment a short-lived
     * emitter daemon thread exited.
     */
    private val stdoutQueue = LinkedBlockingQueue<ByteArray>()
    private val helperStdout: InputStream =
        object : InputStream() {
            private var current: ByteArray? = null
            private var offset: Int = 0

            override fun read(): Int {
                while (true) {
                    val c = current
                    if (c != null && offset < c.size) {
                        return c[offset++].toInt() and 0xff
                    }
                    val next = stdoutQueue.take()
                    if (next.isEmpty()) return -1 // EOF sentinel
                    current = next
                    offset = 0
                }
            }

            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                // Critical override: java.io.InputStream's default vectored read blocks the
                // BufferedReader's fill() in a per-byte loop until it has `len` bytes or EOF.
                // With our LinkedBlockingQueue cadence, that means BufferedReader keeps
                // calling our read() past the end of the current chunk, hangs on take(), and
                // never returns the partial line it has buffered — so the recorder's
                // startedLatch never fires. Drain only what's immediately available after the
                // first byte: the caller (StreamDecoder) gets the newline-terminated chunk
                // and readLine() can return.
                if (len == 0) return 0
                val first = read()
                if (first == -1) return -1
                buf[off] = first.toByte()
                var i = 1
                val cached = current
                if (cached != null) {
                    val drain = minOf(len - i, cached.size - offset)
                    if (drain > 0) {
                        System.arraycopy(cached, offset, buf, off + i, drain)
                        offset += drain
                        i += drain
                    }
                }
                return i
            }
        }
    private val helperStdin = ByteArrayOutputStream()
    private val helperStderr = ByteArrayInputStream(ByteArray(0))
    private val terminated = AtomicBoolean(false)
    private val terminationLatch = CountDownLatch(1)
    private val stopCommandLatch = CountDownLatch(1)

    val destroyForciblyCalled = AtomicBoolean(false)
    val waitForCalled = AtomicBoolean(false)

    override fun getOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                helperStdin.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                helperStdin.write(b, off, len)
            }

            override fun close() {
                // The recorder closes stdin after writing its Stop command. The helper
                // protocol explicitly treats stdin EOF as a Stop signal (belt-and-braces for
                // buffered writes), so this is the right cue regardless of whether the
                // bulk-write happened to deliver the JSON line in one chunk or many.
                stopCommandLatch.countDown()
            }
        }

    override fun getInputStream(): InputStream = helperStdout

    override fun getErrorStream(): InputStream = helperStderr

    override fun waitFor(): Int {
        waitForCalled.set(true)
        terminationLatch.await()
        return EXIT_OK
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitForCalled.set(true)
        return terminationLatch.await(timeout, unit)
    }

    override fun exitValue(): Int {
        if (!terminated.get()) throw IllegalThreadStateException("fake helper still alive")
        return EXIT_OK
    }

    override fun isAlive(): Boolean = !terminated.get()

    override fun destroy() {
        markTerminated()
    }

    override fun destroyForcibly(): Process {
        destroyForciblyCalled.set(true)
        markTerminated()
        return this
    }

    private fun markTerminated() {
        if (terminated.compareAndSet(false, true)) {
            terminationLatch.countDown()
            // Push EOF sentinel so the recorder's reader thread sees EOF and exits the loop.
            stdoutQueue.add(ByteArray(0))
        }
    }

    /**
     * Run [block] (the recorder's `start(...)`) on the calling thread while another thread emits a
     * `Started` event so `start()` can return. Returns the resulting handle.
     */
    fun <T> runStartConcurrently(block: () -> T): T {
        val emitter =
            Thread({
                Thread.sleep(EVENT_EMIT_DELAY_MS)
                emit(STARTED_EVENT)
            })
        emitter.isDaemon = true
        emitter.start()
        return block()
    }

    /** Emits an Error event so the recorder's start fails. */
    fun <T> runStartWithError(block: () -> T): T {
        val emitter =
            Thread({
                Thread.sleep(EVENT_EMIT_DELAY_MS)
                emit(ERROR_EVENT)
            })
        emitter.isDaemon = true
        emitter.start()
        return block()
    }

    /**
     * Closes stdout without emitting Started — recorder synthesises an EOF-before-Started error.
     */
    fun <T> runStartWithEofBeforeStarted(block: () -> T): T {
        val emitter =
            Thread({
                Thread.sleep(EVENT_EMIT_DELAY_MS)
                // Push EOF without marking the process terminated yet — the recorder's
                // failed-start path is what's expected to reap it.
                stdoutQueue.add(ByteArray(0))
            })
        emitter.isDaemon = true
        emitter.start()
        return block()
    }

    /**
     * Runs [block] (the caller's `handle.stop()`) on the calling thread while a daemon thread waits
     * for the recorder's Stop command, then emits a Stopped event and marks the fake process
     * terminated. The two have to overlap because `handle.stop()` blocks on the stoppedLatch until
     * our emit fires; calling `completeStop()` *before* `handle.stop()` deadlocks waiting for a
     * Stop command the recorder hasn't sent yet.
     */
    fun <T> runStopConcurrently(block: () -> T): T {
        val stopper =
            Thread({
                if (stopCommandLatch.await(STOP_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    emit(STOPPED_EVENT)
                    markTerminated()
                }
            })
        stopper.isDaemon = true
        stopper.start()
        return block().also { stopper.join() }
    }

    private fun emit(line: String) {
        stdoutQueue.add((line + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        const val EVENT_EMIT_DELAY_MS: Long = 25
        const val STOP_WAIT_TIMEOUT_MS: Long = 5_000
        const val EXIT_OK: Int = 0
        const val STARTED_EVENT: String =
            "{\"event\":\"started\",\"node_id\":42,\"stream_size\":[100,100]," +
                "\"stream_position\":[0,0],\"gst_pid\":1}"
        const val ERROR_EVENT: String =
            "{\"event\":\"error\",\"kind\":\"PortalRejected\",\"message\":\"user dismissed\"}"
        const val STOPPED_EVENT: String = "{\"event\":\"stopped\",\"output_size_bytes\":1024}"
    }
}

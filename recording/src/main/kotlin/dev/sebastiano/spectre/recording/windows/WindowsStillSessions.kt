package dev.sebastiano.spectre.recording.windows

import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Keeps one `spectre-window-capture --mode stills` helper per capture target between stills (#612).
 *
 * Starting the helper and a Windows Graphics Capture session takes a few hundred milliseconds.
 * Visual-idle sampling takes many stills of the same window in a row, so paying that per still made
 * each sample take about 0.4 to 0.7 s, longer than a caret blink. An open session answers a still
 * in one frame copy.
 *
 * Sessions are keyed by the helper argv, which holds the window selector and owner pid. The helper
 * reports a still as stale when its window no longer matches that selector; the session is then
 * replaced once. A session closes after [idleCloseMillis] without a still, and when the JVM exits
 * (the helper stops at the end of its stdin).
 */
internal class WindowsStillSessions(
    private val idleCloseMillis: Long = DEFAULT_IDLE_CLOSE_MILLIS,
    private val readyTimeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS,
    private val frameTimeoutMillis: Long = DEFAULT_FRAME_TIMEOUT_MILLIS,
) {
    private val sessions = HashMap<List<String>, WindowsStillSession>()
    private val idleCloser =
        ScheduledThreadPoolExecutor(1, daemonThreads("spectre-wgc-stills-idle")).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(idleCloseMillis.coerceAtLeast(1) * 2, TimeUnit.MILLISECONDS)
            allowCoreThreadTimeOut(true)
        }

    /** Returns a still of the window that [argv] selects. [start] launches a new helper. */
    fun capture(argv: List<String>, start: (List<String>) -> Process): BufferedImage {
        repeat(STALE_RETRIES + 1) {
            val session = sessionFor(argv, start)
            val still =
                try {
                    session.requestStill(frameTimeoutMillis)
                } catch (e: IllegalStateException) {
                    discard(argv, session)
                    throw e
                }
            when (still) {
                is Still.Frame -> {
                    scheduleIdleClose(argv, session)
                    return still.image
                }
                is Still.Stale -> discard(argv, session)
            }
        }
        error(
            "spectre-window-capture could not keep a capture session on the target window: " +
                "it changed again after a new session started. Argv: $argv"
        )
    }

    private fun sessionFor(
        argv: List<String>,
        start: (List<String>) -> Process,
    ): WindowsStillSession {
        synchronized(sessions) {
            sessions[argv]
                ?.takeIf { it.isAlive }
                ?.let {
                    return it
                }
        }
        val started = WindowsStillSession.start(argv, start, readyTimeoutMillis)
        synchronized(sessions) {
            val raced = sessions[argv]?.takeIf { it.isAlive }
            if (raced != null) {
                started.close()
                return raced
            }
            sessions[argv] = started
        }
        return started
    }

    private fun discard(argv: List<String>, session: WindowsStillSession) {
        synchronized(sessions) { if (sessions[argv] === session) sessions.remove(argv) }
        session.close()
    }

    private fun scheduleIdleClose(argv: List<String>, session: WindowsStillSession) {
        session.replaceIdleClose(
            idleCloser.schedule({ discard(argv, session) }, idleCloseMillis, TimeUnit.MILLISECONDS)
        )
    }

    internal companion object {
        const val DEFAULT_IDLE_CLOSE_MILLIS: Long = 2_000
        // Covers helper startup, window lookup, and the helper's 5 s first-frame deadline.
        const val DEFAULT_READY_TIMEOUT_MILLIS: Long = 10_000
        const val DEFAULT_FRAME_TIMEOUT_MILLIS: Long = 5_000
        private const val STALE_RETRIES: Int = 1
    }
}

internal sealed interface Still {
    class Frame(val image: BufferedImage) : Still

    class Stale(val reason: String) : Still
}

/** One running `--mode stills` helper. Not thread-safe across stills; callers serialise. */
internal class WindowsStillSession
private constructor(private val process: Process, private val argv: List<String>) {
    private val stdout = BufferedInputStream(process.inputStream, STDOUT_BUFFER_BYTES)
    private val lock = Any()
    private var idleClose: ScheduledFuture<*>? = null
    private var closed = false

    val isAlive: Boolean
        get() = process.isAlive

    fun requestStill(timeoutMillis: Long): Still =
        synchronized(lock) {
            // The idle closer may have closed this session after the caller looked it up.
            if (closed) return Still.Stale("the session was closed while idle")
            try {
                process.outputStream.write(CAPTURE_COMMAND)
                process.outputStream.flush()
            } catch (e: IOException) {
                throw failure("spectre-window-capture stopped before it returned a still.", e)
            }
            awaitOrAbort(timeoutMillis, "return a still") { readStill() }
        }

    fun replaceIdleClose(next: ScheduledFuture<*>) {
        synchronized(lock) {
            idleClose?.cancel(false)
            idleClose = next
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            idleClose?.cancel(false)
            runCatching {
                process.outputStream.write(QUIT_COMMAND)
                process.outputStream.close()
            }
        }
        val exited = runCatching { process.waitFor(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
        if (exited.getOrDefault(false) != true) process.destroyForcibly()
    }

    private fun readStill(): Still {
        val header =
            readAsciiLine(stdout)
                ?: throw failure("spectre-window-capture stopped before it returned a still.")
        val parts = header.split(' ', limit = 3)
        return when (parts.first()) {
            "FRAME" -> {
                val width = parts.getOrNull(1)?.toIntOrNull()
                val height = parts.getOrNull(2)?.toIntOrNull()
                check(width != null && height != null && width > 0 && height > 0) {
                    "spectre-window-capture sent a malformed still header \"$header\". Argv: $argv"
                }
                val pixels = stdout.readNBytes(width * height * BYTES_PER_PIXEL)
                if (pixels.size != width * height * BYTES_PER_PIXEL) {
                    throw failure("spectre-window-capture stopped in the middle of a still.")
                }
                Still.Frame(premultipliedBgraToImage(pixels, width, height))
            }
            "STALE" -> Still.Stale(header.removePrefix("STALE").trim())
            else -> error("spectre-window-capture sent an unexpected line \"$header\". Argv: $argv")
        }
    }

    private fun <T> awaitOrAbort(timeoutMillis: Long, action: String, read: () -> T): T {
        val pending = CompletableFuture.supplyAsync(read, readers)
        try {
            return pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            process.destroyForcibly()
            throw IllegalStateException(
                appendHelperStderr(
                    "Timed out after ${timeoutMillis}ms waiting for spectre-window-capture to " +
                        "$action. Argv: $argv",
                    helperFailureDetail(process),
                ),
                e,
            )
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun failure(message: String, cause: Throwable? = null): IllegalStateException {
        val exit =
            if (process.waitFor(EXIT_PROBE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                null
            }
        val stderr = helperFailureDetail(process)
        val detail =
            if (exit == null) "$message Argv: $argv"
            else
                messageForWindowsGraphicsCaptureHelperExit(
                    exit,
                    argv,
                    helperLaunchArgv(process),
                    stderr,
                )
        return IllegalStateException(appendHelperStderr(detail, stderr), cause)
    }

    companion object {
        fun start(
            argv: List<String>,
            launch: (List<String>) -> Process,
            readyTimeoutMillis: Long,
        ): WindowsStillSession {
            val session = WindowsStillSession(launch(argv), argv)
            val ready =
                session.awaitOrAbort(readyTimeoutMillis, "start a capture session") {
                    readAsciiLine(session.stdout)
                }
            if (ready != READY) {
                val error =
                    session.failure(
                        "spectre-window-capture did not start a capture session" +
                            if (ready == null) "." else " (first line \"$ready\")."
                    )
                session.process.destroyForcibly()
                throw error
            }
            return session
        }

        private const val READY: String = "READY"
        private val CAPTURE_COMMAND: ByteArray = "c\n".toByteArray(Charsets.US_ASCII)
        private val QUIT_COMMAND: ByteArray = "q\n".toByteArray(Charsets.US_ASCII)
        private const val BYTES_PER_PIXEL: Int = 4
        private const val STDOUT_BUFFER_BYTES: Int = 1 shl 20
        private const val EXIT_PROBE_MILLIS: Long = 200
        private const val CLOSE_WAIT_MILLIS: Long = 1_000
        private val readers = Executors.newCachedThreadPool(daemonThreads("spectre-wgc-stills"))
    }
}

/** Reads one `\n`-terminated ASCII line, or null at the end of the stream. */
private fun readAsciiLine(input: InputStream): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII)
        if (next == '\n'.code) return line.toString(Charsets.US_ASCII).trimEnd('\r')
        line.write(next)
    }
}

/**
 * Converts Windows Graphics Capture pixels (premultiplied BGRA) to straight ARGB, the same pixels
 * the one-shot helper used to write as PNG.
 */
internal fun premultipliedBgraToImage(bgra: ByteArray, width: Int, height: Int): BufferedImage {
    val argb = IntArray(width * height)
    for (i in argb.indices) {
        val offset = i * BGRA_BYTES
        val alpha = bgra.channel(offset + ALPHA)
        val red = straight(bgra.channel(offset + RED), alpha)
        val green = straight(bgra.channel(offset + GREEN), alpha)
        val blue = straight(bgra.channel(offset + BLUE), alpha)
        argb[i] = (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, width, height, argb, 0, width)
    }
}

private fun ByteArray.channel(index: Int): Int = this[index].toInt() and CHANNEL_MAX

/** Undoes alpha premultiplication. A fully transparent pixel has no colour left to recover. */
private fun straight(premultiplied: Int, alpha: Int): Int =
    when (alpha) {
        0 -> 0
        CHANNEL_MAX -> premultiplied
        else -> (premultiplied * CHANNEL_MAX / alpha).coerceAtMost(CHANNEL_MAX)
    }

private const val BGRA_BYTES: Int = 4
private const val BLUE: Int = 0
private const val GREEN: Int = 1
private const val RED: Int = 2
private const val ALPHA: Int = 3
private const val CHANNEL_MAX: Int = 0xFF
private const val ALPHA_SHIFT: Int = 24
private const val RED_SHIFT: Int = 16
private const val GREEN_SHIFT: Int = 8

private fun daemonThreads(name: String): ThreadFactory = ThreadFactory { task ->
    Thread(task, name).apply { isDaemon = true }
}

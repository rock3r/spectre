package dev.sebastiano.spectre.cli.daemon

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded daemon-owned diagnostics file; truncation keeps the live stderr inode attached. */
internal class DaemonLog(
    private val path: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val retainedBytes: Int = DEFAULT_RETAINED_BYTES,
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MILLIS,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private var observedSize: Long = -1
    private val worker =
        Thread(
                {
                    while (running.get()) {
                        try {
                            Thread.sleep(checkIntervalMillis)
                        } catch (_: InterruptedException) {
                            // close() wakes the worker; the loop condition decides whether to exit.
                        }
                        runCatching(::rotate)
                    }
                    runCatching(::rotate)
                },
                "spectre-daemon-log-${path.fileName}",
            )
            .apply {
                isDaemon = true
                start()
            }

    private fun rotate() {
        val size = runCatching { Files.size(path) }.getOrDefault(-1)
        if (size <= maxBytes) {
            observedSize = size
            return
        }
        // A detached daemon can still be appending after its launcher exits. Rotate only after a
        // complete check interval with no growth, avoiding truncate/rewrite races with that writer.
        if (size != observedSize) {
            observedSize = size
            return
        }
        rotateIfNeeded(path, maxBytes, retainedBytes)
        observedSize = runCatching { Files.size(path) }.getOrDefault(-1)
    }

    override fun close() {
        running.set(false)
        worker.interrupt()
        runCatching { worker.join(checkIntervalMillis * 2) }
    }

    companion object {
        internal fun rotateIfNeeded(path: Path, maxBytes: Long, retainedBytes: Int) {
            if (!Files.isRegularFile(path) || Files.size(path) <= maxBytes) return
            val tail = readLogTail(path, retainedBytes)
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                channel.truncate(0)
                var written = 0
                while (written < tail.size) written +=
                    channel.write(ByteBuffer.wrap(tail, written, tail.size - written))
                channel.force(false)
            }
        }

        internal fun readLogTail(path: Path, limit: Int = DEFAULT_RETAINED_BYTES): ByteArray {
            if (limit <= 0 || !Files.isRegularFile(path)) return byteArrayOf()
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                if (size == 0L) return byteArrayOf()
                val wanted = minOf(size, limit.toLong()).toInt()
                val buffer = ByteBuffer.allocate(wanted)
                var position = (size - wanted).coerceAtLeast(0)
                while (buffer.hasRemaining()) {
                    val read = channel.read(buffer, position)
                    if (read <= 0) break
                    position += read
                }
                return buffer.array().copyOf(buffer.position())
            }
        }

        private const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024
        private const val DEFAULT_RETAINED_BYTES: Int = 64 * 1024
        private const val DEFAULT_CHECK_INTERVAL_MILLIS: Long = 250
    }
}

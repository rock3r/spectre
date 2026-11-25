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

    private fun rotate() = rotateIfNeeded(path, maxBytes, retainedBytes)

    override fun close() {
        running.set(false)
        worker.interrupt()
        runCatching { worker.join(checkIntervalMillis * 2) }
    }

    companion object {
        internal fun rotateIfNeeded(path: Path, maxBytes: Long, retainedBytes: Int) {
            if (!Files.isRegularFile(path) || Files.size(path) <= maxBytes) return
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel
                ->
                val size = channel.size()
                if (size <= maxBytes) return
                var tail = readTail(channel, size, retainedBytes)
                // Refresh once if an append raced the read. Waiting for a quiet poll can starve
                // rotation indefinitely, so the threshold can be exceeded between polls.
                val latestSize = channel.size()
                if (latestSize != size) {
                    if (latestSize <= maxBytes) return
                    tail = readTail(channel, latestSize, retainedBytes)
                }
                // Move the snapshot to the front before truncating. Writers keep the same file
                // and can append normally immediately after truncate. Uncoordinated appends
                // between the snapshot and truncate can still be lost.
                val buffer = ByteBuffer.wrap(tail)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.truncate(tail.size.toLong())
                channel.force(false)
            }
        }

        internal fun readLogTail(path: Path, limit: Int = DEFAULT_RETAINED_BYTES): ByteArray {
            if (limit <= 0 || !Files.isRegularFile(path)) return byteArrayOf()
            return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                readTail(channel, channel.size(), limit)
            }
        }

        private fun readTail(channel: FileChannel, size: Long, limit: Int): ByteArray {
            val wanted = minOf(size, limit.coerceAtLeast(0).toLong()).toInt()
            val buffer = ByteBuffer.allocate(wanted)
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, size - wanted + buffer.position())
                if (read <= 0) break
            }
            return buffer.array().copyOf(buffer.position())
        }

        private const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024
        private const val DEFAULT_RETAINED_BYTES: Int = 64 * 1024
        private const val DEFAULT_CHECK_INTERVAL_MILLIS: Long = 250
    }
}

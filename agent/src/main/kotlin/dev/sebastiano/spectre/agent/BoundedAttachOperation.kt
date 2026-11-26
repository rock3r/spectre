package dev.sebastiano.spectre.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs an attach helper in a process that can be forcibly reclaimed if native attach wedges. */
internal object BoundedAttachOperation {
    private const val POLL_MS = 25L
    private const val CLEANUP_MS = 2_000L

    fun run(
        command: List<String>,
        timeoutMs: Long,
        targetAlive: () -> Boolean,
        acceptedExitCodes: Set<Int> = setOf(0),
        startProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
    ): String {
        var child: OwnedProcess? = null
        var primary: Throwable? = null
        var result: String? = null
        var interrupted = false
        val operation = runCatching {
            child = OwnedProcess(startProcess(command))
            result = await(checkNotNull(child), timeoutMs, targetAlive, acceptedExitCodes)
        }
        primary = operation.exceptionOrNull()
        if (primary is InterruptedException) {
            interrupted = true
            primary =
                SpectreAttachExceptionImpl("Bounded VirtualMachine.attach was interrupted", primary)
        }
        interrupted = interrupted or Thread.interrupted()
        val cleanupResult = runCatching { child?.close() }
        interrupted = cleanupResult.getOrNull() == true || interrupted
        val cleanup = cleanupResult.exceptionOrNull()
        if (cleanup != null) {
            if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
        }
        if (interrupted) Thread.currentThread().interrupt()
        primary?.let { throw it }
        return checkNotNull(result)
    }

    private fun await(
        child: OwnedProcess,
        timeoutMs: Long,
        targetAlive: () -> Boolean,
        acceptedExitCodes: Set<Int>,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (child.process.isAlive) {
            if (!targetAlive()) {
                throw SpectreAttachExceptionImpl(
                    "Target process exited while VirtualMachine.attach was opening",
                    null,
                )
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw SpectreAttachExceptionImpl(
                    "VirtualMachine.attach did not complete within ${timeoutMs}ms",
                    null,
                )
            }
            child.process.waitFor(
                minOf(POLL_MS, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)),
                TimeUnit.MILLISECONDS,
            )
        }
        child.finishReading(CLEANUP_MS)
        val stdout = child.stdout()
        val stderr = child.stderr()
        val output = stdout.trim().ifEmpty { stderr.trim() }
        if (child.process.exitValue() !in acceptedExitCodes) {
            val detail = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n").trim()
            throw SpectreAttachExceptionImpl(
                "Bounded VirtualMachine.attach helper failed with exit " +
                    "${child.process.exitValue()}: $detail",
                null,
            )
        }
        return output
    }

    private class OwnedProcess(val process: Process) {
        private val stdoutBytes = ByteArrayOutputStream()
        private val stderrBytes = ByteArrayOutputStream()
        private val stdoutThread = drain("stdout", process.inputStream, stdoutBytes)
        private val stderrThread = drain("stderr", process.errorStream, stderrBytes)

        private fun drain(label: String, input: InputStream, sink: ByteArrayOutputStream) =
            thread(name = "spectre-attach-helper-$label", isDaemon = true) {
                input.use { it.copyTo(sink) }
            }

        fun stdout(): String = stdoutBytes.toString(StandardCharsets.UTF_8)

        fun stderr(): String = stderrBytes.toString(StandardCharsets.UTF_8)

        fun finishReading(timeoutMs: Long) {
            joinUntil(timeoutMs)
            checkReadersStopped()
        }

        /** Bounded cleanup; returns whether an interruption was consumed. */
        fun close(): Boolean {
            var interrupted = false
            if (process.isAlive) process.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_MS)
            while (process.isAlive && System.nanoTime() < deadline) {
                try {
                    process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
            try {
                joinUntil(CLEANUP_MS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
            checkReadersStopped()
            if (process.isAlive)
                throw IOException("Attach helper did not stop during bounded cleanup")
            return interrupted
        }

        private fun joinUntil(timeoutMs: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            for (worker in listOf(stdoutThread, stderrThread)) {
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) {
                    worker.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
                }
            }
        }

        private fun checkReadersStopped() {
            if (stdoutThread.isAlive || stderrThread.isAlive) {
                throw IOException("Attach helper output drains did not stop during bounded cleanup")
            }
        }
    }
}

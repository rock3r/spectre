package dev.sebastiano.spectre.agent.launch

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object LinuxProcessArguments {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 100L
    // No queued reads or unbounded thread creation if the kernel stalls on a target's memory.
    private val readers =
        ThreadPoolExecutor(
            0,
            2,
            1L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { task -> Thread(task, "spectre-proc-cmdline").apply { isDaemon = true } },
        )

    fun read(handle: ProcessHandle): Array<String> {
        val lookup =
            try {
                readers.submit<Array<String>> {
                    val path = Path.of("/proc", handle.pid().toString(), "cmdline")
                    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
                    if (
                        bytes.size > MAX_BYTES ||
                            bytes.lastOrNull() != 0.toByte() ||
                            !handle.isAlive
                    ) {
                        emptyArray()
                    } else {
                        // cmdline includes argv[0] and a NUL after every argument (even empty
                        // ones).
                        String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
                            .split('\u0000')
                            .drop(1)
                            .toTypedArray()
                    }
                }
            } catch (_: RejectedExecutionException) {
                return emptyArray()
            }
        return try {
            lookup.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: ExecutionException) {
            // The process may have exited or procfs may deny access.
            emptyArray()
        } catch (_: TimeoutException) {
            emptyArray()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyArray()
        } finally {
            lookup.cancel(true)
        }
    }
}

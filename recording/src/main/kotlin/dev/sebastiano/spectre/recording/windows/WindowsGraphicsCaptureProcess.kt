package dev.sebastiano.spectre.recording.windows

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap

internal fun isWindowsHost(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal fun writeWindowsHelperArgsFile(tokens: List<String>): Path {
    val file = Files.createTempFile("spectre-wgc-args-", ".txt")
    file.toFile().deleteOnExit()
    Files.write(file, tokens, StandardCharsets.UTF_8)
    return file
}

/** Starts the helper. On Windows the flag list is an `@args-file`. Stderr is always piped. */
internal fun startPumpedHelperProcess(
    logicalArgv: List<String>,
    configure: ProcessBuilder.() -> Unit,
): Process {
    val launchArgv =
        windowsHelperProcessArgv(
            logicalArgv = logicalArgv,
            useArgsFile = isWindowsHost(),
            createArgsFile = ::writeWindowsHelperArgsFile,
        )
    val process =
        ProcessBuilder(launchArgv)
            .apply(configure)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
    attachStderrPump(process)
    return process
}

internal fun helperFailureDetail(process: Process): String {
    val pump = stderrPumps[process]
    if (pump != null) {
        pump.awaitBriefly()
        return pump.text().trim()
    }
    return readAvailableStderr(process)
}

internal fun appendHelperStderr(message: String, stderr: String): String =
    if (stderr.isBlank()) message else "$message\nHelper stderr:\n$stderr"

private val stderrPumps = Collections.synchronizedMap(WeakHashMap<Process, StderrPump>())

private fun attachStderrPump(process: Process) {
    val pump = StderrPump(process)
    stderrPumps[process] = pump
    pump.start()
}

private fun readAvailableStderr(process: Process): String {
    val stream = process.errorStream
    val available = runCatching { stream.available() }.getOrDefault(0)
    if (available <= 0) return ""
    val bytes = ByteArray(available)
    val read = runCatching { stream.read(bytes) }.getOrDefault(-1)
    if (read <= 0) return ""
    return String(bytes, 0, read, StandardCharsets.UTF_8).trim()
}

private class StderrPump(private val process: Process) : Thread("spectre-wgc-stderr") {
    private val buffer = StringBuilder()

    init {
        isDaemon = true
    }

    override fun run() {
        val reader = process.errorStream.bufferedReader(StandardCharsets.UTF_8)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                System.err.println(line)
                appendBounded(line)
            }
        } catch (_: IOException) {
            // The helper closed stderr, or the process was destroyed.
        }
    }

    fun text(): String = synchronized(buffer) { buffer.toString() }

    fun awaitBriefly() {
        join(STDERR_JOIN_MILLIS)
    }

    private fun appendBounded(line: String) {
        synchronized(buffer) {
            if (buffer.length >= STDERR_LIMIT) return
            buffer.appendLine(line)
            if (buffer.length > STDERR_LIMIT) {
                buffer.setLength(STDERR_LIMIT)
            }
        }
    }

    private companion object {
        const val STDERR_JOIN_MILLIS: Long = 500
        const val STDERR_LIMIT: Int = 8_192
    }
}

package dev.sebastiano.spectre.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Resolves the real numeric UID of a process for POSIX same-user preflight (#166).
 *
 * Returns `null` when the UID cannot be determined so callers can fall back to username equality.
 * Lookup is advisory — the OS remains the real attach boundary.
 */
internal fun interface ProcessUidLookup {
    fun uidOf(pid: Long): Int?

    companion object {
        fun forOs(osName: String = System.getProperty("os.name").orEmpty()): ProcessUidLookup {
            val os = osName.lowercase()
            return if (os.startsWith("linux")) LinuxProcUidLookup else PsProcessUidLookup
        }
    }
}

/** Linux: parse the real UID from `/proc/<pid>/status` (shell-free). */
internal object LinuxProcUidLookup : ProcessUidLookup {
    override fun uidOf(pid: Long): Int? {
        if (pid <= 0L) return null
        return try {
            val status = Files.readString(Path.of("/proc", pid.toString(), "status"))
            parseProcStatusRealUid(status)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

/**
 * macOS / other Unix: `ps -o uid= -p <pid>` (argv list, no shell). Used when `/proc` is
 * unavailable.
 */
internal object PsProcessUidLookup : ProcessUidLookup {
    private const val WAIT_SECONDS = 2L

    override fun uidOf(pid: Long): Int? {
        if (pid <= 0L) return null
        return try {
            val process =
                ProcessBuilder("ps", "-o", "uid=", "-p", pid.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val finished = process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull()
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

/**
 * Parses the real UID from a Linux `/proc/<pid>/status` body.
 *
 * Format: `Uid:\t<real>\t<effective>\t<saved>\t<fs>` (tabs or spaces).
 */
internal fun parseProcStatusRealUid(statusText: String): Int? {
    for (line in statusText.lineSequence()) {
        if (!line.startsWith("Uid:")) continue
        val fields = line.removePrefix("Uid:").trim().split(WHITESPACE)
        return fields.firstOrNull()?.toIntOrNull()
    }
    return null
}

private val WHITESPACE = Regex("\\s+")

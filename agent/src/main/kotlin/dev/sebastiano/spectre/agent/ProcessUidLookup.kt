package dev.sebastiano.spectre.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Resolves the effective numeric UID of a process for POSIX same-user preflight (#166).
 *
 * Effective UID (not real UID) matches attach-relevant identity: HotSpot attach rendezvous and
 * filesystem credentials follow euid, and macOS `ps -o uid=` reports euid. Linux
 * `/proc/<pid>/status` exposes both; we deliberately read the effective field.
 *
 * Returns `null` when the UID cannot be determined so callers can fall back to username equality.
 * Lookup is advisory — the OS remains the real attach boundary.
 *
 * UIDs are [Long] so the full unsigned 32-bit `uid_t` range (0..2^32-1) is representable.
 */
internal fun interface ProcessUidLookup {
    fun uidOf(pid: Long): Long?

    companion object {
        fun forOs(osName: String = System.getProperty("os.name").orEmpty()): ProcessUidLookup {
            val os = osName.lowercase()
            return if (os.startsWith("linux")) LinuxProcUidLookup else PsProcessUidLookup
        }
    }
}

/** Linux: parse the effective UID from `/proc/<pid>/status` (shell-free). */
internal object LinuxProcUidLookup : ProcessUidLookup {
    override fun uidOf(pid: Long): Long? {
        if (pid <= 0L) return null
        return try {
            val status = Files.readString(Path.of("/proc", pid.toString(), "status"))
            parseProcStatusEffectiveUid(status)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

/**
 * macOS / other Unix: `ps -o uid= -p <pid>` (argv list, no shell). Used when `/proc` is
 * unavailable. On macOS this reports the effective UID.
 */
internal object PsProcessUidLookup : ProcessUidLookup {
    private const val WAIT_SECONDS = 2L

    override fun uidOf(pid: Long): Long? {
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
            process.inputStream.bufferedReader().use { it.readText() }.trim().toUidOrNull()
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
 * Parses the **effective** UID from a Linux `/proc/<pid>/status` body.
 *
 * Format: `Uid:\t<real>\t<effective>\t<saved>\t<fs>` (tabs or spaces). Index 1 is effective. When
 * only one field is present, treat it as the sole reported identity.
 */
internal fun parseProcStatusEffectiveUid(statusText: String): Long? {
    for (line in statusText.lineSequence()) {
        if (!line.startsWith("Uid:")) continue
        val fields = line.removePrefix("Uid:").trim().split(WHITESPACE)
        // Prefer effective (field 1); fall back to a single-field line if the kernel omits the
        // rest.
        val raw = fields.getOrNull(1) ?: fields.firstOrNull() ?: return null
        return raw.toUidOrNull()
    }
    return null
}

/** Parse a decimal UID covering the full unsigned 32-bit `uid_t` range. */
internal fun String.toUidOrNull(): Long? {
    val value = toLongOrNull() ?: return null
    if (value < 0L || value > MAX_UID_T) return null
    return value
}

/** Max value of POSIX `uid_t` when it is an unsigned 32-bit integer. */
internal const val MAX_UID_T: Long = 0xFFFF_FFFFL

private val WHITESPACE = Regex("\\s+")

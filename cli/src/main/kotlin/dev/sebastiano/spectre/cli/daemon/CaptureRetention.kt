package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Lazy retention for the default capture root only.
 *
 * Never deletes captures owned by currently-attached sessions, and never auto-touches
 * client-supplied out-dir captures (those are only removed via explicit prune).
 */
internal object CaptureRetention {
    const val DEFAULT_KEEP: Int = 50

    fun enforce(
        defaultRoot: Path,
        ledger: CaptureLedger,
        keep: Int = DEFAULT_KEEP,
        liveSessionIds: Set<String>,
    ): List<Path> {
        if (keep < 0) return emptyList()
        val defaultEntries =
            ledger
                .listExisting()
                .filter { !it.explicitOutDir }
                .filter { isUnder(Path.of(it.path), defaultRoot) }
                .sortedBy { it.createdAtEpochMs }

        val deletable = defaultEntries.filter { entry -> entry.sessionId !in liveSessionIds }
        if (deletable.size <= keep) return emptyList()

        val toDelete = deletable.dropLast(keep)
        val deleted = ArrayList<Path>()
        for (entry in toDelete) {
            val path = Path.of(entry.path)
            if (deleteRecursively(path)) {
                deleted.add(path)
            }
        }
        if (deleted.isNotEmpty()) {
            ledger.removePaths(deleted.map { it.toString() }.toSet())
        }
        return deleted
    }

    fun deleteCaptureDirectory(path: Path): Boolean = deleteRecursively(path)

    private fun isUnder(path: Path, root: Path): Boolean {
        val normalizedPath = path.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        return normalizedPath.startsWith(normalizedRoot)
    }

    private fun deleteRecursively(path: Path): Boolean {
        if (!Files.exists(path)) return false
        if (path.isDirectory()) {
            Files.list(path).use { stream -> stream.forEach { child -> deleteRecursively(child) } }
        }
        return runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
    }
}

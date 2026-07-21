package dev.sebastiano.spectre.cli.daemon

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Append-only capture ledger. Crash-proof index covering default-root and out-dir captures. */
internal class CaptureLedger(private val ledgerFile: Path) {
    fun append(entry: CaptureLedgerEntry) {
        withLedgerLock {
            Files.createDirectories(ledgerFile.parent)
            val line = JSON.encodeToString(entry) + "\n"
            FileChannel.open(
                    ledgerFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
                .use { channel ->
                    val buffer = StandardCharsets.UTF_8.encode(line)
                    while (buffer.hasRemaining()) {
                        channel.write(buffer)
                    }
                    channel.force(true)
                }
        }
    }

    fun listExisting(): List<CaptureLedgerEntry> = withLedgerLock {
        readAllUnlocked().filter { entry -> Files.isDirectory(Path.of(entry.path)) }
    }

    fun entriesForSession(sessionId: String): List<CaptureLedgerEntry> =
        listExisting().filter { it.sessionId == sessionId }

    fun removePaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        withLedgerLock {
            if (!Files.isRegularFile(ledgerFile)) return@withLedgerLock
            val remaining = readAllUnlocked().filterNot { it.path in paths }
            rewriteUnlocked(remaining)
        }
    }

    private fun readAllUnlocked(): List<CaptureLedgerEntry> {
        if (!Files.isRegularFile(ledgerFile)) return emptyList()
        return Files.readAllLines(ledgerFile, StandardCharsets.UTF_8).mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching { JSON.decodeFromString(CaptureLedgerEntry.serializer(), trimmed) }
                .getOrNull()
        }
    }

    private fun rewriteUnlocked(entries: List<CaptureLedgerEntry>) {
        Files.createDirectories(ledgerFile.parent)
        val content = buildString {
            entries.forEach { entry ->
                append(JSON.encodeToString(entry))
                append('\n')
            }
        }
        val tmp = ledgerFile.resolveSibling(ledgerFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp,
                ledgerFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, ledgerFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> withLedgerLock(block: () -> T): T {
        val key = ledgerFile.toAbsolutePath().normalize().toString()
        val monitor = JVM_LOCKS.computeIfAbsent(key) { Any() }
        // Same-JVM writers cannot overlap FileChannel locks; serialize in-process first, then
        // take an exclusive file lock so CLI prune and the daemon cannot race across processes.
        synchronized(monitor) {
            Files.createDirectories(ledgerFile.parent)
            val lockPath = ledgerFile.resolveSibling(ledgerFile.fileName.toString() + ".lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
                channel ->
                channel.lock().use {
                    return block()
                }
            }
        }
    }

    private companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val JVM_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}

@Serializable
internal data class CaptureLedgerEntry(
    val sessionId: String,
    val path: String,
    val createdAtEpochMs: Long,
    val sizeBytes: Long,
    val explicitOutDir: Boolean,
)

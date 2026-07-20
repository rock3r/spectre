package dev.sebastiano.spectre.cli.daemon

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Append-only capture ledger. Crash-proof index covering default-root and out-dir captures. */
internal class CaptureLedger(private val ledgerFile: Path) {
    fun append(entry: CaptureLedgerEntry) {
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

    fun listExisting(): List<CaptureLedgerEntry> =
        readAll().filter { entry -> Files.isDirectory(Path.of(entry.path)) }

    fun entriesForSession(sessionId: String): List<CaptureLedgerEntry> =
        listExisting().filter { it.sessionId == sessionId }

    fun removePaths(paths: Set<String>) {
        if (paths.isEmpty() || !Files.isRegularFile(ledgerFile)) return
        val remaining = readAll().filterNot { it.path in paths }
        rewrite(remaining)
    }

    private fun readAll(): List<CaptureLedgerEntry> {
        if (!Files.isRegularFile(ledgerFile)) return emptyList()
        return Files.readAllLines(ledgerFile, StandardCharsets.UTF_8).mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching { JSON.decodeFromString(CaptureLedgerEntry.serializer(), trimmed) }
                .getOrNull()
        }
    }

    private fun rewrite(entries: List<CaptureLedgerEntry>) {
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
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, ledgerFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
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

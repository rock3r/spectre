package dev.sebastiano.spectre.cli.daemon

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Allocates `NNNN-<timestamp>/` capture directories under a root without shared counters. */
internal object CaptureDirectoryAllocator {
    private val SEQUENCE_PREFIX = Regex("^(\\d{4})-")
    private val TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

    fun allocate(root: Path, clock: Clock = Clock.systemUTC()): Path {
        Files.createDirectories(root)
        hardenDirectoryPermissions(root)
        var nextSequence = nextSequenceNumber(root)
        repeat(MAX_ALLOCATION_ATTEMPTS) {
            val name =
                String.format(
                    Locale.ROOT,
                    "%04d-%s",
                    nextSequence,
                    TIMESTAMP.format(Instant.now(clock)),
                )
            val candidate = root.resolve(name)
            try {
                Files.createDirectory(candidate)
                hardenDirectoryPermissions(candidate)
                return candidate
            } catch (_: FileAlreadyExistsException) {
                nextSequence = maxOf(nextSequence + 1, nextSequenceNumber(root))
            }
        }
        error(
            "Could not allocate a capture directory under $root after $MAX_ALLOCATION_ATTEMPTS attempts"
        )
    }

    fun nextSequenceNumber(root: Path): Int {
        if (!Files.isDirectory(root)) return 1
        var max = 0
        Files.list(root).use { stream ->
            stream.forEach { path ->
                val match = SEQUENCE_PREFIX.find(path.fileName.toString()) ?: return@forEach
                max = maxOf(max, match.groupValues[1].toInt())
            }
        }
        return max + 1
    }

    private fun hardenDirectoryPermissions(directory: Path) {
        try {
            Files.setPosixFilePermissions(
                directory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows and other non-POSIX filesystems ignore mode 0700.
        }
    }

    private const val MAX_ALLOCATION_ATTEMPTS: Int = 64
}

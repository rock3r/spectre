package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** Shared capture roots, ledger, and lifecycle helpers for the daemon and CLI. */
internal object CaptureLifecycle {
    fun defaultCapturesRoot(): Path =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre", "captures")

    fun defaultLedgerFile(): Path =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre", "capture-ledger.jsonl")

    fun ledger(ledgerFile: Path = defaultLedgerFile()): CaptureLedger = CaptureLedger(ledgerFile)

    fun allocateDirectory(outDir: String?, clock: Clock = Clock.systemUTC()): Path {
        val root =
            if (outDir != null) {
                Path.of(outDir)
            } else {
                defaultCapturesRoot()
            }
        return CaptureDirectoryAllocator.allocate(root, clock)
    }

    fun directorySizeBytes(directory: Path): Long {
        if (!Files.isDirectory(directory)) return 0L
        var total = 0L
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { total += Files.size(it) }
        }
        return total
    }
}

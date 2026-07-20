package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureRetentionTest {
    @Test
    fun `keeps newest captures on default root and never touches live sessions`() {
        val root = Files.createTempDirectory("spectre-retention")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val dirs =
            (1..5).map { index ->
                val dir =
                    Files.createDirectory(
                        root.resolve(String.format(java.util.Locale.ROOT, "%04d-ts", index))
                    )
                Files.writeString(dir.resolve("marker"), "x".repeat(index))
                ledger.append(
                    CaptureLedgerEntry(
                        sessionId = if (index == 2) "live" else "closed",
                        path = dir.toString(),
                        createdAtEpochMs = index.toLong(),
                        sizeBytes = index.toLong(),
                        explicitOutDir = false,
                    )
                )
                dir
            }

        val deleted =
            CaptureRetention.enforce(
                defaultRoot = root,
                ledger = ledger,
                keep = 2,
                liveSessionIds = setOf("live"),
            )

        // Live session capture (0002) must remain even if old; keep last 2 closed plus live.
        assertTrue(Files.isDirectory(dirs[1])) // live 0002
        assertTrue(deleted.isNotEmpty())
        assertFalse(Files.exists(dirs[0])) // oldest closed deleted
        val remaining = ledger.listExisting().map { it.path }.toSet()
        assertTrue(remaining.contains(dirs[1].toString()))
        assertEquals(3, remaining.size) // live + 2 newest closed (0004, 0005) — or similar
    }

    @Test
    fun `never auto-prunes explicit out-dir captures`() {
        val defaultRoot = Files.createTempDirectory("spectre-default")
        val outRoot = Files.createTempDirectory("spectre-out")
        val ledger = CaptureLedger(defaultRoot.resolve("ledger.jsonl"))
        val outCapture = Files.createDirectory(outRoot.resolve("0001-x"))
        ledger.append(CaptureLedgerEntry("s", outCapture.toString(), 1, 10, explicitOutDir = true))
        repeat(3) { index ->
            val dir =
                Files.createDirectory(
                    defaultRoot.resolve(String.format(java.util.Locale.ROOT, "%04d-d", index + 1))
                )
            ledger.append(
                CaptureLedgerEntry("s", dir.toString(), index + 2L, 1, explicitOutDir = false)
            )
        }

        CaptureRetention.enforce(
            defaultRoot = defaultRoot,
            ledger = ledger,
            keep = 1,
            liveSessionIds = emptySet(),
        )

        assertTrue(Files.isDirectory(outCapture))
    }
}

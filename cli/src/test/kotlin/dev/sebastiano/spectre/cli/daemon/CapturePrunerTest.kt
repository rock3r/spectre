package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapturePrunerTest {
    @Test
    fun `negative keep is rejected`() {
        val root = Files.createTempDirectory("spectre-prune")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        assertFailsWith<IllegalArgumentException> {
            CapturePruner.prune(
                request = CapturePruner.Request(keep = -1),
                ledger = ledger,
                liveSessionIds = emptySet(),
            )
        }
    }

    @Test
    fun `live sessions are skipped without force`() {
        val root = Files.createTempDirectory("spectre-prune")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val liveDir = Files.createDirectory(root.resolve("live"))
        ledger.append(CaptureLedgerEntry("live", liveDir.toString(), 1, 1, false))
        val result =
            CapturePruner.prune(
                request = CapturePruner.Request(all = true),
                ledger = ledger,
                liveSessionIds = setOf("live"),
            )
        assertTrue(result.deletedPaths.isEmpty())
        assertTrue(result.skippedLive.contains(liveDir.toString()))
        assertTrue(Files.isDirectory(liveDir))
    }
}

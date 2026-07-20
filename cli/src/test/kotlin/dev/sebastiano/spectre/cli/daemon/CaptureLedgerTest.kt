package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureLedgerTest {
    @Test
    fun `append and list drops entries whose directories no longer exist`() {
        val root = Files.createTempDirectory("spectre-ledger")
        val ledgerFile = root.resolve("ledger.jsonl")
        val alive = Files.createDirectory(root.resolve("alive"))
        val gone = root.resolve("gone")
        Files.createDirectory(gone)

        val ledger = CaptureLedger(ledgerFile)
        ledger.append(
            CaptureLedgerEntry(
                sessionId = "pid-1",
                path = alive.toString(),
                createdAtEpochMs = 10,
                sizeBytes = 11,
                explicitOutDir = false,
            )
        )
        ledger.append(
            CaptureLedgerEntry(
                sessionId = "pid-1",
                path = gone.toString(),
                createdAtEpochMs = 20,
                sizeBytes = 22,
                explicitOutDir = true,
            )
        )
        Files.delete(gone)

        val listed = ledger.listExisting()
        assertEquals(1, listed.size)
        assertEquals(alive.toString(), listed.single().path)
        assertEquals("pid-1", listed.single().sessionId)
    }

    @Test
    fun `entriesForSession returns only that session's existing dirs`() {
        val root = Files.createTempDirectory("spectre-ledger")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val a = Files.createDirectory(root.resolve("a"))
        val b = Files.createDirectory(root.resolve("b"))
        ledger.append(CaptureLedgerEntry("pid-1", a.toString(), 1, 1, explicitOutDir = false))
        ledger.append(CaptureLedgerEntry("pid-2", b.toString(), 2, 2, explicitOutDir = false))

        assertEquals(listOf(a.toString()), ledger.entriesForSession("pid-1").map { it.path })
        assertEquals(listOf(b.toString()), ledger.entriesForSession("pid-2").map { it.path })
    }

    @Test
    fun `removePaths drops matching ledger lines`() {
        val root = Files.createTempDirectory("spectre-ledger")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val keep = Files.createDirectory(root.resolve("keep"))
        val drop = Files.createDirectory(root.resolve("drop"))
        ledger.append(CaptureLedgerEntry("s", keep.toString(), 1, 1, false))
        ledger.append(CaptureLedgerEntry("s", drop.toString(), 2, 2, false))

        ledger.removePaths(setOf(drop.toString()))
        assertEquals(listOf(keep.toString()), ledger.listExisting().map { it.path })
        assertTrue(Files.exists(keep))
    }
}

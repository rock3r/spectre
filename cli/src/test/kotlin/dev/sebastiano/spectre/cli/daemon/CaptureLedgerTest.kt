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

    @Test
    fun `concurrent append and removePaths does not drop surviving entries`() {
        val root = Files.createTempDirectory("spectre-ledger-race")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val keepers =
            (1..20).map { index ->
                val dir = Files.createDirectory(root.resolve("k$index"))
                ledger.append(CaptureLedgerEntry("s", dir.toString(), index.toLong(), 1, false))
                dir
            }
        val doomed = Files.createDirectory(root.resolve("doomed"))
        ledger.append(CaptureLedgerEntry("s", doomed.toString(), 100, 1, false))

        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val appends =
                (1..30).map { index ->
                    pool.submit {
                        val dir = Files.createDirectory(root.resolve("a$index"))
                        ledger.append(
                            CaptureLedgerEntry("s", dir.toString(), 200L + index, 1, false)
                        )
                    }
                }
            val removals =
                (1..10).map { pool.submit { ledger.removePaths(setOf(doomed.toString())) } }
            (appends + removals).forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val paths = ledger.listExisting().map { it.path }.toSet()
        keepers.forEach { assertTrue(paths.contains(it.toString()), "missing ${it.fileName}") }
        assertTrue(paths.none { it.endsWith("doomed") })
        assertTrue(paths.size >= keepers.size + 20)
    }
}

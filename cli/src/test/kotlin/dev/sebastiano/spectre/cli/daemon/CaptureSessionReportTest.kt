package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSessionReportTest {
    @Test
    fun `forDetach reports zero leftovers when the session wrote nothing`() {
        val root = Files.createTempDirectory("spectre-detach-empty")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))

        val detached = CaptureSessionReport.forDetach("session-empty", ledger)

        assertEquals("session-empty", detached.sessionId)
        assertEquals(0, detached.captureCount)
        assertEquals(0L, detached.captureBytes)
        assertEquals(emptyList(), detached.capturePaths)
        assertNull(detached.pruneCommand)
        assertNull(detached.skillHint)
    }

    @Test
    fun `forDetach reports real leftover paths bytes and prune hints`() {
        val root = Files.createTempDirectory("spectre-detach-leftovers")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val first = Files.createDirectories(root.resolve("cap-a"))
        val second = Files.createDirectories(root.resolve("cap-b"))
        // sizeBytes in the ledger is what detach reports (not a live re-scan of directory size).
        ledger.append(
            CaptureLedgerEntry(
                sessionId = "session-42",
                path = first.toString(),
                createdAtEpochMs = 1,
                sizeBytes = 1_024,
                explicitOutDir = false,
            )
        )
        ledger.append(
            CaptureLedgerEntry(
                sessionId = "session-42",
                path = second.toString(),
                createdAtEpochMs = 2,
                sizeBytes = 2_048,
                explicitOutDir = false,
            )
        )
        // Other sessions must not pollute this session's detach report.
        ledger.append(
            CaptureLedgerEntry(
                sessionId = "other",
                path = Files.createDirectories(root.resolve("other")).toString(),
                createdAtEpochMs = 3,
                sizeBytes = 99,
                explicitOutDir = false,
            )
        )

        val detached = CaptureSessionReport.forDetach("session-42", ledger)

        assertEquals("session-42", detached.sessionId)
        assertEquals(2, detached.captureCount)
        assertEquals(3_072L, detached.captureBytes)
        assertEquals(listOf(first.toString(), second.toString()), detached.capturePaths)
        assertEquals("spectre captures prune --session session-42", detached.pruneCommand)
        assertEquals(CaptureSessionReport.CAPTURE_SKILL_NAME, detached.skillHint)
        assertTrue(detached.capturePaths.all { Files.isDirectory(java.nio.file.Path.of(it)) })
    }

    @Test
    fun `forDetach ignores ledger rows whose capture directory no longer exists`() {
        val root = Files.createTempDirectory("spectre-detach-missing-dir")
        val ledger = CaptureLedger(root.resolve("ledger.jsonl"))
        val gone = root.resolve("gone")
        // Deliberately do not create `gone` on disk.
        ledger.append(
            CaptureLedgerEntry(
                sessionId = "session-gone",
                path = gone.toString(),
                createdAtEpochMs = 1,
                sizeBytes = 500,
                explicitOutDir = false,
            )
        )

        val detached = CaptureSessionReport.forDetach("session-gone", ledger)

        assertEquals(0, detached.captureCount)
        assertEquals(0L, detached.captureBytes)
        assertEquals(emptyList(), detached.capturePaths)
        assertNull(detached.pruneCommand)
        assertNull(detached.skillHint)
    }
}

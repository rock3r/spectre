package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Acceptance coverage for #181: crash-proof ledger survival and two-session concurrency/prune
 * guards that the thinner unit tests do not fully exercise.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class CaptureLifecycleAcceptanceTest {

    @Test
    fun `ledger survives process restart after mid-session captures`() {
        val root = Files.createTempDirectory("spectre-crash-root")
        val ledgerFile = root.resolve("capture-ledger.jsonl")
        val firstProcessLedger = CaptureLedger(ledgerFile)
        val clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC)

        // Session writes two captures, then "dies" (drop the ledger instance; keep files).
        CaptureArtifactStore.write(
            sessionId = "pid-dead",
            result = sampleResult(png = byteArrayOf(9, 9, 9)),
            outDir = null,
            liveSessionIds = setOf("pid-dead"),
            clock = clock,
            ledger = firstProcessLedger,
            defaultRoot = root,
            retentionKeep = 50,
        )
        CaptureArtifactStore.write(
            sessionId = "pid-dead",
            result = sampleResult(png = byteArrayOf(8, 8, 8, 8)),
            outDir = null,
            liveSessionIds = setOf("pid-dead"),
            clock = clock,
            ledger = firstProcessLedger,
            defaultRoot = root,
            retentionKeep = 50,
        )

        // New process: construct a fresh ledger on the same file — no in-memory state.
        val restarted = CaptureLedger(ledgerFile)
        val surviving = restarted.listExisting()
        assertEquals(2, surviving.size, "both captures must still be listed after restart")
        surviving.forEach { entry ->
            assertEquals("pid-dead", entry.sessionId)
            assertTrue(Files.isDirectory(java.nio.file.Path.of(entry.path)))
            assertTrue(Files.isRegularFile(java.nio.file.Path.of(entry.path, "capture.json")))
            assertTrue(Files.isRegularFile(java.nio.file.Path.of(entry.path, "screenshot.png")))
            assertFalse(entry.explicitOutDir, "default-root captures must not be marked out-dir")
        }
        assertEquals(
            listOf("0001-", "0002-"),
            surviving.map { java.nio.file.Path.of(it.path).fileName.toString().take(5) }.sorted(),
        )
    }

    @Test
    fun `two sessions capture concurrently without colliding and cannot prune each others live dirs`() {
        val root = Files.createTempDirectory("spectre-two-session")
        val ledgerFile = root.resolve("capture-ledger.jsonl")
        val ledger = CaptureLedger(ledgerFile)
        val clock = Clock.systemUTC()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs =
                listOf("session-A", "session-B").flatMap { sessionId ->
                    (1..8).map { index ->
                        pool.submit(
                            Callable {
                                CaptureArtifactStore.write(
                                    sessionId = sessionId,
                                    result =
                                        sampleResult(
                                            png =
                                                byteArrayOf(
                                                    index.toByte(),
                                                    sessionId.hashCode().toByte(),
                                                )
                                        ),
                                    outDir = null,
                                    liveSessionIds = setOf("session-A", "session-B"),
                                    clock = clock,
                                    ledger = ledger,
                                    defaultRoot = root,
                                    retentionKeep = 50,
                                )
                            }
                        )
                    }
                }
            val results = jobs.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(16, results.size)
            val dirs = results.map { it.directory }.toSet()
            assertEquals(16, dirs.size, "every capture must get a unique directory")

            val bySession = ledger.listExisting().groupBy { it.sessionId }
            assertEquals(8, bySession["session-A"]?.size)
            assertEquals(8, bySession["session-B"]?.size)

            // Live guard: both sessions still attached — prune --all must skip every path.
            val prunedWhileLive =
                CapturePruner.prune(
                    request = CapturePruner.Request(all = true),
                    ledger = ledger,
                    liveSessionIds = setOf("session-A", "session-B"),
                )
            assertTrue(prunedWhileLive.deletedPaths.isEmpty())
            assertEquals(16, prunedWhileLive.skippedLive.size)
            assertEquals(16, ledger.listExisting().size)

            // Retention must also refuse to delete live sessions' dirs.
            val retentionDeleted =
                CaptureRetention.enforce(
                    defaultRoot = root,
                    ledger = ledger,
                    keep = 1,
                    liveSessionIds = setOf("session-A", "session-B"),
                )
            assertTrue(retentionDeleted.isEmpty())
            assertEquals(16, ledger.listExisting().size)

            // After A detaches, prune may remove only A's captures while B stays live.
            val prunedAfterA =
                CapturePruner.prune(
                    request = CapturePruner.Request(sessionId = "session-A"),
                    ledger = ledger,
                    liveSessionIds = setOf("session-B"),
                )
            assertEquals(8, prunedAfterA.deletedPaths.size)
            val remaining = ledger.listExisting()
            assertEquals(8, remaining.size)
            assertTrue(remaining.all { it.sessionId == "session-B" })
            remaining.forEach { entry ->
                assertTrue(Files.isDirectory(java.nio.file.Path.of(entry.path)))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun sampleResult(png: ByteArray): AtomicCaptureResult =
        AtomicCaptureResult(
            captureJson = """{"schemaVersion":1,"nodes":[]}""",
            pngBytes = png,
            schemaVersion = 1,
            windowIndex = 0,
            nodeCount = 0,
            taggedNodeCount = 0,
            textedNodeCount = 0,
            imageWidth = 4,
            imageHeight = 4,
            captureDurationMs = 1,
        )
}

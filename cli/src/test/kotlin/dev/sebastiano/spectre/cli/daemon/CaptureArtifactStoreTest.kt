package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class CaptureArtifactStoreTest {
    @Test
    fun `writes artifacts ledger entry and uses sequenced directory names`() {
        val root = Files.createTempDirectory("spectre-store-root")
        val ledgerFile = root.resolve("ledger.jsonl")
        val ledger = CaptureLedger(ledgerFile)
        val clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC)

        val first =
            CaptureArtifactStore.write(
                sessionId = "pid-1",
                result = sampleResult(),
                outDir = root.toString(),
                liveSessionIds = setOf("pid-1"),
                clock = clock,
                ledger = ledger,
                defaultRoot = root,
                retentionKeep = 50,
            )
        val second =
            CaptureArtifactStore.write(
                sessionId = "pid-1",
                result = sampleResult(),
                outDir = root.toString(),
                liveSessionIds = setOf("pid-1"),
                clock = clock,
                ledger = ledger,
                defaultRoot = root,
                retentionKeep = 50,
            )

        assertTrue(Files.isRegularFile(java.nio.file.Path.of(first.captureJsonPath)))
        assertTrue(Files.isRegularFile(java.nio.file.Path.of(first.screenshotPngPath)))
        assertTrue(first.directory.contains("0001-"))
        assertTrue(second.directory.contains("0002-"))
        assertEquals(2, ledger.listExisting().size)
        assertEquals("pid-1", ledger.listExisting().first().sessionId)
    }

    private fun sampleResult(): AtomicCaptureResult =
        AtomicCaptureResult(
            captureJson = """{"schemaVersion":1}""",
            pngBytes = byteArrayOf(1, 2, 3, 4),
            schemaVersion = 1,
            windowIndex = 0,
            nodeCount = 1,
            taggedNodeCount = 0,
            textedNodeCount = 0,
            imageWidth = 10,
            imageHeight = 10,
            captureDurationMs = 5,
        )
}

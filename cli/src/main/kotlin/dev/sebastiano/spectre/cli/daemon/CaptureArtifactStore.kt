package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Writes atomic-capture artifacts and records them in the append-only ledger.
 *
 * Sequencing, retention, and session-aware lifecycle ownership for #181 live here.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal object CaptureArtifactStore {

    fun write(
        sessionId: String,
        result: AtomicCaptureResult,
        outDir: String?,
        liveSessionIds: Set<String> = emptySet(),
        clock: Clock = Clock.systemUTC(),
        ledger: CaptureLedger = CaptureLifecycle.ledger(),
        defaultRoot: Path = CaptureLifecycle.defaultCapturesRoot(),
        retentionKeep: Int = CaptureRetention.DEFAULT_KEEP,
    ): DaemonResponse.Capture {
        val explicitOutDir = outDir != null
        val directory = CaptureLifecycle.allocateDirectory(outDir, clock)
        val captureJsonPath = directory.resolve("capture.json")
        val screenshotPngPath = directory.resolve("screenshot.png")
        Files.writeString(captureJsonPath, result.captureJson)
        Files.write(screenshotPngPath, result.pngBytes)
        val sizeBytes = CaptureLifecycle.directorySizeBytes(directory)
        ledger.append(
            CaptureLedgerEntry(
                sessionId = sessionId,
                path = directory.toAbsolutePath().normalize().toString(),
                createdAtEpochMs = clock.millis(),
                sizeBytes = sizeBytes,
                explicitOutDir = explicitOutDir,
            )
        )
        if (!explicitOutDir) {
            CaptureRetention.enforce(
                defaultRoot = defaultRoot,
                ledger = ledger,
                keep = retentionKeep,
                liveSessionIds = liveSessionIds + sessionId,
            )
        }
        return DaemonResponse.Capture(
            sessionId = sessionId,
            directory = directory.toString(),
            captureJsonPath = captureJsonPath.toString(),
            screenshotPngPath = screenshotPngPath.toString(),
            schemaVersion = result.schemaVersion,
            windowIndex = result.windowIndex,
            nodeCount = result.nodeCount,
            taggedNodeCount = result.taggedNodeCount,
            textedNodeCount = result.textedNodeCount,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            captureDurationMs = result.captureDurationMs,
        )
    }
}

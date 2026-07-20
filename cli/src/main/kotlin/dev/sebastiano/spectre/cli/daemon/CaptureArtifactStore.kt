package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes atomic-capture artifacts for the #180 thin surface.
 *
 * Full sequencing, retention, and ledger ownership land in #181; this store only guarantees a
 * unique writable directory and dumps `capture.json` + `screenshot.png`.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal object CaptureArtifactStore {

    fun write(
        sessionId: String,
        result: AtomicCaptureResult,
        outDir: String?,
    ): DaemonResponse.Capture {
        val directory = allocateDirectory(outDir)
        val captureJsonPath = directory.resolve("capture.json")
        val screenshotPngPath = directory.resolve("screenshot.png")
        Files.writeString(captureJsonPath, result.captureJson)
        Files.write(screenshotPngPath, result.pngBytes)
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

    private fun allocateDirectory(outDir: String?): Path {
        val root =
            if (outDir != null) {
                Path.of(outDir)
            } else {
                Path.of(System.getProperty("java.io.tmpdir"), "spectre", "captures")
            }
        Files.createDirectories(root)
        val stamp =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now())
        return Files.createTempDirectory(root, "0000-$stamp-")
    }
}

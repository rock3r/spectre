package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import javax.imageio.ImageIO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        val directory =
            CaptureLifecycle.allocateDirectory(
                outDir = outDir,
                clock = clock,
                defaultRoot = defaultRoot,
            )
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

    /**
     * Writes an OS-accessibility capture only after both artifacts have been obtained successfully.
     */
    fun writeAccessibility(
        sessionId: String,
        windowIndex: Int,
        pngBytes: ByteArray,
        snapshot: AccessibilitySnapshot,
        outDir: String?,
        liveSessionIds: Set<String> = emptySet(),
        clock: Clock = Clock.systemUTC(),
        ledger: CaptureLedger = CaptureLifecycle.ledger(),
        defaultRoot: Path = CaptureLifecycle.defaultCapturesRoot(),
        retentionKeep: Int = CaptureRetention.DEFAULT_KEEP,
    ): DaemonResponse.Capture {
        require(snapshot.schemaVersion == 2) { "accessibility capture requires schema version 2" }
        val explicitOutDir = outDir != null
        val directory = CaptureLifecycle.allocateDirectory(outDir, clock, defaultRoot)
        try {
            val treePath = directory.resolve("accessibility.json")
            val screenshotPath = directory.resolve("screenshot.png")
            Files.writeString(treePath, Json.encodeToString(snapshot))
            Files.write(screenshotPath, pngBytes)
            val image =
                requireNotNull(ImageIO.read(pngBytes.inputStream())) { "invalid screenshot PNG" }
            ledger.append(
                CaptureLedgerEntry(
                    sessionId,
                    directory.toAbsolutePath().normalize().toString(),
                    clock.millis(),
                    CaptureLifecycle.directorySizeBytes(directory),
                    explicitOutDir,
                )
            )
            if (!explicitOutDir) {
                CaptureRetention.enforce(
                    defaultRoot,
                    ledger,
                    retentionKeep,
                    liveSessionIds + sessionId,
                )
            }
            return DaemonResponse.Capture(
                sessionId = sessionId,
                directory = directory.toString(),
                captureJsonPath = treePath.toString(),
                screenshotPngPath = screenshotPath.toString(),
                schemaVersion = snapshot.schemaVersion,
                windowIndex = windowIndex,
                nodeCount = snapshot.nodes.size,
                taggedNodeCount = 0,
                textedNodeCount = snapshot.nodes.count { !it.name.isNullOrBlank() },
                imageWidth = image.width,
                imageHeight = image.height,
                captureDurationMs = 0,
            )
        } catch (exception: java.io.IOException) {
            directory.toFile().deleteRecursively()
            throw exception
        } catch (exception: IllegalArgumentException) {
            directory.toFile().deleteRecursively()
            throw exception
        } catch (exception: IllegalStateException) {
            directory.toFile().deleteRecursively()
            throw exception
        }
    }
}

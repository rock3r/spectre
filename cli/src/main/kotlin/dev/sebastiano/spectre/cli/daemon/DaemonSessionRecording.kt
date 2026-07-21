package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.screencapturekit.MacOsScreenCaptureAccess
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureAccessDeniedException
import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

internal data class RecordingStatus(
    val active: Boolean,
    val outputPath: String? = null,
    val captureDirectory: String? = null,
)

/**
 * Daemon-owned window recording for one attach session (#185): window identity → AutoRecorder
 * window(+crop), capture-dir allocation, ledger, and finalize-on-close.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal class DaemonSessionRecording(
    private val delegate: AttachedAutomator,
    private val sessionId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ledger: CaptureLedger = CaptureLifecycle.ledger(),
) {
    private var recording: RecordingHandle? = null
    private var recordingOutput: Path? = null
    private var captureDirectory: Path? = null
    private var ledgerExplicitOutDir: Boolean = false

    fun start(outputPath: String?, windowIndex: Int): String {
        if (recording != null) {
            throw IOException("a recording is already in progress for this session")
        }
        requireScreenCaptureAccess()
        val identity = requireRecordableIdentity(windowIndex)
        val title = requireNonBlankTitle(identity)
        requireUniqueTitle(title, identity.index)
        val (destination, directory, explicit) = resolveOutput(outputPath)
        destination.parent?.let { Files.createDirectories(it) }
        return try {
            val handle = startWindowRecorder(identity, title, destination)
            recording = handle
            recordingOutput = destination
            captureDirectory = directory
            ledgerExplicitOutDir = explicit
            // Ledger only after stop with final size — avoids double-counting (#185 review).
            destination.toString()
        } catch (exception: ScreenCaptureAccessDeniedException) {
            discardAllocatedDirectory(directory, explicit)
            throw IOException(exception.message ?: "Screen Recording not granted", exception)
        } catch (exception: IllegalStateException) {
            discardAllocatedDirectory(directory, explicit)
            throw IOException(exception.message ?: "failed to start recording", exception)
        } catch (exception: IllegalArgumentException) {
            discardAllocatedDirectory(directory, explicit)
            throw IOException(exception.message ?: "failed to start recording", exception)
        } catch (exception: UnsupportedOperationException) {
            discardAllocatedDirectory(directory, explicit)
            throw IOException(exception.message ?: "failed to start recording", exception)
        } catch (exception: IOException) {
            discardAllocatedDirectory(directory, explicit)
            throw exception
        }
    }

    private fun discardAllocatedDirectory(directory: Path, explicit: Boolean) {
        if (!explicit) {
            runCatching { directory.toFile().deleteRecursively() }
        }
    }

    fun stop(liveSessionIds: Set<String>): String {
        val active = recording ?: throw IOException("no recording is in progress for this session")
        val directory = captureDirectory
        val explicit = ledgerExplicitOutDir
        val knownOutput = recordingOutput?.toString()
        var result: String? = null
        var error: IOException? = null
        try {
            active.stop()
            result = active.output.toString()
        } catch (exception: IllegalStateException) {
            error = IOException(exception.message ?: "failed to stop recording", exception)
        }
        // Always ledger Spectre-allocated dirs (including partial files after a failed stop)
        // so prune/retention can clean them up. Never ledger user --output parents.
        if (directory != null && !explicit) {
            appendLedger(
                directory,
                sizeBytes = CaptureLifecycle.directorySizeBytes(directory),
                explicit = false,
            )
            runCatching {
                CaptureRetention.enforce(
                    defaultRoot = CaptureLifecycle.defaultCapturesRoot(),
                    ledger = ledger,
                    liveSessionIds = liveSessionIds + sessionId,
                )
            }
        }
        clear()
        if (error != null) throw error
        return result ?: knownOutput ?: error("recording stopped without an output path")
    }

    fun status(): RecordingStatus {
        val output = recordingOutput
        return RecordingStatus(
            active = recording != null,
            outputPath = output?.toString(),
            captureDirectory = captureDirectory?.toString(),
        )
    }

    /** Finalize any active recording (detach / close). Safe if idle. */
    fun finalizeIfActive(liveSessionIds: Set<String>) {
        if (recording != null) runCatching { stop(liveSessionIds) }
        clear()
    }

    private fun clear() {
        recording = null
        recordingOutput = null
        captureDirectory = null
    }

    private fun requireScreenCaptureAccess() {
        try {
            MacOsScreenCaptureAccess.requireGranted()
        } catch (exception: ScreenCaptureAccessDeniedException) {
            throw IOException(exception.message ?: "Screen Recording not granted", exception)
        }
    }

    private fun requireRecordableIdentity(windowIndex: Int): WindowIdentityDto =
        selectIdentity(windowIndex)
            ?: throw IOException(
                "the target has no non-popup window identity to record " +
                    "(windowIndex=$windowIndex)"
            )

    private fun requireNonBlankTitle(identity: WindowIdentityDto): String =
        identity.title?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "window ${identity.index} has a blank title; cannot target for window " +
                    "capture. Set a unique window title or pass region mode later."
            )

    private fun requireUniqueTitle(title: String, selectedIndex: Int) {
        val all =
            try {
                delegate.windowIdentities(null)
            } catch (exception: IOException) {
                throw IOException(
                    "failed to enumerate windows for title uniqueness: ${exception.message}",
                    exception,
                )
            }
        // Include popups: helpers match by title among all same-PID windows (#185 review).
        val collisions = all.filter { identity -> identity.title.orEmpty().contains(title) }
        if (collisions.size > 1) {
            val indexes = collisions.map { it.index }.joinToString(", ")
            throw IOException(
                "window title \"$title\" is ambiguous among windows " +
                    "[$indexes] (selected index $selectedIndex; includes popups). " +
                    "Use a unique title so remote capture can match the intended window."
            )
        }
    }

    private fun startWindowRecorder(
        identity: WindowIdentityDto,
        title: String,
        destination: Path,
    ): RecordingHandle {
        val crop =
            if (identity.cropRequired) {
                Rectangle(
                    identity.surfaceBoundsInWindow.x,
                    identity.surfaceBoundsInWindow.y,
                    identity.surfaceBoundsInWindow.width,
                    identity.surfaceBoundsInWindow.height,
                )
            } else {
                null
            }
        return AutoRecorder()
            .startWindowByTitle(
                title = title,
                windowOwnerPid = delegate.pid,
                output = destination,
                cropInWindow = crop,
                scaleX = identity.scaleX,
                scaleY = identity.scaleY,
            )
    }

    private fun appendLedger(directory: Path, sizeBytes: Long, explicit: Boolean) {
        ledger.append(
            CaptureLedgerEntry(
                sessionId = sessionId,
                path = directory.toAbsolutePath().normalize().toString(),
                createdAtEpochMs = clock.millis(),
                sizeBytes = sizeBytes,
                explicitOutDir = explicit,
            )
        )
    }

    private fun selectIdentity(windowIndex: Int): WindowIdentityDto? {
        val identities =
            try {
                delegate.windowIdentities(windowIndex)
            } catch (exception: IOException) {
                throw IOException(
                    "failed to read window identity for recording: ${exception.message}",
                    exception,
                )
            }
        val match = identities.firstOrNull()
        if (match == null) {
            throw IOException(
                "no window identity at windowIndex=$windowIndex " +
                    "(out of range or no tracked windows)"
            )
        }
        if (match.isPopup) {
            throw IOException(
                "windowIndex=$windowIndex is a popup; pass a non-popup window index for recording"
            )
        }
        return match
    }

    private data class ResolvedOutput(
        val file: Path,
        val directory: Path,
        val explicitOutDir: Boolean,
    )

    private fun resolveOutput(outputPath: String?): ResolvedOutput {
        if (outputPath.isNullOrBlank()) {
            val directory = CaptureLifecycle.allocateDirectory(outDir = null, clock = clock)
            return ResolvedOutput(
                file = directory.resolve("recording.mp4"),
                directory = directory,
                explicitOutDir = false,
            )
        }
        val file = Path.of(outputPath).toAbsolutePath().normalize()
        val directory = file.parent ?: Path.of(".")
        return ResolvedOutput(file = file, directory = directory, explicitOutDir = true)
    }
}

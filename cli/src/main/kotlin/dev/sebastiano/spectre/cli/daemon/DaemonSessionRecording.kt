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
 * Daemon-owned recording for one attach session (#185): window identity → AutoRecorder
 * window(+crop), optional full-desktop region capture, capture-dir allocation, ledger, and
 * finalize-on-close.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal class DaemonSessionRecording(
    private val sessionId: String,
    private val targetPid: Long,
    private val windowIdentities: (windowIndex: Int?) -> List<WindowIdentityDto>,
    private val clock: Clock = Clock.systemUTC(),
    private val ledger: CaptureLedger = CaptureLifecycle.ledger(),
    private val fullscreenRegionBounds: () -> Rectangle = ::requireFullscreenRegionBounds,
    private val autoRecorderFactory: () -> AutoRecorder = { AutoRecorder() },
    /**
     * Out-of-process window recording seam (default: [AutoRecorder.startWindowByTitle]). Tests
     * inject a fake starter so kill-target / finalize behaviour is CI-safe without SCK/WGC/portal.
     */
    private val startWindowByTitle:
        (
            title: String,
            windowOwnerPid: Long,
            output: Path,
            cropInWindow: Rectangle?,
            scaleX: Double,
            scaleY: Double,
        ) -> RecordingHandle =
        { title, windowOwnerPid, output, cropInWindow, scaleX, scaleY ->
            autoRecorderFactory()
                .startWindowByTitle(
                    title = title,
                    windowOwnerPid = windowOwnerPid,
                    output = output,
                    cropInWindow = cropInWindow,
                    scaleX = scaleX,
                    scaleY = scaleY,
                )
        },
) {
    constructor(
        delegate: AttachedAutomator,
        sessionId: String,
        clock: Clock = Clock.systemUTC(),
        ledger: CaptureLedger = CaptureLifecycle.ledger(),
        fullscreenRegionBounds: () -> Rectangle = ::requireFullscreenRegionBounds,
        autoRecorderFactory: () -> AutoRecorder = { AutoRecorder() },
    ) : this(
        sessionId = sessionId,
        targetPid = delegate.pid,
        windowIdentities = { index -> delegate.windowIdentities(index) },
        clock = clock,
        ledger = ledger,
        fullscreenRegionBounds = fullscreenRegionBounds,
        autoRecorderFactory = autoRecorderFactory,
    )

    private var recording: RecordingHandle? = null
    private var recordingOutput: Path? = null
    private var captureDirectory: Path? = null
    private var ledgerExplicitOutDir: Boolean = false

    fun start(outputPath: String?, windowIndex: Int, fullscreen: Boolean = false): String {
        if (recording != null) {
            throw IOException("a recording is already in progress for this session")
        }
        requireScreenCaptureAccess()
        val (destination, directory, explicit) = resolveOutput(outputPath)
        destination.parent?.let { Files.createDirectories(it) }
        return try {
            val handle =
                if (fullscreen) {
                    startFullscreenRecorder(destination)
                } else {
                    val identity = requireRecordableIdentity(windowIndex)
                    val title = requireNonBlankTitle(identity)
                    requireUniqueTitle(title, identity.index)
                    startWindowRecorder(identity, title, destination)
                }
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

    private fun startFullscreenRecorder(destination: Path): RecordingHandle {
        // Region backends capture a single display (macOS display 0 / Wayland portal stream).
        // Multi-monitor virtual-desktop unions are not supported — fail loudly rather than
        // cropping a partial or invalid region.
        val bounds = fullscreenRegionBounds()
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw IOException(
                "cannot start fullscreen recording: display bounds are empty ($bounds)"
            )
        }
        return autoRecorderFactory().startRegion(region = bounds, output = destination)
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
        return try {
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
                runCatching {
                    appendLedger(
                        directory,
                        sizeBytes = CaptureLifecycle.directorySizeBytes(directory),
                        explicit = false,
                    )
                    CaptureRetention.enforce(
                        defaultRoot = CaptureLifecycle.defaultCapturesRoot(),
                        ledger = ledger,
                        liveSessionIds = liveSessionIds + sessionId,
                    )
                }
            }
            if (error != null) throw error
            result ?: knownOutput ?: error("recording stopped without an output path")
        } finally {
            // Always clear in-memory state so a failed stop cannot leave start() blocked.
            clear()
        }
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
                windowIdentities(null)
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
        return startWindowByTitle(
            title,
            targetPid,
            destination,
            crop,
            identity.scaleX,
            identity.scaleY,
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
                windowIdentities(windowIndex)
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

/**
 * Screen region for CLI/MCP `--fullscreen` recording.
 *
 * Region capture backends record a **single** display (macOS primary display / display index 0,
 * Windows graphics capture, Linux Xorg/Wayland portal one monitor). Multi-monitor “virtual desktop”
 * unions are not supported; callers get a clear error instead of a silently wrong crop.
 */
internal fun requireFullscreenRegionBounds(
    environment: java.awt.GraphicsEnvironment =
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
): Rectangle {
    val devices = environment.screenDevices
    if (devices.size > 1) {
        throw IOException(
            "fullscreen recording is not supported on multi-monitor desktops yet " +
                "(capture backends only record a single display; detected ${devices.size} " +
                "screens). Use window mode, or record with one display only."
        )
    }
    val bounds = environment.defaultScreenDevice.defaultConfiguration.bounds
    return Rectangle(bounds)
}

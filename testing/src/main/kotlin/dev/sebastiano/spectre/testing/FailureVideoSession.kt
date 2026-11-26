package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.recording.RecordingHandle
import java.nio.file.Files
import java.nio.file.Path

/**
 * One test-invocation failure-video lifecycle: optional start, mandatory stop+finalize before
 * keep/delete, and [abandon] for crash/abort teardown that must not leave an orphaned recorder.
 *
 * Always best-effort: start/stop/delete failures must not replace the original test outcome.
 */
internal class FailureVideoSession(
    private val config: FailureVideoConfig,
    private val starter: FailureVideoStarter,
) {
    private var handle: RecordingHandle? = null
    private var outputPath: Path? = null
    private var startFailure: String? = null
    @Volatile private var observing: Boolean = false
    private var observer: Thread? = null

    val activeOutput: Path?
        get() = outputPath

    val hasActiveRecorder: Boolean
        get() = handle != null

    @Synchronized
    fun start(automator: ComposeAutomator, testClassName: String, testMethodName: String) {
        if (!FailureVideoDecisions.shouldStart(config.policy)) return
        if (handle != null) return
        val methodDir =
            FailureArtifactPaths.methodDirectory(
                testClassName = testClassName,
                testMethodName = testMethodName,
                config =
                    FailureArtifactsConfig(
                        // enabled flag unused for path layout
                        enabled = false,
                        reportsRoot = config.reportsRoot,
                        attemptIndex = config.attemptIndex,
                        invocationId = config.invocationId,
                    ),
            )
        val output = methodDir.resolve(VIDEO_FILE_NAME)
        val directoryResult = runCatching { Files.createDirectories(methodDir) }
        if (directoryResult.isFailure) {
            startFailure = directoryResult.exceptionOrNull()?.describeForReport()
            return
        }
        if (starter === AutoFailureVideoStarter) {
            observing = true
            observer =
                Thread({ observeUntilStarted(automator, output) }, "spectre-failure-video").also {
                    it.isDaemon = true
                    it.start()
                }
            return
        }
        val startedResult = runCatching { starter.start(output, automator, config.scope) }
        val started = startedResult.getOrNull()
        if (started == null) {
            startFailure =
                startedResult.exceptionOrNull()?.describeForReport()
                    ?: "the recorder did not find a recordable window"
            // Starter may have created a partial file before failing; never leave it for a later
            // pass/abort to look like kept failure-video evidence.
            handle = null
            outputPath = null
            deleteQuietly(output)
            return
        }
        handle = started
        outputPath = started.output
        startFailure = null
    }

    /**
     * Stop the recorder (finalize the file), then keep or delete according to [policy] and
     * [outcome]. Safe when nothing was started.
     */
    fun finalizeAndApply(
        outcome: FailureVideoOutcome,
        publishReport: (key: String, value: String) -> Unit = { _, _ -> },
    ) {
        stopObservation()
        val active = handle
        val path = outputPath
        handle = null
        outputPath = null
        if (active == null) {
            if (FailureVideoDecisions.shouldKeep(config.policy, outcome)) {
                publishMissing(publishReport, startFailure ?: "recording was not started")
            }
            return
        }
        val finalized = path ?: active.output
        val stopResult = runCatching { if (!active.isStopped) active.stop() }
        val stopOk = stopResult.isSuccess
        // Only keep after a successful stop: a failed finalize can leave a truncated file that
        // must not be published as failure evidence (treat like abandon/delete).
        val keep = stopOk && FailureVideoDecisions.shouldKeep(config.policy, outcome)
        if (keep) {
            val validationFailure = finalizedVideoFailure(finalized)
            if (validationFailure == null) {
                publishReportSafely(
                    publishReport,
                    REPORT_ENTRY_KEY,
                    finalized.toAbsolutePath().normalize().toString(),
                )
            } else {
                deleteQuietly(finalized)
                publishMissing(publishReport, validationFailure)
            }
        } else {
            deleteQuietly(finalized)
            if (!stopOk && FailureVideoDecisions.shouldKeep(config.policy, outcome)) {
                publishMissing(
                    publishReport,
                    "recording could not be finalized: ${stopResult.exceptionOrNull()?.describeForReport()}",
                )
            }
        }
    }

    /**
     * Force stop + delete for crash/teardown paths where there is no normal outcome decision yet.
     * Leaves no active recorder.
     */
    fun abandon() {
        stopObservation()
        val active = handle
        val path = outputPath
        handle = null
        outputPath = null
        if (active != null) {
            runCatching { if (!active.isStopped) active.stop() }
        }
        val target = path ?: active?.output
        if (target != null) deleteQuietly(target)
    }

    private fun deleteQuietly(path: Path) {
        runCatching {
            Files.deleteIfExists(path)
            removeEmptyParentsTowardReportsRoot(path.parent)
        }
    }

    private fun observeUntilStarted(automator: ComposeAutomator, output: Path) {
        val result = runCatching {
            when (val scope = config.scope) {
                FailureVideoScope.Window -> {
                    var surfaceId: String? = null
                    while (observing && surfaceId == null) {
                        surfaceId = AutoFailureVideoStarter.discoverWindow(automator)
                        if (surfaceId == null) Thread.sleep(25)
                    }
                    if (surfaceId == null) return
                    AutoFailureVideoStarter.startWindow(output, automator, surfaceId)
                }
                else -> AutoFailureVideoStarter.start(output, automator, scope)
            }
        }
        val started = result.getOrNull()
        synchronized(this) {
            if (observing && started != null) {
                handle = started
                outputPath = started.output
                startFailure = null
                observer = null
                return
            }
            if (result.isFailure && observing) {
                startFailure = result.exceptionOrNull()?.describeForReport()
                observing = false
            }
            observer = null
        }
        // A native start may return after teardown cancelled publication. That handle belongs to
        // this invocation and must be finalized exactly once instead of being leaked or queued.
        if (started != null) {
            runCatching { if (!started.isStopped) started.stop() }
            deleteQuietly(started.output)
        } else if (!observing) {
            deleteQuietly(output)
        }
    }

    private fun stopObservation() {
        val activeObserver: Thread?
        synchronized(this) {
            observing = false
            activeObserver = observer
            observer = null
        }
        if (activeObserver != null && activeObserver !== Thread.currentThread()) {
            activeObserver.interrupt()
            // This is only a response bound. Correctness does not depend on the native starter
            // returning: observeUntilStarted owns and disposes any handle that completes later.
            runCatching { activeObserver.join(OBSERVER_JOIN_TIMEOUT_MILLIS) }
        }
    }

    private fun publishMissing(
        publishReport: (key: String, value: String) -> Unit,
        reason: String,
    ) {
        val message = "Failure video was requested but not produced: $reason"
        System.err.println("Spectre: $message")
        publishReportSafely(publishReport, MISSING_REPORT_ENTRY_KEY, message)
    }

    private fun finalizedVideoFailure(path: Path): String? {
        if (!Files.isRegularFile(path)) return "recorder finalized without producing $path"
        if (!Files.isReadable(path)) return "recorder finalized an unreadable video at $path"
        val size = runCatching {
            Files.size(path)
        }
            .getOrElse {
                return "recorder finalized an unreadable video at $path: ${it.describeForReport()}"
            }
        return if (size > 0L) null else "recorder finalized a zero-byte video at $path"
    }

    /** Best-effort remove empty parents up to reports root (never delete reports root). */
    private fun removeEmptyParentsTowardReportsRoot(start: Path?) {
        val root = config.reportsRoot.toAbsolutePath().normalize()
        var parent = start
        while (parent != null) {
            val normalized = parent.toAbsolutePath().normalize()
            val stop =
                normalized == root || !normalized.startsWith(root) || !isEmptyDirectory(parent)
            if (stop) return
            Files.deleteIfExists(parent)
            parent = parent.parent
        }
    }

    private fun isEmptyDirectory(directory: Path): Boolean = runCatching {
        Files.isDirectory(directory) && Files.list(directory).use { it.findFirst().isEmpty }
    }
        .getOrDefault(false)

    internal companion object {
        private const val OBSERVER_JOIN_TIMEOUT_MILLIS: Long = 100
        const val VIDEO_FILE_NAME: String = "failure-video.mp4"
        const val REPORT_ENTRY_KEY: String = "spectre.failureVideo"
        const val MISSING_REPORT_ENTRY_KEY: String = "spectre.failureVideoMissing"
    }
}

/** Starts a [RecordingHandle] writing to [output] for the current [automator] windows. */
internal fun interface FailureVideoStarter {
    fun start(output: Path, automator: ComposeAutomator): RecordingHandle?

    fun start(
        output: Path,
        automator: ComposeAutomator,
        scope: FailureVideoScope,
    ): RecordingHandle? = start(output, automator)
}

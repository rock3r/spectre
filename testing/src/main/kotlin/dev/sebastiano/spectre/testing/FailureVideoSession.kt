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

    val activeOutput: Path?
        get() = outputPath

    val hasActiveRecorder: Boolean
        get() = handle != null

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
        val startedResult = runCatching {
            Files.createDirectories(methodDir)
            starter.start(output, automator)
        }
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
            if (Files.isRegularFile(finalized)) {
                publishReport(REPORT_ENTRY_KEY, finalized.toAbsolutePath().normalize().toString())
            } else {
                publishMissing(publishReport, "recorder finalized without producing $finalized")
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

    private fun publishMissing(
        publishReport: (key: String, value: String) -> Unit,
        reason: String,
    ) {
        val message = "Failure video was requested but not produced: $reason"
        System.err.println("Spectre: $message")
        publishReport(MISSING_REPORT_ENTRY_KEY, message)
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
        const val VIDEO_FILE_NAME: String = "failure-video.mp4"
        const val REPORT_ENTRY_KEY: String = "spectre.failureVideo"
        const val MISSING_REPORT_ENTRY_KEY: String = "spectre.failureVideoMissing"
    }
}

/** Starts a [RecordingHandle] writing to [output] for the current [automator] windows. */
internal fun interface FailureVideoStarter {
    fun start(output: Path, automator: ComposeAutomator): RecordingHandle?
}

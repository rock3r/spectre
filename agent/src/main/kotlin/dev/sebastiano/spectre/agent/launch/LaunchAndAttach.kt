package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Launch a command, wait through staged readiness, attach Spectre, and return a [LaunchedSession]
 * that tears the app down on [AutoCloseable.close].
 *
 * Stages (each with its own timeout and failure type):
 * 1. [LaunchStage.PROCESS_ALIVE] — process still running after start
 * 2. [LaunchStage.JVM_ATTACHABLE] — target JVM visible to the Attach API
 * 3. [LaunchStage.AGENT_BOOTSTRAP] — agent load + ComposeAutomator bootstrap + UDS
 * 4. [LaunchStage.FIRST_WINDOW] — `windows()` non-empty
 *
 * Direct `java` launches inject `-XX:+EnableDynamicAgentLoading` (unless disabled). Gradle-ish
 * commands emit [LaunchCommandRewriter.gradleLaunchWarning] and discover the app JVM among
 * descendants of the client process.
 */
@ExperimentalSpectreAgentApi
public object LaunchAndAttach {

    /**
     * Start [spec], run staged readiness, and attach. On any stage failure the launched process
     * tree (or discovered app JVM for Gradle) is torn down before the exception propagates.
     *
     * @param warningSink receives loud operational warnings (e.g. Gradle-ish launch caveats).
     *   Defaults to [System.err]. Warnings are also stored on [LaunchedSession.warnings].
     */
    @Throws(LaunchException::class, IOException::class)
    public fun launch(
        spec: LaunchSpec,
        warningSink: (String) -> Unit = DEFAULT_WARNING_SINK,
    ): LaunchedSession {
        require(spec.command.isNotEmpty()) { "command must not be empty" }
        val prepared = prepareLaunch(spec)
        for (warning in prepared.warnings) {
            warningSink(warning)
        }
        val process = startProcess(prepared.command, spec, prepared.stdoutPath, prepared.stderrPath)
        return attachAfterStart(process, prepared, spec)
    }

    /** Default warning destination — stderr so interactive tools and CI logs surface it. */
    public val DEFAULT_WARNING_SINK: (String) -> Unit = { message -> System.err.println(message) }

    private data class PreparedLaunch(
        val command: List<String>,
        val gradleish: Boolean,
        val warnings: List<String>,
        val stdoutPath: Path,
        val stderrPath: Path,
    )

    private fun prepareLaunch(spec: LaunchSpec): PreparedLaunch {
        val gradleish = LaunchCommandRewriter.isGradleishLaunch(spec.command)
        val warnings =
            if (gradleish) listOf(LaunchCommandRewriter.gradleLaunchWarning()) else emptyList()
        val command = rewriteCommand(spec)
        maybeRequireSpectreCore(spec, command)
        val captureDir =
            (spec.captureDirectory ?: Files.createTempDirectory("spectre-launch-")).also {
                Files.createDirectories(it)
            }
        return PreparedLaunch(
            command = command,
            gradleish = gradleish,
            warnings = warnings,
            stdoutPath = captureDir.resolve("stdout.log"),
            stderrPath = captureDir.resolve("stderr.log"),
        )
    }

    private fun rewriteCommand(spec: LaunchSpec): List<String> =
        if (LaunchCommandRewriter.isDirectJvmLaunch(spec.command)) {
            LaunchCommandRewriter.rewriteDirectJvmCommand(
                command = spec.command,
                extraJvmArgs = spec.extraJvmArgs,
                inject = spec.injectDynamicAgentLoading,
            )
        } else {
            spec.command
        }

    private fun maybeRequireSpectreCore(spec: LaunchSpec, command: List<String>) {
        if (!spec.requireSpectreCoreOnClasspath) return
        if (!LaunchCommandRewriter.isDirectJvmLaunch(command)) return
        val cp = LaunchCommandRewriter.extractClasspath(command) ?: return
        if (!LaunchCommandRewriter.classpathContainsSpectreCore(cp)) {
            throw SpectreCoreMissingOnClasspathException(cp)
        }
    }

    private fun startProcess(
        command: List<String>,
        spec: LaunchSpec,
        stdoutPath: Path,
        stderrPath: Path,
    ): Process {
        val builder = ProcessBuilder(command)
        if (spec.workingDirectory != null) {
            builder.directory(spec.workingDirectory.toFile())
        }
        if (spec.environment.isNotEmpty()) {
            builder.environment().putAll(spec.environment)
        }
        builder.redirectOutput(stdoutPath.toFile())
        builder.redirectError(stderrPath.toFile())
        return try {
            builder.start()
        } catch (ex: IOException) {
            throw ProcessExitedBeforeAttachException(
                exitCode = -1,
                stderrExcerpt = "Failed to start process: ${ex.message}",
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                cause = ex,
            )
        }
    }

    private fun attachAfterStart(
        process: Process,
        prepared: PreparedLaunch,
        spec: LaunchSpec,
    ): LaunchedSession {
        val launchedPid = process.pid()
        var attachedPid: Long? = null
        var automator: AttachedAutomator? = null
        var ready = false
        try {
            LaunchReadiness.awaitProcessAlive(
                process,
                spec.stageTimeouts.processAliveMs,
                prepared.stdoutPath,
                prepared.stderrPath,
            )
            attachedPid =
                LaunchReadiness.awaitJvmAttachable(
                    process = process,
                    launchedPid = launchedPid,
                    gradleish = prepared.gradleish,
                    nameFilter = spec.appJvmNameFilter,
                    timeoutMs = spec.stageTimeouts.jvmAttachableMs,
                    stdoutPath = prepared.stdoutPath,
                    stderrPath = prepared.stderrPath,
                )
            automator =
                LaunchReadiness.awaitAgentBootstrap(
                    process = process,
                    attachedPid = attachedPid,
                    gradleish = prepared.gradleish,
                    attachOptions = spec.attachOptions,
                    bootstrapTimeoutMs = spec.stageTimeouts.agentBootstrapMs,
                    stdoutPath = prepared.stdoutPath,
                    stderrPath = prepared.stderrPath,
                )
            LaunchReadiness.awaitFirstWindow(
                automator = automator,
                attachedPid = attachedPid,
                timeoutMs = spec.stageTimeouts.firstWindowMs,
                stdoutPath = prepared.stdoutPath,
                stderrPath = prepared.stderrPath,
            )
            ready = true
            return LaunchedSession(
                launchedPid = launchedPid,
                attachedPid = attachedPid,
                automator = automator,
                stdoutPath = prepared.stdoutPath,
                stderrPath = prepared.stderrPath,
                gradleish = prepared.gradleish,
                warnings = prepared.warnings,
                onClose = { teardownApp(process, prepared.gradleish, launchedPid, attachedPid) },
            )
        } finally {
            if (!ready) {
                runCatching { automator?.close() }
                teardownApp(process, prepared.gradleish, launchedPid, attachedPid)
            }
        }
    }

    private fun teardownApp(
        process: Process,
        gradleish: Boolean,
        launchedPid: Long,
        attachedPid: Long?,
    ) {
        if (gradleish) {
            // Prefer the discovered app JVM. Never kill a Gradle daemon by display name.
            if (attachedPid != null) {
                ProcessTreeTeardown.destroyTreeByPid(attachedPid)
            }
            // If we never discovered an app JVM, destroy only the *launched client* process
            // (./gradlew wrapper). Do not walk its descendants blindly — that graph can include
            // the long-lived daemon on some layouts.
            if (attachedPid == null && process.isAlive) {
                runCatching { process.destroy() }
                if (process.isAlive) {
                    process.waitFor(
                        ProcessTreeTeardown.DEFAULT_GRACE_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
            return
        }
        ProcessTreeTeardown.destroyTreeByPid(launchedPid)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}

package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.launch.LaunchAndAttach
import dev.sebastiano.spectre.agent.launch.LaunchException
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.cli.daemon.EmbeddedAgentRuntime
import java.io.IOException
import java.nio.file.Path

/**
 * Launch a command, wait through staged readiness, attach Spectre, print a summary, then tear down
 * when the command exits this process (or when `--once` finishes the first window check).
 *
 * Prefer prod-like launches. Gradle `run` / `hotRun` are supported but warn loudly.
 *
 * Examples:
 * ```
 * spectre launch -- java -jar app/build/libs/app.jar
 * spectre launch -C /path/to/repo -- ./gradlew :app:run
 * spectre launch --once -- java -cp fixture.jar FixtureMain
 * ```
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal class LaunchCommand(private val output: Appendable, private val errorOutput: Appendable) :
    CliktCommand(name = "launch") {
    private val workingDirectory: Path? by
        option("-C", "--directory", help = "Working directory for the launched process")
            .path(mustExist = true, canBeFile = false, canBeDir = true)
    private val nameFilter: String? by
        option(
            "--app-name",
            help =
                "Substring of the app JVM display name for Gradle-ish launches " +
                    "(recommended when using ./gradlew)",
        )
    private val once: Boolean by
        option(
                "--once",
                help =
                    "Exit after staged readiness succeeds (print windows count) instead of holding " +
                        "the session open until stdin EOF / interrupt",
            )
            .flag(default = false)
    private val command: List<String> by
        argument(help = "Command to launch; place after -- so flags are not consumed by spectre")
            .multiple(required = true)

    override fun run() {
        if (command.isEmpty()) {
            throw CliktError("launch requires a command after --")
        }
        // Released CLI packages embed agent-runtime; ensure AttachOptions can resolve it.
        installEmbeddedAgentRuntimeForLaunch()
        val spec =
            LaunchSpec(
                command = command,
                workingDirectory = workingDirectory,
                appJvmNameFilter = nameFilter,
            )
        try {
            LaunchAndAttach.launch(spec) { warning ->
                    errorOutput.append(warning)
                    errorOutput.appendLine()
                }
                .use { session ->
                    output.append("launchedPid=${session.launchedPid}")
                    output.append(" attachedPid=${session.attachedPid}")
                    output.append(" gradleish=${session.gradleish}")
                    output.appendLine()
                    output.append("stdout=${session.stdoutPath}")
                    output.appendLine()
                    output.append("stderr=${session.stderrPath}")
                    output.appendLine()
                    val windows = session.automator.windows()
                    output.append("windows=${windows.size}")
                    output.appendLine()
                    if (!once) {
                        output.append(
                            "Session open — press Enter or send EOF to detach and tear down."
                        )
                        output.appendLine()
                        // Hold the session for interactive use; teardown on close.
                        try {
                            System.`in`.read()
                        } catch (_: IOException) {
                            // stdin closed
                        }
                    }
                }
        } catch (ex: LaunchException) {
            errorOutput.append("Spectre launch error [${ex.stage}]: ${ex.message}")
            errorOutput.appendLine()
            throw ProgramResult(EXIT_ATTACH_FAILURE)
        } catch (ex: IOException) {
            errorOutput.append("Spectre launch I/O error: ${ex.message}")
            errorOutput.appendLine()
            throw ProgramResult(EXIT_ATTACH_FAILURE)
        }
    }

    private companion object {
        const val EXIT_ATTACH_FAILURE: Int = 3
    }
}

private fun installEmbeddedAgentRuntimeForLaunch() {
    val prop = "dev.sebastiano.spectre.agent.runtimeJar"
    if (System.getProperty(prop) != null) return
    EmbeddedAgentRuntime.install()?.let { runtime -> System.setProperty(prop, runtime.toString()) }
}

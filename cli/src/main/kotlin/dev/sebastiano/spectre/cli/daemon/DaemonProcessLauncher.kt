package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Starts a detached JVM hosting [DaemonMain] for one local socket endpoint. */
public class DaemonProcessLauncher(
    private val socketPath: Path,
    private val javaExecutable: String = defaultJavaExecutable(),
    private val classPath: String = System.getProperty("java.class.path"),
) {
    private var startupErrorLog: Path? = null
    private var daemonProcess: Process? = null

    /** Launches the daemon process without inheriting this client's standard streams. */
    @Throws(IOException::class)
    public fun start(): Process {
        val errorLog = Files.createTempFile("spectre-daemon-startup-", ".log")
        startupErrorLog = errorLog
        return ProcessBuilder(command())
            .also { restoreBundledRuntimeExecutePermissions() }
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errorLog.toFile())
            .start()
            .also { daemonProcess = it }
    }

    /** Returns and removes any diagnostic emitted before the daemon reached its socket. */
    public fun consumeStartupError(): String? =
        startupErrorLog
            ?.takeIf { daemonProcess?.isAlive != true }
            ?.let { errorLog ->
                runCatching { Files.readString(errorLog).trim().ifEmpty { null } }
                    .getOrNull()
                    .also { Files.deleteIfExists(errorLog) }
            }

    /** Removes the startup diagnostic after a confirmed daemon exits. */
    public fun discardStartupError() {
        val errorLog = startupErrorLog ?: return
        val process = daemonProcess
        if (process?.isAlive == true) {
            process.onExit().thenRun { Files.deleteIfExists(errorLog) }
        } else {
            Files.deleteIfExists(errorLog)
        }
    }

    /** Returns the isolated daemon command without starting a process. */
    public fun command(): List<String> = buildList {
        add(javaExecutable)
        addAll(agentRuntimePropertyArgument())
        add("-cp")
        add(classPath)
        add(DAEMON_MAIN_CLASS)
        add("--socket")
        add(socketPath.toString())
    }

    private fun agentRuntimePropertyArgument(): List<String> =
        System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
            ?.let { listOf("-Ddev.sebastiano.spectre.agent.runtimeJar=$it") }
            .orEmpty()

    private fun restoreBundledRuntimeExecutePermissions() {
        if (javaExecutable != defaultJavaExecutable()) return
        val javaPath = Path.of(javaExecutable)
        javaPath.toFile().setExecutable(true, false)
        javaPath.parent.parent
            .resolve("lib")
            .resolve("jspawnhelper")
            .toFile()
            .setExecutable(true, false)
    }

    private companion object {
        private const val DAEMON_MAIN_CLASS: String =
            "dev.sebastiano.spectre.cli.daemon.DaemonMainKt"

        private fun defaultJavaExecutable(): String {
            val executable =
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "java.exe"
                } else {
                    "java"
                }
            return Path.of(System.getProperty("java.home"), "bin", executable).toString()
        }
    }
}

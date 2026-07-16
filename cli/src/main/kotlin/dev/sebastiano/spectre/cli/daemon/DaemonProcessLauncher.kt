package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.nio.file.Path

/** Starts a detached JVM hosting [DaemonMain] for one local socket endpoint. */
public class DaemonProcessLauncher(
    private val socketPath: Path,
    private val javaExecutable: String = defaultJavaExecutable(),
    private val classPath: String = System.getProperty("java.class.path"),
) {
    /** Launches the daemon process without inheriting this client's standard streams. */
    @Throws(IOException::class)
    public fun start(): Process =
        ProcessBuilder(command())
            .also { restoreBundledRuntimeExecutePermissions() }
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // Surface a daemon startup failure to the invoking CLI instead of reporting only
            // the missing socket after its connection retry window expires.
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

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

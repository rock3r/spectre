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
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

    /** Returns the isolated daemon command without starting a process. */
    public fun command(): List<String> =
        listOf(
            javaExecutable,
            "-cp",
            classPath,
            DAEMON_MAIN_CLASS,
            "--socket",
            socketPath.toString(),
        )

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

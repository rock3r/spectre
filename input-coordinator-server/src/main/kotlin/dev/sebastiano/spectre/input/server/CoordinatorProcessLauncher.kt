@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import java.io.IOException
import java.nio.file.Path
import java.time.Duration

/** Starts a dedicated coordinator JVM from the supplied runtime classpath. */
@ExperimentalSpectreInputCoordinationApi
public class CoordinatorProcessLauncher(
    private val endpoint: CoordinatorEndpoint,
    private val javaExecutable: String = defaultJavaExecutable(),
    private val classPath: String = System.getProperty("java.class.path"),
    private val idleTimeout: Duration = Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS),
) {
    init {
        require(!idleTimeout.isNegative && !idleTimeout.isZero) { "Idle timeout must be positive" }
    }

    /** Launches the coordinator without inheriting client standard streams. */
    @Throws(IOException::class)
    public fun start(): Process =
        ProcessBuilder(command())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

    /** Returns the dedicated coordinator Java command without starting it. */
    public fun command(): List<String> =
        listOf(
            javaExecutable,
            "-cp",
            classPath,
            COORDINATOR_MAIN_CLASS,
            "--socket",
            endpoint.socketPath.toString(),
            "--idle-millis",
            idleTimeout.toMillis().toString(),
        )

    private companion object {
        const val COORDINATOR_MAIN_CLASS: String =
            "dev.sebastiano.spectre.input.server.CoordinatorProcessMainKt"
        const val DEFAULT_IDLE_TIMEOUT_SECONDS: Long = 30

        fun defaultJavaExecutable(): String {
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

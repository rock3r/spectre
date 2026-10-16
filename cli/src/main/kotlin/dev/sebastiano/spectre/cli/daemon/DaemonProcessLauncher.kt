package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AttachInputCoordination
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits
import dev.sebastiano.spectre.cli.bootstrap.AotCache
import dev.sebastiano.spectre.cli.bootstrap.SpectreBootstrap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Starts a detached JVM hosting [DaemonMain] for one local socket endpoint. */
@OptIn(ExperimentalSpectreAgentApi::class)
public class DaemonProcessLauncher(
    private val socketPath: Path,
    private val javaExecutable: String = defaultJavaExecutable(),
    private val classPath: String = System.getProperty("java.class.path"),
    private val readProperty: (String) -> String? = System::getProperty,
    private val aotJvmArguments: () -> List<String> = { AotCache.daemonJvmArguments(classPath) },
    private val callerJvmOptions: () -> List<String> = {
        SpectreBootstrap.callerJvmOptions(
            java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
        )
    },
) {
    private var startupErrorLog: Path? = null
    private var daemonProcess: Process? = null

    /** Launches the daemon process without inheriting this client's standard streams. */
    @Throws(IOException::class)
    public fun start(): Process {
        val errorLog = Files.createTempFile("spectre-daemon-startup-", ".log")
        startupErrorLog = errorLog
        return processBuilder()
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

    /**
     * The builder for the daemon process: the isolated command, with the JVM option environment
     * variables removed from the inherited environment. The daemon's options are reconstructed
     * explicitly on its command line (see [command]); a thin start-script launch keeps
     * `JAVA_TOOL_OPTIONS` & friends in the CLI's environment, and leaving them in the daemon's
     * would apply them a second time — agents initialized twice, an environment collector colliding
     * with the cache-consuming daemon's pinned G1.
     */
    internal fun processBuilder(): ProcessBuilder =
        ProcessBuilder(command()).also { sanitizeDaemonEnvironment(it.environment()) }

    /** Returns the isolated daemon command without starting a process. */
    public fun command(): List<String> = buildList {
        add(javaExecutable)
        // JDK 25 Leyden cache, when the foreground launch already trained one for this exact
        // jar. The daemon never trains: it is detached and long-lived, so a training run here
        // would race the foreground's cache promotion and could not be promoted on exit.
        addAll(aotJvmArguments())
        // The caller's JVM options (heap, module access) reach the daemon only
        // through this list: the bootstrap scrubbed the option environment variables
        // (JAVA_TOOL_OPTIONS & friends) out of the CLI's environment so they could not
        // collide with the frontend contract, and re-applied the supported options on
        // the CLI's own command line — which is where this list reads them from.
        // Collector selectors and AOT options stay behind (see SpectreBootstrap):
        // the daemon keeps its own ergonomics and consumes the managed cache.
        // Instrumentation agents stay behind too: an agent belongs to the CLI
        // process that was launched with it — a JDWP agent would fail to bind its
        // listen address a second time in the daemon.
        addAll(callerJvmOptions().filterNot(SpectreBootstrap::isInstrumentationAgentArgument))
        addAll(forwardedPropertyArguments())
        add("-cp")
        add(classPath)
        add(DAEMON_MAIN_CLASS)
        add("--socket")
        add(socketPath.toString())
        // Pin the budget whenever one was explicitly asked for, even if it equals the default:
        // the daemon inherits this process's environment, so `--max-frame-bytes 64MiB` over a
        // `SPECTRE_MAX_FRAME_BYTES=128MiB` would otherwise boot the daemon on 128MiB. With no
        // explicit request the daemon resolves the same environment and lands on the same value.
        FrameLimits.requestedMaxFrameBytes?.let { budget ->
            add("--max-frame-bytes")
            add(budget.toString())
        }
    }

    /**
     * Re-states on the daemon's command line the switches this process was given.
     *
     * The daemon is a fresh JVM launched from an explicit argument list, so it inherits this
     * process's environment but none of its `-D`s. Anything the daemon reads as a system property
     * has to be listed here or a user who sets it on the CLI watches it be silently ignored — which
     * for [AttachInputCoordination.PROPERTY] would look exactly like an escape hatch that does not
     * work, since `DaemonSessionRegistry` attaches with no `AttachOptions` at all and the property
     * is that user's only channel into the decision.
     */
    private fun forwardedPropertyArguments(): List<String> =
        FORWARDED_PROPERTIES.mapNotNull { name ->
            readProperty(name)?.let { value -> "-D$name=$value" }
        }

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

    public companion object {
        /** Removes the JVM option environment variables from a daemon environment. */
        internal fun sanitizeDaemonEnvironment(environment: MutableMap<String, String>) {
            SpectreBootstrap.sanitizeChildEnvironment(environment)
        }

        private const val DAEMON_MAIN_CLASS: String =
            "dev.sebastiano.spectre.cli.daemon.DaemonMainKt"

        /** System properties the daemon reads, and therefore has to be handed. */
        private val FORWARDED_PROPERTIES: List<String> =
            listOf("dev.sebastiano.spectre.agent.runtimeJar", AttachInputCoordination.PROPERTY)

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

package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonProcessLauncherTest {
    @Test
    fun `builds a daemon-only Java command for its socket`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    // No forwarded -D properties: the real defaults resolve
                    // user.home and java.io.tmpdir on any host, which would
                    // make the expected command host-dependent.
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-cp",
                "spectre.jar",
                "dev.sebastiano.spectre.cli.daemon.DaemonMainKt",
                "--socket",
                Path.of("/tmp", "spectre", "daemon.sock").toString(),
            ),
            command,
        )
    }

    @Test
    fun `includes ready AOT cache arguments for the daemon JVM`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    aotJvmArguments = { listOf("-XX:AOTCache=/tmp/aot/spectre.aot") },
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-XX:AOTCache=/tmp/aot/spectre.aot",
                "-cp",
                "spectre.jar",
                "dev.sebastiano.spectre.cli.daemon.DaemonMainKt",
                "--socket",
                Path.of("/tmp", "spectre", "daemon.sock").toString(),
            ),
            command,
        )
    }

    // ---- forwarded switches (#472) ----
    //
    // The daemon is a fresh JVM with an explicit command line, so a system property set on the CLI
    // reaches it only if this launcher passes it on. `DaemonSessionRegistry` attaches with
    // `AgentAttach.attach(pid)` and no options, which makes the property the only channel a CLI
    // user has for the coordination opt-out — a `-D` the CLI silently dropped would look exactly
    // like an opt-out that does not work.

    @Test
    fun `forwards the input coordination opt-out to the daemon`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { name ->
                        "disabled"
                            .takeIf { name == "dev.sebastiano.spectre.agent.inputCoordination" }
                    },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf("-Ddev.sebastiano.spectre.agent.inputCoordination=disabled"),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards the agent runtime jar override`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { name ->
                        "/jars/agent.jar"
                            .takeIf { name == "dev.sebastiano.spectre.agent.runtimeJar" }
                    },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf("-Ddev.sebastiano.spectre.agent.runtimeJar=/jars/agent.jar"),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards standard properties that shape the daemon's JVM state`() {
        // Regression for #551: the daemon is a fresh JVM launched from an
        // explicit argument list, so a standard property a caller sets on the
        // CLI launch never reaches it unless it is restated here. The daemon
        // reads JVM state these shape — the temp and home directories its
        // captures and runtime files land under, and headless mode for
        // Robot-driven input — so a caller's override silently stopping at
        // the CLI means captures in the wrong place or failures in a sandbox
        // whose default home is unwritable.
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { name ->
                        mapOf(
                            "java.io.tmpdir" to "/custom-tmp",
                            "user.home" to "/writable-home",
                            "java.awt.headless" to "false",
                        )[name]
                    },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf(
                "-Djava.io.tmpdir=/custom-tmp",
                "-Duser.home=/writable-home",
                "-Djava.awt.headless=false",
            ),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards caller JVM options to the daemon`() {
        // JVM options the caller gave the CLI (via JAVA_TOOL_OPTIONS & friends, or
        // JAVA_OPTS/SPECTRE_OPTS) are reconstructed onto the CLI's own command line
        // by the bootstrap; the daemon is a fresh JVM, so they reach it only if this
        // launcher passes them on — a caller's -Xmx1g must not stop at the
        // short-lived CLI front end. Instrumentation agents are the exception:
        // they belong to the CLI process alone (a JDWP agent would fail to bind
        // its listen address a second time in the daemon).
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    aotJvmArguments = { listOf("-XX:AOTCache=/tmp/aot/spectre.aot") },
                    readProperty = { null },
                    callerJvmOptions = {
                        listOf(
                            "-Xmx1g",
                            "-javaagent:/tmp/agent.jar",
                            "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
                            "-XX:MaxGCPauseMillis=50",
                        )
                    },
                )
                .command()

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-XX:AOTCache=/tmp/aot/spectre.aot",
                "-Xmx1g",
                "-XX:MaxGCPauseMillis=50",
                "-cp",
                "spectre.jar",
                "dev.sebastiano.spectre.cli.daemon.DaemonMainKt",
                "--socket",
                Path.of("/tmp", "spectre", "daemon.sock").toString(),
            ),
            command,
        )
    }

    @Test
    fun `daemon environment drops inherited JVM option variables`() {
        // The daemon's options are reconstructed explicitly on its command line;
        // leaving JAVA_TOOL_OPTIONS & friends in its environment would apply
        // them a second time (agents twice, or an env collector colliding with
        // the cache's pinned G1). Thin start-script launches keep those
        // variables in the CLI's environment, so the scrub must happen here.
        val environment =
            mutableMapOf(
                "JAVA_TOOL_OPTIONS" to "-XX:+UseZGC",
                "JDK_JAVA_OPTIONS" to "-Xmx1g",
                "_JAVA_OPTIONS" to "-Dfoo=bar",
                "PATH" to "/usr/bin",
            )

        DaemonProcessLauncher.sanitizeDaemonEnvironment(environment)

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
    }

    @Test
    fun `forwards nothing when nothing is set`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(emptyList(), command.filter { it.startsWith("-D") })
    }
}

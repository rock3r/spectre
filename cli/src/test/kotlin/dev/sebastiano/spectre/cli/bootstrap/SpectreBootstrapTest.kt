package dev.sebastiano.spectre.cli.bootstrap

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpectreBootstrapTest {

    // Paths are built through Path so the expected commands use this host's separator
    // (these tests previously hardcoded '/' and only passed on Unix).
    private val javaExecutable = Path.of("/jdk", "bin", "java").toString()
    private val jarFile = Path.of("/opt", "spectre", "cli.jar")

    // The frontend GC/representation contract every bootstrap-launched JVM shares:
    // explicit G1 (JDK 25 Leyden does not support the launcher-default ZGC) with the
    // archived-heap representation pinned. UseCompressedClassPointers is absent on
    // purpose: it defaults to true next to compressed oops, and naming it makes
    // JDK 25 warn about deprecation on stderr.
    private val frontendArguments =
        listOf(
            "-XX:+UseG1GC",
            "-XX:+UseCompressedOops",
            "-XX:-UseCompactObjectHeaders",
            "-XX:ObjectAlignmentInBytes=8",
        )

    @Test
    fun `child command launches the real CLI entry point with the planned AOT flags`() {
        val cache = Path.of("/tmp", "aot", "spectre.aot")
        val plan =
            AotCache.Plan(
                AotCache.Mode.PRODUCTION,
                cache,
                null,
                listOf("-XX:AOTCache=$cache"),
            )

        val command =
            SpectreBootstrap.childCommand(
                javaExecutable,
                plan,
                jarFile,
                arrayOf("daemon", "status"),
                25,
                emptyList(),
            )

        assertEquals(
            listOf(
                javaExecutable,
                *frontendArguments.toTypedArray(),
                "-XX:AOTCache=$cache",
                // A rejected cache (corrupt, foreign) makes the JVM print [aot] lines
                // to stdout by default; stdout is a protocol surface (spectre mcp,
                // --json), so production launches silence Leyden logging entirely.
                "-Xlog:aot=off",
                // The CLI loads JNA at startup; without this, JDK 24+ prints a
                // restricted-method warning to stderr on every invocation.
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                jarFile.toString(),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "daemon",
                "status",
            ),
            command,
        )
    }

    @Test
    fun `training command records a discarded help run`() {
        val cache = Path.of("/tmp", "aot", "spectre.aot")
        val training = Path.of("/tmp", "aot", "spectre.aot.training-42")
        val plan =
            AotCache.Plan(
                AotCache.Mode.TRAINING,
                cache,
                training,
                listOf(
                    "-XX:AOTMode=record",
                    "-XX:AOTConfiguration=$training.config",
                ),
            )

        val command = SpectreBootstrap.trainingCommand(javaExecutable, plan, jarFile, 25)

        // The record run must carry the same frontend arguments and
        // --enable-native-access as the production child: JEP 483 requires module
        // options to be identical between training and production, or the JVM
        // rejects the cache.
        assertEquals(
            listOf(
                javaExecutable,
                *frontendArguments.toTypedArray(),
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=$training.config",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                jarFile.toString(),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "--help",
            ),
            command,
        )
    }

    @Test
    fun `assembly command creates the training cache without running the CLI`() {
        val cache = Path.of("/tmp", "aot", "spectre.aot")
        val training = Path.of("/tmp", "aot", "spectre.aot.training-42")
        val plan =
            AotCache.Plan(
                AotCache.Mode.TRAINING,
                cache,
                training,
                listOf(
                    "-XX:AOTMode=record",
                    "-XX:AOTConfiguration=$training.config",
                ),
            )

        val command = SpectreBootstrap.assemblyCommand(javaExecutable, plan, jarFile, 25)

        assertEquals(
            listOf(
                javaExecutable,
                *frontendArguments.toTypedArray(),
                "-XX:AOTMode=create",
                "-XX:AOTConfiguration=$training.config",
                "-XX:AOTCacheOutput=$training",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                jarFile.toString(),
            ),
            command,
        )
    }

    @Test
    fun `child command omits the native access flag before JDK 24`() {
        // --enable-native-access exists earlier, but the bootstrap only promises it
        // where it is needed to silence restricted-method warnings; an unknown-option
        // failure on an old JVM must stay impossible by construction.
        val plan = AotCache.Plan(AotCache.Mode.DISABLED, null, null, emptyList())

        val command =
            SpectreBootstrap.childCommand(
                javaExecutable,
                plan,
                jarFile,
                arrayOf("--help"),
                21,
                emptyList(),
            )

        assertFalse("--enable-native-access=ALL-UNNAMED" in command)
    }

    @Test
    fun `child command forwards the caller's system properties`() {
        // -D properties given to the bootstrap JVM (java -D..., JAVA_OPTS,
        // SPECTRE_OPTS) are read by the real CLI and its daemon launcher; they
        // must reach the child instead of stopping at the bootstrap.
        val plan = AotCache.Plan(AotCache.Mode.DISABLED, null, null, emptyList())

        val command =
            SpectreBootstrap.childCommand(
                javaExecutable,
                plan,
                jarFile,
                arrayOf("--help"),
                25,
                SpectreBootstrap.callerSystemProperties(
                    listOf(
                        "-XX:+UseZGC",
                        "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                        "-Xmx512m",
                        "-Ddev.sebastiano.spectre.agent.inputCoordination=disabled",
                    )
                ),
            )

        assertEquals(
            listOf(
                "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                "-Ddev.sebastiano.spectre.agent.inputCoordination=disabled",
            ),
            command.filter { it.startsWith("-D") },
        )
        assertFalse("-XX:+UseZGC" in command)
        assertFalse("-Xmx512m" in command)
    }

    @Test
    fun `only cache-backed plans exec a child JVM`() {
        fun plan(mode: AotCache.Mode) = AotCache.Plan(mode, null, null, emptyList())

        assertTrue(SpectreBootstrap.shouldExecInChild(plan(AotCache.Mode.PRODUCTION)))
        assertTrue(SpectreBootstrap.shouldExecInChild(plan(AotCache.Mode.TRAINING)))
        // Disabled still execs: packaged launches give the bootstrap JVM lean startup
        // flags, and the real CLI (notably the long-lived MCP server) must not inherit
        // them by running in-process.
        assertTrue(SpectreBootstrap.shouldExecInChild(plan(AotCache.Mode.DISABLED)))
        assertFalse(SpectreBootstrap.shouldExecInChild(plan(AotCache.Mode.UNAVAILABLE)))
    }

    @Test
    fun `java executable name follows the host OS`() {
        assertEquals(
            Path.of("/jdk", "bin", "java"),
            SpectreBootstrap.javaExecutable(Path.of("/jdk"), "Linux"),
        )
        assertEquals(
            Path.of("/jdk", "bin", "java.exe"),
            SpectreBootstrap.javaExecutable(Path.of("/jdk"), "Windows 11"),
        )
    }
}

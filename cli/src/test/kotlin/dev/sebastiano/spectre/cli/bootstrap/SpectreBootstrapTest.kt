package dev.sebastiano.spectre.cli.bootstrap

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

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

        val command =
            SpectreBootstrap.trainingCommand(javaExecutable, plan, jarFile, 25, emptyList())

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

        val command =
            SpectreBootstrap.assemblyCommand(javaExecutable, plan, jarFile, 25, emptyList())

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
                emptyList(),
            )

        assertFalse("--enable-native-access=ALL-UNNAMED" in command)
    }

    @Test
    fun `child command forwards the caller's system properties and JVM options`() {
        // -D properties and non-property JVM options (-Xmx, -javaagent,
        // --add-opens, ...) given to the bootstrap JVM (java ..., JAVA_OPTS,
        // SPECTRE_OPTS) are read by the real CLI and its daemon launcher; they
        // must reach the child instead of stopping at the bootstrap. Only the
        // frontend contract itself (collector selectors, AOT flags) stays
        // bootstrap-owned.
        val plan = AotCache.Plan(AotCache.Mode.DISABLED, null, null, emptyList())
        val inputArguments =
            listOf(
                "-XX:+UseZGC",
                "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                "-Xmx512m",
                "-Ddev.sebastiano.spectre.agent.inputCoordination=disabled",
            )

        val command =
            SpectreBootstrap.childCommand(
                javaExecutable,
                plan,
                jarFile,
                arrayOf("--help"),
                25,
                SpectreBootstrap.callerJvmOptions(inputArguments),
                SpectreBootstrap.callerSystemProperties(inputArguments),
            )

        assertEquals(
            listOf(
                "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                "-Ddev.sebastiano.spectre.agent.inputCoordination=disabled",
            ),
            command.filter { it.startsWith("-D") },
        )
        assertFalse("-XX:+UseZGC" in command)
        // The caller's heap choice reaches the real CLI; only the bootstrap's own
        // -Xmx64m (see below) is withheld.
        assertTrue("-Xmx512m" in command)
    }

    @Test
    fun `caller JVM options keep user options and drop the bootstrap and contract flags`() {
        val options =
            SpectreBootstrap.callerJvmOptions(
                listOf(
                    // The Roast bootstrap's own vmArgs: the real CLI must not inherit
                    // the 64 MiB heap cap or single-tier compilation.
                    "-XX:TieredStopAtLevel=1",
                    "-Xmx64m",
                    "-Xmx1g",
                    "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                    "-XX:+UseZGC",
                    "-XX:-UseG1GC",
                    "-XX:AOTCache=/tmp/user.aot",
                    "-javaagent:/tmp/agent.jar",
                    "--add-opens",
                    "java.base/java.lang=ALL-UNNAMED",
                    "-XX:MaxGCPauseMillis=50",
                    "-verbose:gc",
                    // Pinned by the frontend contract: forwarding a caller's different
                    // value would make every production launch reject the cache the
                    // contract trained, silently running uncached forever.
                    "-XX:-UseCompressedOops",
                    "-XX:ObjectAlignmentInBytes=16",
                    // The caller's native-access targets are forwarded (the
                    // option accumulates); the contract adds ALL-UNNAMED on top.
                    "--enable-native-access=ALL-UNNAMED",
                    // Not a JVM option shape the bootstrap forwards.
                    "spectre",
                )
            )

        assertEquals(
            listOf(
                "-Xmx1g",
                "-javaagent:/tmp/agent.jar",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "-XX:MaxGCPauseMillis=50",
                "-verbose:gc",
                "--enable-native-access=ALL-UNNAMED",
            ),
            options,
        )
    }

    @Test
    fun `caller representation flags survive when the plan consumes no cache`() {
        // With SPECTRE_AOT=off (DISABLED) or an unavailable cache, the child
        // consumes nothing the frontend contract trained, so a caller's
        // heap-representation choices must not be silently replaced by the
        // pinned opposites: forwarded after the pinned flags, they win.
        val inputArguments =
            listOf(
                "-XX:+UseCompactObjectHeaders",
                "-XX:-UseCompressedOops",
                "-XX:ObjectAlignmentInBytes=16",
                "-Xmx1g",
            )
        for (mode in listOf(AotCache.Mode.DISABLED, AotCache.Mode.UNAVAILABLE)) {
            assertEquals(
                inputArguments,
                SpectreBootstrap.callerJvmOptions(
                    inputArguments,
                    AotCache.Plan(mode, null, null, emptyList()),
                ),
            )
        }
        // A production child consumes the cache the contract trained; the
        // pinned representation stays non-negotiable there.
        assertEquals(
            listOf("-Xmx1g"),
            SpectreBootstrap.callerJvmOptions(
                inputArguments,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `module path options travel with the module options they serve`() {
        // --add-modules without --module-path leaves the child unable to find
        // the very modules the caller asked for; the two shape the module
        // graph together, so both the forwarded options and the module-option
        // set (which training, assembly, and the fingerprint all consume)
        // must carry them, in equals and two-token forms alike.
        val inputArguments =
            listOf(
                "--module-path",
                "/plugins",
                "--add-modules=my.plugin",
                "--upgrade-module-path=/upgrades",
            )

        assertEquals(inputArguments, SpectreBootstrap.callerModuleOptions(inputArguments))
        assertEquals(
            inputArguments,
            SpectreBootstrap.callerJvmOptions(
                inputArguments,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `short module path alias and caller native access targets are preserved`() {
        // `-p` is the short form of --module-path: same two-token, module-graph
        // treatment, or the child cannot find the caller's modules.
        val moduleInput = listOf("-p", "/plugins", "--add-modules=my.plugin")
        assertEquals(moduleInput, SpectreBootstrap.callerModuleOptions(moduleInput))
        assertEquals(
            moduleInput,
            SpectreBootstrap.callerJvmOptions(
                moduleInput,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )

        // A caller's --enable-native-access target names modules allowed to use
        // restricted native operations; dropping it and adding only ALL-UNNAMED
        // revokes that permission in the child. It shapes an archived module
        // property, so it also belongs to the module-option set every cache
        // phase (record, assembly, production, fingerprint) is built from.
        val nativeAccessInput = listOf("--enable-native-access=my.plugin")
        assertEquals(
            nativeAccessInput,
            SpectreBootstrap.callerJvmOptions(
                nativeAccessInput,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )
        assertEquals(nativeAccessInput, SpectreBootstrap.callerModuleOptions(nativeAccessInput))

        // The contract's own ALL-UNNAMED token is not a caller module option:
        // every cache phase injects it itself, and the managed child's input
        // arguments always contain it (injected after the foreground
        // fingerprint was computed), so counting it here would give the
        // daemon a different fingerprint and an eternally unavailable cache.
        assertEquals(
            nativeAccessInput,
            SpectreBootstrap.callerModuleOptions(
                listOf("--enable-native-access=ALL-UNNAMED", "--enable-native-access=my.plugin")
            ),
        )

        // --limit-modules limits the observable module universe: the same
        // module-graph treatment, in equals and two-token forms.
        val limitInput = listOf("--limit-modules", "java.base,my.plugin")
        assertEquals(limitInput, SpectreBootstrap.callerModuleOptions(limitInput))
        assertEquals(
            listOf("--limit-modules=java.base,my.plugin"),
            SpectreBootstrap.callerModuleOptions(listOf("--limit-modules=java.base,my.plugin")),
        )
        assertEquals(
            limitInput,
            SpectreBootstrap.callerJvmOptions(
                limitInput,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `standard X options survive when the plan consumes no cache`() {
        // -Xcomp, -Xint, -Xlog and friends are outside the managed child's
        // forwarding whitelist because an unknown option shape could risk the
        // cache or the protocol stdout. When the plan consumes no cache
        // (SPECTRE_AOT=off, or unavailable) that reason is gone, and dropping
        // them would silently change the real CLI's behavior.
        val inputArguments = listOf("-Xcomp", "-Xint", "-Xlog:class+load", "-Xmx1g")
        for (mode in listOf(AotCache.Mode.DISABLED, AotCache.Mode.UNAVAILABLE)) {
            assertEquals(
                inputArguments,
                SpectreBootstrap.callerJvmOptions(
                    inputArguments,
                    AotCache.Plan(mode, null, null, emptyList()),
                ),
            )
        }
        assertEquals(
            listOf("-Xmx1g"),
            SpectreBootstrap.callerJvmOptions(
                inputArguments,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `foreign collector selection makes the launch user-managed`() {
        assertFalse(SpectreBootstrap.hasForeignCollector(listOf("-XX:+UseG1GC")))
        assertFalse(SpectreBootstrap.hasForeignCollector(listOf("-XX:+UseCompressedOops")))
        assertTrue(SpectreBootstrap.hasForeignCollector(listOf("-XX:+UseZGC")))
        assertTrue(SpectreBootstrap.hasForeignCollector(listOf("-XX:+UseShenandoahGC")))
        // Disabling the pinned collector is a collector choice too: forwarded
        // after -XX:+UseG1GC it would leave the JVM with no collector selected
        // and exit during initialization.
        assertTrue(SpectreBootstrap.hasForeignCollector(listOf("-XX:-UseG1GC")))
        assertFalse(SpectreBootstrap.hasForeignCollector(listOf("-XX:-UseZGC")))
    }

    @Test
    fun `instrumentation agents keep the launch in one JVM`() {
        // A JVMTI agent (coverage, profiling) can install a class-file-load
        // hook; JDK 25 lists such hooks as a reason an AOT cache cannot load,
        // and training records without the caller's agent. Agent-bearing
        // launches therefore skip managed caching entirely — and, because any
        // agent may already hold a fixed resource in this JVM (see
        // `any agent keeps the launch in the bootstrap JVM`), they run the
        // real CLI in-process instead of re-executing a child.
        assertTrue(SpectreBootstrap.hasInstrumentationAgent(listOf("-javaagent:/tmp/jacoco.jar")))
        assertTrue(
            SpectreBootstrap.hasInstrumentationAgent(listOf("-agentlib:jdwp=transport=dt_socket"))
        )
        assertTrue(SpectreBootstrap.hasInstrumentationAgent(listOf("-agentpath:/tmp/profiler.so")))
        assertFalse(SpectreBootstrap.hasInstrumentationAgent(listOf("-Xmx1g", "-XX:+UseG1GC")))
        assertTrue(SpectreBootstrap.isInstrumentationAgentArgument("-javaagent:/tmp/jacoco.jar"))
        assertFalse(SpectreBootstrap.isInstrumentationAgentArgument("-Xmx1g"))
    }

    @Test
    fun `any agent keeps the launch in the bootstrap JVM`() {
        // Regression for #554: which agents bind a fixed resource in premain
        // cannot be recognised reliably — a JDWP agent holds its listen
        // address, jmx_prometheus_javaagent opens its exporter port, a
        // profiler may lock a file. A re-exec'd child carrying the identical
        // agent would fail to bind that resource during agent startup while
        // this JVM lives, so SpectreBootstrap.main runs every agent-bearing
        // launch in-process through mustRunInProcess, trading the fast fork
        // path for correctness on an explicit user customisation.
        assertTrue(
            SpectreBootstrap.mustRunInProcess(
                listOf("-javaagent:/tmp/jmx_prometheus_javaagent.jar=9404:exporter.yaml")
            )
        )
        assertTrue(
            SpectreBootstrap.mustRunInProcess(
                listOf("-agentlib:jdwp=transport=dt_socket,server=y,address=5005")
            )
        )
        assertTrue(SpectreBootstrap.mustRunInProcess(listOf("-agentpath:/tmp/libprofiler.so=x")))
        assertFalse(SpectreBootstrap.mustRunInProcess(listOf("-Xmx1g", "-XX:+UseG1GC")))
    }

    @Test
    fun `child spawn failure is reported instead of falling back to the bootstrap JVM`() {
        // The bootstrap JVM carries the packaged launcher's caps (-Xmx64m,
        // TieredStopAtLevel=1); running the full CLI there after a failed child
        // spawn would let a long-lived `spectre mcp` die later with an OOM
        // instead of failing here with the actual launch error. A command that
        // cannot be spawned at all must surface as a non-zero exit.
        assertEquals(
            1,
            SpectreBootstrap.runChild(listOf("/definitely/not/a/java-executable", "-version")),
        )
    }

    @Test
    fun `user-managed child carries the caller's options without the frontend contract`() {
        // A caller who supplies their own AOT cache or collector gets a child JVM
        // with exactly their options: no managed frontend arguments, no managed
        // cache, and none of the bootstrap's own lean-startup flags — the real
        // CLI (notably a long-lived `spectre mcp`) must not run capped at 64 MiB.
        val command =
            SpectreBootstrap.userManagedChildCommand(
                javaExecutable,
                jarFile,
                arrayOf("mcp"),
                listOf(
                    "-XX:TieredStopAtLevel=1",
                    "-Xmx64m",
                    "-XX:+UseZGC",
                    "-XX:AOTCache=/tmp/user.aot",
                    "-Xmx1g",
                    "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                ),
            )

        assertEquals(
            listOf(
                javaExecutable,
                "-XX:+UseZGC",
                "-XX:AOTCache=/tmp/user.aot",
                "-Xmx1g",
                "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/agent.jar",
                "-cp",
                jarFile.toString(),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "mcp",
            ),
            command,
        )
    }

    @Test
    fun `child environment drops inherited JVM option variables`() {
        // A caller collector in JAVA_TOOL_OPTIONS would collide with the pinned
        // G1 in the child ("Multiple garbage collectors selected"); the supported
        // -D properties are forwarded explicitly instead.
        val environment =
            mutableMapOf(
                "JAVA_TOOL_OPTIONS" to "-XX:+UseZGC",
                "JDK_JAVA_OPTIONS" to "-Xmx1g",
                "_JAVA_OPTIONS" to "-Dfoo=bar",
                "PATH" to "/usr/bin",
            )

        SpectreBootstrap.sanitizeChildEnvironment(environment)

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
    }

    @Test
    fun `promotion replaces an empty cache file but keeps a concurrent winner`(
        @TempDir directory: Path
    ) {
        val cache = directory.resolve("spectre.aot")
        val training = directory.resolve("spectre.aot.training-1")
        Files.write(training, byteArrayOf(1, 2, 3))
        Files.write(cache, byteArrayOf())

        SpectreBootstrap.promoteTrainingCache(
            AotCache.Plan(AotCache.Mode.TRAINING, cache, training, emptyList())
        )

        // Zero-length is not a usable cache: the fresh output replaces it
        // instead of being discarded, which would retrain on every launch.
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(cache))

        val winner = directory.resolve("spectre2.aot")
        val training2 = directory.resolve("spectre2.aot.training-1")
        Files.write(winner, byteArrayOf(9))
        Files.write(training2, byteArrayOf(7))

        SpectreBootstrap.promoteTrainingCache(
            AotCache.Plan(AotCache.Mode.TRAINING, winner, training2, emptyList())
        )

        assertContentEquals(byteArrayOf(9), Files.readAllBytes(winner))
    }

    @Test
    fun `unavailable plan child command runs the real CLI uncached`() {
        // A jar-backed launch whose cache is unavailable (malformed cache dir,
        // old JDK, ...) still execs this child instead of running in the capped
        // bootstrap JVM: frontend contract and caller options, no AOT flags.
        val plan = AotCache.Plan(AotCache.Mode.UNAVAILABLE, null, null, emptyList())

        val command =
            SpectreBootstrap.childCommand(
                javaExecutable,
                plan,
                jarFile,
                arrayOf("mcp"),
                25,
                listOf("-Xmx1g"),
                emptyList(),
            )

        assertEquals(
            listOf(
                javaExecutable,
                *frontendArguments.toTypedArray(),
                "-Xmx1g",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                jarFile.toString(),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "mcp",
            ),
            command,
        )
    }

    @Test
    fun `caller module options reach the record and assembly JVMs as well as production`() {
        // JEP 483 requires identical module options across record, create, and
        // consumption: if the production child carried --add-opens but training
        // did not, the JVM would reject the trained cache on every launch and
        // silently run uncached forever. Heap and agent options, by contrast,
        // are production-only: they do not shape the archived module graph.
        val moduleOptions =
            SpectreBootstrap.callerModuleOptions(
                listOf(
                    "-Xmx1g",
                    "--add-opens",
                    "java.base/java.lang=ALL-UNNAMED",
                    "-javaagent:/tmp/agent.jar",
                    "--add-modules",
                    "java.sql",
                )
            )

        assertEquals(
            listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED", "--add-modules", "java.sql"),
            moduleOptions,
        )

        val training = Path.of("/tmp", "aot", "spectre.aot.training-42")
        val plan =
            AotCache.Plan(
                AotCache.Mode.TRAINING,
                Path.of("/tmp", "aot", "spectre.aot"),
                training,
                listOf(
                    "-XX:AOTMode=record",
                    "-XX:AOTConfiguration=$training.config",
                ),
            )

        assertEquals(
            listOf(
                javaExecutable,
                *frontendArguments.toTypedArray(),
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=$training.config",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "--add-modules",
                "java.sql",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                jarFile.toString(),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "--help",
            ),
            SpectreBootstrap.trainingCommand(javaExecutable, plan, jarFile, 25, moduleOptions),
        )
        assertEquals(
            listOf(
                javaExecutable,
                *frontendArguments.toTypedArray(),
                "-XX:AOTMode=create",
                "-XX:AOTConfiguration=$training.config",
                "-XX:AOTCacheOutput=$training",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "--add-modules",
                "java.sql",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                jarFile.toString(),
            ),
            SpectreBootstrap.assemblyCommand(javaExecutable, plan, jarFile, 25, moduleOptions),
        )
    }

    @Test
    fun `caller illegal native access policy reaches every cache phase`() {
        // A caller's --illegal-native-access=deny is a policy the real CLI
        // must run under: dropped by the bootstrap, code that should throw
        // IllegalCallerException runs with the default warn instead. Like
        // --enable-native-access it shapes an archived module property
        // (jdk.module.illegal.native.access), so it belongs both to the
        // forwarded options of cache-free and cache-consuming children and
        // to the module-option set the record run, the assembly JVM, and the
        // fingerprint are built from — a phase missing it makes the JVM
        // reject the trained cache on every launch.
        val input = listOf("--illegal-native-access=deny")

        assertEquals(input, SpectreBootstrap.callerModuleOptions(input))
        assertEquals(
            input,
            SpectreBootstrap.callerJvmOptions(
                input,
                AotCache.Plan(AotCache.Mode.DISABLED, null, null, emptyList()),
            ),
        )
        assertEquals(
            input,
            SpectreBootstrap.callerJvmOptions(
                input,
                AotCache.Plan(
                    AotCache.Mode.PRODUCTION,
                    Path.of("/tmp", "aot", "spectre.aot"),
                    null,
                    emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `only a sole classpath entry counts as the packaged fat jar`() {
        // The child command re-execs with -cp <jar>, which is only the fat-jar
        // launch (java -jar, Roast). A thin start-script distribution puts the
        // CLI jar plus separate dependency jars on the classpath; re-execing
        // with just the thin jar would drop every dependency, so those
        // launches must stay in-process instead.
        val separator = java.io.File.pathSeparator
        val thinJar = Path.of("/opt", "spectre", "lib", "cli.jar").toAbsolutePath().normalize()
        val classPath =
            thinJar.toString() + separator + Path.of("/opt", "spectre", "lib", "kotlin-stdlib.jar")

        assertTrue(SpectreBootstrap.isSoleClasspathEntry(thinJar, thinJar.toString()))
        assertFalse(SpectreBootstrap.isSoleClasspathEntry(thinJar, classPath))
        assertFalse(SpectreBootstrap.isSoleClasspathEntry(thinJar, "/opt/spectre/lib/other.jar"))
        assertFalse(SpectreBootstrap.isSoleClasspathEntry(thinJar, ""))
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

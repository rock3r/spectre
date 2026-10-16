package dev.sebastiano.spectre.cli.bootstrap

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AotCacheTest {
    @TempDir lateinit var root: Path

    private fun jar(name: String = "spectre.jar", modifiedMillis: Long = 1_000_000L): Path {
        val jar = root.resolve(name)
        Files.write(jar, byteArrayOf(1, 2, 3, 4))
        Files.setLastModifiedTime(jar, FileTime.fromMillis(modifiedMillis))
        return jar
    }

    private fun plan(
        jarFile: Path?,
        environment: Map<String, String> = emptyMap(),
        featureVersion: Int = 25,
        allowTraining: Boolean = true,
    ): AotCache.Plan =
        AotCache.plan(
            jarFile,
            environment,
            root.resolve("home"),
            "Linux",
            "x86_64",
            "25.0.1",
            featureVersion,
            allowTraining,
            "testpid",
        )

    @Test
    fun `trains to a unique temporary file when no cache exists`() {
        val plan = plan(jar())

        assertEquals(AotCache.Mode.TRAINING, plan.mode())
        assertTrue(plan.cacheFile().toString().endsWith(".aot"))
        // Training is a discarded-stdio record run whose recorded configuration the
        // bootstrap then assembles into trainingFile (never the user's own command:
        // a record run appends an "AOTConfiguration recorded" line to stdout, which
        // would corrupt spectre mcp's protocol stream and --json output).
        assertEquals(
            listOf(
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=" + plan.trainingFile() + ".config",
            ),
            plan.jvmArguments(),
        )
        assertTrue(plan.trainingFile().toString().endsWith(".training-testpid"))
    }

    @Test
    fun `uses the cache in production when it already exists`() {
        val jar = jar()
        val training = plan(jar)
        Files.createDirectories(training.cacheFile().parent)
        Files.write(training.cacheFile(), byteArrayOf(9))

        val plan = plan(jar)

        assertEquals(AotCache.Mode.PRODUCTION, plan.mode())
        assertEquals(listOf("-XX:AOTCache=" + training.cacheFile()), plan.jvmArguments())
    }

    @Test
    fun `daemon never trains but uses a ready cache`() {
        val jar = jar()

        val cold = plan(jar, allowTraining = false)
        assertEquals(AotCache.Mode.UNAVAILABLE, cold.mode())
        assertEquals(emptyList(), cold.jvmArguments())

        val training = plan(jar)
        Files.createDirectories(training.cacheFile().parent)
        Files.write(training.cacheFile(), byteArrayOf(9))

        val warm = plan(jar, allowTraining = false)
        assertEquals(AotCache.Mode.PRODUCTION, warm.mode())
        assertEquals(listOf("-XX:AOTCache=" + training.cacheFile()), warm.jvmArguments())

        // The daemon consumes the same cache, so its arguments must carry the
        // module option the cache was trained with, or the JVM rejects it.
        assertEquals(
            listOf(
                "-XX:AOTCache=" + training.cacheFile(),
                "--enable-native-access=ALL-UNNAMED",
            ),
            AotCache.daemonJvmArguments(
                jar.toString(),
                emptyMap(),
                root.resolve("home"),
                "Linux",
                "x86_64",
                "25.0.1",
                25,
            ),
        )
    }

    @Test
    fun `a changed jar fingerprints to a different cache file`() {
        val before = plan(jar(modifiedMillis = 1_000_000L))
        val after = plan(jar(modifiedMillis = 2_000_000L))

        assertNotEquals(before.cacheFile(), after.cacheFile())
        assertEquals(AotCache.Mode.TRAINING, after.mode())
    }

    @Test
    fun `fingerprint is stable for an unchanged jar`() {
        assertEquals(plan(jar()).cacheFile(), plan(jar()).cacheFile())
    }

    @Test
    fun `fingerprint separates architectures sharing a home directory`() {
        // A shared (for example NFS) home must not let an x86_64 host plan
        // PRODUCTION against an arm64 host's incompatible cache, or the JVM
        // would reject that cache forever instead of retraining.
        val jar = jar()
        val arm =
            AotCache.plan(
                jar,
                emptyMap(),
                root.resolve("home"),
                "Linux",
                "aarch64",
                "25.0.1",
                25,
                true,
                "testpid",
            )

        assertNotEquals(plan(jar).cacheFile(), arm.cacheFile())
    }

    @Test
    fun `disabled by SPECTRE_AOT off`() {
        val plan = plan(jar(), environment = mapOf("SPECTRE_AOT" to "off"))

        assertEquals(AotCache.Mode.DISABLED, plan.mode())
        assertEquals(emptyList(), plan.jvmArguments())
    }

    @Test
    fun `unavailable before JDK 25`() {
        val plan = plan(jar(), featureVersion = 21)

        assertEquals(AotCache.Mode.UNAVAILABLE, plan.mode())
        assertEquals(emptyList(), plan.jvmArguments())
    }

    @Test
    fun `user supplied AOT options win over managed caching`() {
        val plan =
            plan(jar(), environment = mapOf("JAVA_TOOL_OPTIONS" to "-XX:AOTCache=/tmp/user.aot"))

        assertEquals(AotCache.Mode.UNAVAILABLE, plan.mode())
        assertEquals(emptyList(), plan.jvmArguments())
    }

    @Test
    fun `user AOT options are detected on the JVM command line too`() {
        // Start scripts pass JAVA_OPTS/SPECTRE_OPTS as JVM arguments, not through
        // the option environment variables the planner checks.
        assertTrue(AotCache.hasUserAotArguments(listOf("-Xmx512m", "-XX:AOTCache=/tmp/user.aot")))
        assertTrue(AotCache.hasUserAotArguments(listOf("-XX:AOTMode=record")))
        assertFalse(
            AotCache.hasUserAotArguments(
                listOf("-Xmx512m", "-Ddev.sebastiano.spectre.agent.runtimeJar=/tmp/a.jar")
            )
        )
    }

    @Test
    fun `unavailable without a single existing jar`() {
        assertEquals(AotCache.Mode.UNAVAILABLE, plan(null).mode())
        assertEquals(
            AotCache.Mode.UNAVAILABLE,
            plan(root.resolve("missing.jar")).mode(),
        )
        val classesDir = root.resolve("classes")
        Files.createDirectories(classesDir)
        assertEquals(AotCache.Mode.UNAVAILABLE, plan(classesDir).mode())
    }

    @Test
    fun `cache directory honours the override and per-OS defaults`() {
        val home = root.resolve("home")
        assertEquals(
            Path.of("/tmp/custom-aot"),
            AotCache.cacheDirectory(
                mapOf("SPECTRE_AOT_CACHE_DIR" to "/tmp/custom-aot"),
                home,
                "Linux",
            ),
        )
        assertEquals(
            home.resolve(".cache/spectre/aot"),
            AotCache.cacheDirectory(emptyMap(), home, "Linux"),
        )
        assertEquals(
            home.resolve("xdg/spectre/aot"),
            AotCache.cacheDirectory(
                mapOf("XDG_CACHE_HOME" to home.resolve("xdg").toString()),
                home,
                "Linux",
            ),
        )
        assertEquals(
            home.resolve("Library/Caches/Spectre/aot"),
            AotCache.cacheDirectory(emptyMap(), home, "Mac OS X"),
        )
        assertEquals(
            Path.of("C:/Users/x/AppData/Local").resolve("Spectre/aot"),
            AotCache.cacheDirectory(
                mapOf("LOCALAPPDATA" to "C:/Users/x/AppData/Local"),
                home,
                "Windows 11",
            ),
        )
    }

    @Test
    fun `single jar extraction rejects multi-entry classpaths`() {
        val jar = jar()
        assertEquals(jar, AotCache.singleJarFile(jar.toString()).orElse(null))
        assertTrue(
            AotCache.singleJarFile("$jar${File.pathSeparator}${root.resolve("other.jar")}").isEmpty
        )
        assertTrue(AotCache.singleJarFile("").isEmpty)
    }

    @Test
    fun `pruning keeps only the newest caches`() {
        val directory = root.resolve("aot")
        Files.createDirectories(directory)
        val names =
            listOf("spectre-old.aot", "spectre-mid.aot", "spectre-new.aot", "spectre-keep.aot")
        names.forEachIndexed { index, name ->
            val file = directory.resolve(name)
            Files.write(file, byteArrayOf(1))
            Files.setLastModifiedTime(file, FileTime.fromMillis((index + 1) * 1_000_000L))
        }
        val keep = directory.resolve("spectre-keep.aot")

        AotCache.pruneOldCaches(directory, keep, 2)

        assertEquals(
            setOf("spectre-new.aot", "spectre-keep.aot"),
            directory.toFile().list()?.toSet(),
        )
    }

    @Test
    fun `daemon arguments stay empty for an exploded test classpath`() {
        assertEquals(
            emptyList(),
            AotCache.daemonJvmArguments(
                root.resolve("classes").toString() + File.pathSeparator + jar(),
                emptyMap(),
                root.resolve("home"),
                "Linux",
                "x86_64",
                "25.0.1",
                25,
            ),
        )
    }
}

package dev.sebastiano.spectre.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * Unit coverage for the Java 21 preflight [PatchStartScripts] injects into the generated Unix
 * `bin/spectre` launcher.
 *
 * Runs the real patched launcher under `/bin/sh` against a fake `java`, so the version parsing is
 * exercised exactly as shipped rather than re-implemented. The fake mimics a JVM started with
 * `JAVA_TOOL_OPTIONS` exported (common on CI runners, behind corporate proxies, and in container
 * images): every such JVM prints `Picked up JAVA_TOOL_OPTIONS: ...` to stderr *before* the
 * `version "..."` line. A preflight that only reads the first line of `java -version` then finds
 * no version and kills the launcher even though the runtime is fine.
 */
@DisabledOnOs(OS.WINDOWS) // Exercises the POSIX launcher under /bin/sh.
class PatchStartScriptsTest {

    @Test
    fun `the preflight accepts a Java 21 runtime that echoes JAVA_TOOL_OPTIONS first`(
        @TempDir root: Path
    ) {
        val jdk = fakeJdk(root, "banner", version = "21.0.10", banner = true)

        val result = runLauncher(root, javaHome = jdk)

        assertEquals(0, result.exitCode, "launcher failed:\n${result.output}")
        assertFalse(result.output.contains("ERROR"), "launcher failed:\n${result.output}")
    }

    @Test
    fun `the preflight still rejects an old runtime that echoes JAVA_TOOL_OPTIONS first`(
        @TempDir root: Path
    ) {
        // Proves the version really is parsed from the line after the banner, not just skipped.
        val jdk = fakeJdk(root, "old", version = "17.0.2", banner = true)

        val result = runLauncher(root, javaHome = jdk)

        assertEquals(1, result.exitCode, "launcher should have died:\n${result.output}")
        assertTrue(
            result.output.contains("Spectre requires JDK 21 or later; found Java 17.0.2"),
            "unexpected launcher output:\n${result.output}",
        )
    }

    @Test
    fun `the preflight reads the JVM record, not a version-shaped banner payload`(
        @TempDir root: Path
    ) {
        // JAVA_TOOL_OPTIONS can itself carry `version "..."` — a property default, a note, a
        // path. The banner then looks like a version record, and matching any line containing
        // `version "` reads the property instead of the JVM, rejecting a supported runtime.
        // The same shape reaches the launcher as `NOTE: Picked up JDK_JAVA_OPTIONS: ...`.
        // Raised by Codex review on PR #485.
        val jdk =
            fakeJdk(
                root,
                "decoy",
                version = "21.0.10",
                banner = true,
                bannerOptions = "-Dnote=version \"17.0.2\"",
            )

        val result = runLauncher(root, javaHome = jdk)

        assertEquals(0, result.exitCode, "launcher failed:\n${result.output}")
        assertFalse(
            result.output.contains("17.0.2"),
            "the banner payload was parsed as the Java version:\n${result.output}",
        )
    }

    @Test
    fun `the preflight ignores a version-shaped agent banner`(@TempDir root: Path) {
        // A -javaagent installed through JAVA_TOOL_OPTIONS can print its own premain banner
        // ahead of the JVM record, and a single-token one such as `Agent version "17.0.2"` has
        // the very shape of a record. Only `java`/`openjdk` name a real record.
        // Raised by Codex review on PR #485.
        val jdk = fakeJdk(root, "agent", version = "21.0.2", banner = false)
        Files.writeString(
            jdk.resolve("bin/java"),
            """
            #!/bin/sh
            case "${'$'}1" in
                -version)
                    echo 'Agent version "17.0.2"' >&2
                    echo 'openjdk version "21.0.2" 2024-01-16' >&2
                    ;;
                --list-modules)
                    echo 'jdk.attach@21.0.2'
                    ;;
            esac
            """
                .trimIndent() + "\n",
        )
        check(jdk.resolve("bin/java").toFile().setExecutable(true))

        val result = runLauncher(root, javaHome = jdk)

        assertEquals(0, result.exitCode, "launcher failed:\n${result.output}")
        assertFalse(
            result.output.contains("17.0.2"),
            "the agent banner was parsed as the Java version:\n${result.output}",
        )
    }

    @Test
    fun `a launcher that never exits fails on the timeout instead of hanging`(
        @TempDir root: Path
    ) {
        // The harness must bound its own runs: draining the pipe to EOF before waiting would
        // block forever here and hang CI rather than failing. Raised by Codex review on PR #485.
        val jdk = fakeJdk(root, "wedged", version = "21.0.10", banner = false, hangs = true)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                runLauncher(root, javaHome = jdk, timeoutSeconds = 2)
            }

        assertTrue(
            failure.message.orEmpty().contains("did not finish within 2 seconds"),
            "unexpected failure: ${failure.message}",
        )
    }

    @Test
    fun `the preflight accepts a Java 21 runtime without the banner`(@TempDir root: Path) {
        // Regression guard: the banner fix must not change how a plain `java -version` is read.
        val jdk = fakeJdk(root, "plain", version = "21.0.10", banner = false)

        val result = runLauncher(root, javaHome = jdk)

        assertEquals(0, result.exitCode, "launcher failed:\n${result.output}")
        assertFalse(result.output.contains("ERROR"), "launcher failed:\n${result.output}")
    }

    @Test
    // macOS asks /usr/libexec/java_home first, which a test cannot stub, so the sdkman
    // candidate below would never be probed on a mac with a host JDK 21 installed.
    @EnabledOnOs(OS.LINUX)
    fun `the local JDK search accepts a candidate that echoes JAVA_TOOL_OPTIONS first`(
        @TempDir root: Path
    ) {
        val home = root.resolve("home")
        val sdkmanJdk = home.resolve(".sdkman/candidates/java/current")
        writeFakeJava(sdkmanJdk, version = "21.0.10", banner = true)
        // A PATH that carries the tools the search needs but no `java`, so the search runs.
        val pathWithoutJava = root.resolve("path-without-java")
        Files.createDirectories(pathWithoutJava)
        for (tool in listOf("sed", "grep")) {
            Files.createSymbolicLink(pathWithoutJava.resolve(tool), hostTool(tool))
        }

        val result =
            runLauncher(root, javaHome = null) { environment ->
                environment["HOME"] = home.toString()
                environment["PATH"] = pathWithoutJava.toString()
            }

        assertEquals(0, result.exitCode, "launcher failed:\n${result.output}")
        assertTrue(
            result.output.contains("JAVACMD=${sdkmanJdk.resolve("bin/java")}"),
            "the sdkman candidate was not selected:\n${result.output}",
        )
    }

    private class LauncherResult(val exitCode: Int, val output: String)

    /**
     * Patches [UNPATCHED_LAUNCHER] with the real [PatchStartScripts] logic, then runs it under
     * `/bin/sh` with `JAVA_HOME` pointing at [javaHome] (or unset when null).
     */
    private fun runLauncher(
        root: Path,
        javaHome: Path?,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        configureEnvironment: (MutableMap<String, String>) -> Unit = {},
    ): LauncherResult {
        val launcher = root.resolve("app/bin/spectre")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, PatchStartScripts.patchUnixScript(UNPATCHED_LAUNCHER))

        // Redirect to a file rather than draining the pipe inline: reading to EOF first would
        // block forever on a launcher that never exits, so the timeout below would never be
        // reached and a regression would hang the whole CI job instead of failing this test.
        val outputFile = launcher.resolveSibling("launcher-output.txt")
        val processBuilder =
            ProcessBuilder("/bin/sh", launcher.toString())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
        val environment = processBuilder.environment()
        environment.remove("JAVA_HOME")
        if (javaHome != null) environment["JAVA_HOME"] = javaHome.toString()
        configureEnvironment(environment)

        val process = processBuilder.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        val output = Files.readString(outputFile)
        check(finished) { "launcher did not finish within $timeoutSeconds seconds:\n$output" }
        return LauncherResult(process.exitValue(), output)
    }

    private fun fakeJdk(
        root: Path,
        name: String,
        version: String,
        banner: Boolean,
        bannerOptions: String = DEFAULT_BANNER_OPTIONS,
        hangs: Boolean = false,
    ): Path {
        val jdkHome = root.resolve("jdk-$name")
        writeFakeJava(jdkHome, version, banner, bannerOptions, hangs)
        return jdkHome
    }

    /** Writes a `bin/java` script that answers `-version` and `--list-modules` like a real JDK. */
    private fun writeFakeJava(
        jdkHome: Path,
        version: String,
        banner: Boolean,
        bannerOptions: String = DEFAULT_BANNER_OPTIONS,
        hangs: Boolean = false,
    ) {
        val java = jdkHome.resolve("bin/java")
        Files.createDirectories(java.parent)
        val bannerLine =
            if (banner) "echo 'Picked up JAVA_TOOL_OPTIONS: $bannerOptions' >&2" else ":"
        // A JVM that never returns wedges the launcher, which is what bounds the harness.
        val hangLine = if (hangs) "sleep 600" else ":"
        Files.writeString(
            java,
            """
            #!/bin/sh
            case "${'$'}1" in
                -version)
                    $hangLine
                    $bannerLine
                    echo 'openjdk version "$version" 2026-01-20' >&2
                    echo 'OpenJDK Runtime Environment (build $version+7)' >&2
                    echo 'OpenJDK 64-Bit Server VM (build $version+7, mixed mode, sharing)' >&2
                    ;;
                --list-modules)
                    echo 'java.base@$version'
                    echo 'jdk.attach@$version'
                    ;;
            esac
            """
                .trimIndent() + "\n",
        )
        check(java.toFile().setExecutable(true)) { "Could not make $java executable" }
    }

    private fun hostTool(name: String): Path =
        System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.toPath()
            ?: error("Could not find $name on PATH")

    private companion object {
        private const val COMMAND_TIMEOUT_SECONDS: Long = 30

        private const val DEFAULT_BANNER_OPTIONS = "-Dspectre.test=true"

        /**
         * Minimal stand-in for the Gradle-generated launcher: the two anchors [PatchStartScripts]
         * rewrites, the `die` helper, and the `JAVACMD` resolution the preflight relies on. It
         * echoes the resolved `JAVACMD` instead of starting the JVM.
         */
        private val UNPATCHED_LAUNCHER =
            """
            #!/bin/sh
            APP_HOME=${'$'}{0%/bin/*}

            die () {
                echo
                echo "${'$'}*"
                echo
                exit 1
            } >&2

            # Determine the Java command to use to start the JVM.
            if [ -n "${'$'}JAVA_HOME" ] ; then
                JAVACMD=${'$'}JAVA_HOME/bin/java
                if [ ! -x "${'$'}JAVACMD" ] ; then
                    die "ERROR: JAVA_HOME is set to an invalid directory: ${'$'}JAVA_HOME"
                fi
            else
                JAVACMD=java
                if ! command -v java >/dev/null 2>&1
                then
                    die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
                fi
            fi

            # Add default JVM options here. You can also use JAVA_OPTS and SPECTRE_OPTS to pass JVM options to this script.
            DEFAULT_JVM_OPTS=""

            echo "JAVACMD=${'$'}JAVACMD"
            """
                .trimIndent() + "\n"
    }
}

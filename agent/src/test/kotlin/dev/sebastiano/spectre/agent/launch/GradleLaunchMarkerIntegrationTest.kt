@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Real Gradle/JavaExec boundary coverage for launch ownership; no Attach call is used as a probe.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class GradleLaunchMarkerIntegrationTest {
    @Test
    fun `overlapping JavaExec launches with the same main bind to their own marker`() {
        val fixtureA = createFixture("overlap-a")
        val fixtureB = createFixture("overlap-b")
        val warmup = startGradle(fixtureA, marker = null, task = "help")
        assertEquals(0, warmup.waitFor(), "failed to warm a reusable Gradle daemon")

        val first = startMarkedFixture(fixtureA, "marker-overlap-a")
        val firstPid = awaitOwnedJvm(first, "marker-overlap-a")
        val second = startMarkedFixture(fixtureB, "marker-overlap-b")
        try {
            val secondPid = awaitOwnedJvm(second, "marker-overlap-b")
            assertNotEquals(firstPid, secondPid)
            assertTrue(LaunchDescendantDiscovery.commandLineContains(firstPid, "marker-overlap-a"))
            assertTrue(LaunchDescendantDiscovery.commandLineContains(secondPid, "marker-overlap-b"))
            assertTrue(!LaunchDescendantDiscovery.commandLineContains(firstPid, "marker-overlap-b"))
            assertTrue(
                !LaunchDescendantDiscovery.commandLineContains(secondPid, "marker-overlap-a")
            )
        } finally {
            destroyLaunch(first)
            destroyLaunch(second)
        }
    }

    @Test
    fun `no-daemon JavaExec launch exposes marker in the owned subprocess argv`() {
        val fixture = createFixture("no-daemon")
        val launch = startMarkedFixture(fixture, "marker-no-daemon", noDaemon = true)
        try {
            val pid = awaitOwnedJvm(launch, "marker-no-daemon")
            assertTrue(LaunchDescendantDiscovery.commandLineContains(pid, "marker-no-daemon"))
        } finally {
            destroyLaunch(launch)
        }
    }

    private fun createFixture(name: String): Path {
        val directory = Files.createTempDirectory("spectre-gradle-marker-$name-")
        directory.resolve("settings.gradle").writeText("rootProject.name = '$name'\n")
        directory
            .resolve("build.gradle")
            .writeText(
                """
                |tasks.register('runFixture', JavaExec) {
                |    classpath = files('.')
                |    mainClass = 'SameMain'
                |}
                """
                    .trimMargin()
            )
        directory
            .resolve("SameMain.java")
            .writeText(
                """
                |public final class SameMain {
                |    public static void main(String[] args) throws Exception {
                |        Thread.sleep(120000L);
                |    }
                |}
                """
                    .trimMargin()
            )
        val javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString()
        val compile = ProcessBuilder(javac, "SameMain.java").directory(directory.toFile()).start()
        assertEquals(0, compile.waitFor(), "fixture javac failed")
        return directory
    }

    private fun startMarkedFixture(
        fixture: Path,
        marker: String,
        noDaemon: Boolean = false,
    ): Launch {
        val command = buildList {
            add(gradlew().toString())
            add("-p")
            add(fixture.toString())
            add("runFixture")
            add("-q")
            if (noDaemon) add("--no-daemon")
        }
        val capture = Files.createTempDirectory("spectre-gradle-marker-capture-")
        val marked = prepareGradleLaunchCommand(command, capture, marker)
        val startedAt = Instant.now()
        return Launch(
            process =
                ProcessBuilder(marked)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start(),
            startedAt = startedAt,
            marker = marker,
        )
    }

    private fun startGradle(fixture: Path, marker: String?, task: String): Process {
        val command = listOf(gradlew().toString(), "-p", fixture.toString(), task, "-q")
        val actual =
            if (marker == null) command else prepareGradleLaunchCommand(command, fixture, marker)
        return ProcessBuilder(actual).redirectErrorStream(true).start()
    }

    private fun awaitOwnedJvm(launch: Launch, marker: String): Long {
        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        while (Instant.now().isBefore(deadline)) {
            discoverOwnedAppJvm(
                    clientPid = launch.process.pid(),
                    nameFilter = "SameMain",
                    clientStart = launch.startedAt,
                    launchMarker = marker,
                )
                ?.let {
                    return it
                }
            check(launch.process.isAlive) { "Gradle client exited before marked JavaExec appeared" }
            Thread.sleep(100)
        }
        error("timed out discovering JavaExec owned by $marker")
    }

    private fun destroyLaunch(launch: Launch) {
        ProcessHandle.allProcesses()
            .filter { LaunchDescendantDiscovery.commandLineContains(it.pid(), launch.marker) }
            .forEach { ProcessTreeTeardown.destroyTreeByPid(it.pid()) }
        launch.process.destroyForcibly()
        launch.process.waitFor()
    }

    private fun gradlew(): Path {
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = directory.resolve("gradlew")
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent ?: error("could not find gradlew")
        }
        error("could not find gradlew")
    }

    private data class Launch(val process: Process, val startedAt: Instant, val marker: String)
}

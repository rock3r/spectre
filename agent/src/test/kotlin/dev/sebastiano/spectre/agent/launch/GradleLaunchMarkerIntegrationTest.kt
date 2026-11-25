@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Real Gradle/JavaExec boundary coverage for launch ownership; no Attach call is used as a probe.
 */
class GradleLaunchMarkerIntegrationTest {
    @Test
    fun `overlapping JavaExec launches with the same main bind to their own marker`() {
        val fixtureA = createFixture("overlap-a")
        val fixtureB = createFixture("overlap-b")
        val warmup = startGradle(fixtureA, marker = null, task = "help")
        assertEquals(0, warmup.waitFor(), "failed to warm a reusable Gradle daemon")

        val first = startMarkedFixture(fixtureA)
        var second: Launch? = null
        try {
            val firstPid = awaitOwnedJvm(first)
            second = startMarkedFixture(fixtureB)
            val secondPid = awaitOwnedJvm(second)
            assertNotEquals(firstPid, secondPid)
            assertTrue(hasLaunchMarker(firstPid, first.marker))
            assertTrue(hasLaunchMarker(secondPid, second.marker))
            assertTrue(!hasLaunchMarker(firstPid, second.marker))
            assertTrue(!hasLaunchMarker(secondPid, first.marker))
        } finally {
            destroyLaunch(first)
            second?.let(::destroyLaunch)
        }
    }

    @Test
    fun `no-daemon JavaExec launch exposes marker in the owned subprocess argv`() {
        val fixture = createFixture("no-daemon")
        val launch = startMarkedFixture(fixture, noDaemon = true)
        try {
            val pid = awaitOwnedJvm(launch)
            assertTrue(hasLaunchMarker(pid, launch.marker))
        } finally {
            destroyLaunch(launch)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.WINDOWS)
    fun `no-daemon JavaExec hidden from hsperfdata is found once through overlapping native roots`() {
        val fixture = createFixture("no-daemon-no-perfdata", disablePerfData = true)
        val launch = startMarkedFixture(fixture, noDaemon = true)
        try {
            val pid = awaitOwnedJvm(launch)
            assertFalse(
                LaunchDescendantDiscovery.isJvmAttachable(pid),
                "-XX:-UsePerfData must force discovery through the native process-tree fallback",
            )
            assertTrue(LaunchDescendantDiscovery.commandLineContains(pid, "-XX:-UsePerfData"))
        } finally {
            destroyLaunch(launch)
        }
    }

    private fun createFixture(name: String, disablePerfData: Boolean = false): Path {
        val directory = Files.createTempDirectory("spectre-gradle-marker-$name-")
        directory.resolve("settings.gradle").writeText("rootProject.name = '$name'\n")
        directory
            .resolve("build.gradle")
            .writeText(
                """
                |tasks.register('runFixture', JavaExec) {
                |    classpath = files('.')
                |    mainClass = 'SameMain'
                |    args providers.gradleProperty('fixtureReadinessFile').get()
                |    ${if (disablePerfData) "jvmArgs '-XX:-UsePerfData'" else ""}
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
                |        java.nio.file.Files.writeString(
                |            java.nio.file.Path.of(args[0]),
                |            ProcessHandle.current().pid() + "\n" +
                |                System.getProperty("${LaunchAndAttach.LAUNCH_MARKER_PROPERTY}", "") + "\n"
                |        );
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
        noDaemon: Boolean = false,
    ): Launch {
        val capture = Files.createTempDirectory("spectre-gradle-marker-capture-")
        val command = buildList {
            add(gradlew().toString())
            add("-p")
            add(fixture.toString())
            add("runFixture")
            add("-q")
            add("-PfixtureReadinessFile=${capture.resolve("fixture.ready")}")
            if (noDaemon) add("--no-daemon")
        }
        val marker = UUID.randomUUID().toString()
        val marked = prepareGradleLaunchCommand(command, capture, marker)
        val startedAt = Instant.now()
        return Launch(
            process =
                ProcessBuilder(marked)
                    .redirectErrorStream(true)
                    .redirectOutput(capture.resolve("gradle.log").toFile())
                    .start(),
            startedAt = startedAt,
            marker = marker,
            capture = capture,
        )
    }

    private fun startGradle(fixture: Path, marker: String?, task: String): Process {
        val command = listOf(gradlew().toString(), "-p", fixture.toString(), task, "-q")
        val actual =
            if (marker == null) command else prepareGradleLaunchCommand(command, fixture, marker)
        return ProcessBuilder(actual).redirectErrorStream(true).start()
    }

    private fun awaitOwnedJvm(launch: Launch): Long {
        val fixture = awaitFixtureReadiness(launch)
        val deadline = Instant.now().plus(MARKER_DISCOVERY_TIMEOUT)
        while (Instant.now().isBefore(deadline)) {
            discoverOwnedAppJvm(
                    clientPid = launch.process.pid(),
                    nameFilter = "SameMain",
                    clientStart = launch.startedAt,
                    launchMarker = launch.marker,
                )
                ?.let { discoveredPid ->
                    assertEquals(
                        fixture.pid,
                        discoveredPid,
                        "ownership discovery selected a different process than the live fixture",
                    )
                    return discoveredPid
                }
            check(launch.process.isAlive) { timeoutDiagnostics(launch, fixture) }
            Thread.sleep(100)
        }
        error(timeoutDiagnostics(launch, fixture))
    }

    private fun awaitFixtureReadiness(launch: Launch): FixtureReadiness {
        val readinessFile = launch.capture.resolve("fixture.ready")
        val deadline = Instant.now().plus(FIXTURE_READY_TIMEOUT)
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(readinessFile)) {
                val lines = Files.readAllLines(readinessFile)
                if (lines.size >= 2) {
                    val readiness = FixtureReadiness(lines[0].toLong(), lines[1])
                    assertEquals(launch.marker, readiness.marker, "fixture inherited wrong marker")
                    assertTrue(
                        ProcessHandle.of(readiness.pid).map(ProcessHandle::isAlive).orElse(false),
                        "fixture reported pid ${readiness.pid}, but it is not alive",
                    )
                    return readiness
                }
            }
            check(launch.process.isAlive) { timeoutDiagnostics(launch, null) }
            Thread.sleep(100)
        }
        error(timeoutDiagnostics(launch, null))
    }

    private fun timeoutDiagnostics(launch: Launch, fixture: FixtureReadiness?): String {
        val now = Instant.now()
        val markerPids =
            ProcessHandle.allProcesses()
                .filter { hasLaunchMarker(it.pid(), launch.marker) }
                .map { it.pid().toString() }
                .toList()
        val descendants = runCatching {
            launch.process.descendants().map { it.pid().toString() }.toList()
        }
            .getOrDefault(emptyList())
        val output = runCatching {
            Files.readString(launch.capture.resolve("gradle.log"))
        }
            .getOrDefault("<unavailable>")
        return """
            |Gradle fixture timeout
            |startedAt=${launch.startedAt}
            |observedAt=$now
            |clientPid=${launch.process.pid()} clientAlive=${launch.process.isAlive}
            |fixturePid=${fixture?.pid ?: "<not ready>"}
            |markerPids=$markerPids
            |clientDescendants=$descendants
            |gradleOutput:
            |$output
            """
            .trimMargin()
    }

    private fun destroyLaunch(launch: Launch) {
        ProcessHandle.allProcesses()
            .filter { hasLaunchMarker(it.pid(), launch.marker) }
            .forEach { ProcessTreeTeardown.destroyTreeByPid(it.pid()) }
        launch.process.destroyForcibly()
        launch.process.waitFor()
    }

    private fun gradlew(): Path {
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate =
                directory.resolve(
                    if (java.io.File.separatorChar == '\\') "gradlew.bat" else "gradlew"
                )
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent ?: error("could not find gradlew")
        }
        error("could not find gradlew")
    }

    private data class Launch(
        val process: Process,
        val startedAt: Instant,
        val marker: String,
        val capture: Path,
    )

    private data class FixtureReadiness(val pid: Long, val marker: String)

    private companion object {
        val FIXTURE_READY_TIMEOUT: Duration = Duration.ofSeconds(90)
        val MARKER_DISCOVERY_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}

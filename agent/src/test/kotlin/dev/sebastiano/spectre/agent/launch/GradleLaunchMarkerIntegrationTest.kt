@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

/**
 * Real Gradle/JavaExec boundary coverage for launch ownership; no Attach call is used as a probe.
 */
class GradleLaunchMarkerIntegrationTest {
    @TempDir lateinit var directory: Path
    private val reusableFixtures = mutableListOf<Path>()
    private val ownedProcesses = ConcurrentHashMap<Path, MutableSet<ProcessHandle>>()
    private val processTrackers = ConcurrentHashMap<Path, MutableSet<CompletableFuture<Void>>>()

    @AfterEach
    fun stopFixtureDaemons() {
        assertAll(
            reusableFixtures.map { fixture ->
                Executable {
                    val daemon = buildJvm(fixture)
                    runGradle(fixture, "--stop")
                    daemon?.onExit()?.get(OWNED_PROCESS_EXIT_TIMEOUT.seconds, TimeUnit.SECONDS)
                    awaitOwnedProcesses(fixture)
                }
            }
        )
    }

    @Test
    fun `overlapping JavaExec launches with the same main bind to their own marker`() {
        val fixtureA = createFixture("overlap-a")
        val fixtureB = createFixture("overlap-b")
        // Warm both independent build JVMs before either measured app launch.
        for (fixture in listOf(fixtureA, fixtureB)) {
            reusableFixtures.add(fixture)
            runGradle(fixture, "--daemon", "help")
        }

        val warmedA = requireNotNull(buildJvm(fixtureA))
        val warmedB = requireNotNull(buildJvm(fixtureB))
        val first = startMarkedFixture(fixtureA)
        var second: Launch? = null
        try {
            val firstPid = awaitOwnedJvm(first)
            second = startMarkedFixture(fixtureB)
            val secondPid = awaitOwnedJvm(second)
            assertEquals(warmedA, buildJvm(fixtureA))
            assertEquals(warmedB, buildJvm(fixtureB))
            assertNotEquals(firstPid, secondPid)
            assertTrue(hasLaunchMarker(firstPid, first.marker))
            assertTrue(hasLaunchMarker(secondPid, second.marker))
            assertTrue(!hasLaunchMarker(firstPid, second.marker))
            assertTrue(!hasLaunchMarker(secondPid, first.marker))
        } finally {
            try {
                second?.let(::destroyLaunch)
            } finally {
                destroyLaunch(first)
            }
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
        val fixture = Files.createDirectory(directory.resolve(name))
        fixture
            .resolve("settings.gradle")
            .writeText(
                """
                |rootProject.name = '$name'
                |file('build.pid').text = ProcessHandle.current().pid().toString()
                |println 'Fixture settings evaluated'
                """
                    .trimMargin()
            )
        fixture
            .resolve("gradle.properties")
            .writeText("org.gradle.jvmargs=-Xmx192m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC\n")
        fixture
            .resolve("build.gradle")
            .writeText(
                """
                |tasks.register('runFixture', JavaExec) {
                |    classpath = files(providers.gradleProperty('fixtureClasses').get())
                |    mainClass = '${SameMain::class.java.name}'
                |    maxHeapSize = '32m'
                |    args 'fixture.ready', '${ProcessHandle.current().pid()}', '${LaunchAndAttach.LAUNCH_MARKER_PROPERTY}'
                |    ${if (disablePerfData) "jvmArgs '-XX:-UsePerfData'" else ""}
                |    doFirst { logger.lifecycle('Fixture JavaExec starting') }
                |}
                """
                    .trimMargin()
            )
        return fixture
    }

    private fun startMarkedFixture(fixture: Path, noDaemon: Boolean = false): Launch {
        val command =
            gradleCommand(
                if (noDaemon) "--no-daemon" else "--daemon",
                "-PfixtureClasses=${Path.of(SameMain::class.java.protectionDomain.codeSource.location.toURI())}",
                "runFixture",
            )
        val marker = UUID.randomUUID().toString()
        val marked = prepareGradleLaunchCommand(command, fixture, marker)
        val startedAt = Instant.now()
        return Launch(
            startBuild(fixture, marked, fixture.resolve("gradle.log")),
            startedAt,
            marker,
            fixture,
        )
    }

    private fun gradleCommand(vararg args: String): List<String> {
        val binary = if (java.io.File.separatorChar == '\\') "gradle.bat" else "gradle"
        val gradle = Path.of(System.getProperty("spectre.test.gradleHome"), "bin", binary)
        return listOf(
            gradle.toString(),
            "--offline",
            "--console=plain",
            "--max-workers=1",
            "--no-watch-fs",
            *args,
        )
    }

    private fun startBuild(fixture: Path, command: List<String>, log: Path): Process {
        val process =
            ProcessBuilder(command)
                .directory(fixture.toFile())
                .apply {
                    // Host launch options can override the fixture budget or dump unrelated
                    // arguments.
                    listOf(
                            "JAVA_OPTS",
                            "GRADLE_OPTS",
                            "JAVA_TOOL_OPTIONS",
                            "_JAVA_OPTIONS",
                            "JDK_JAVA_OPTIONS",
                        )
                        .forEach(environment()::remove)
                    environment()["GRADLE_USER_HOME"] =
                        fixture.resolveSibling("${fixture.fileName}-home").toString()
                    environment()["JAVA_HOME"] = System.getProperty("java.home")
                }
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
        trackOwnedProcesses(fixture, process)
        return process
    }

    private fun trackOwnedProcesses(fixture: Path, process: Process) {
        val seen = ownedProcesses.computeIfAbsent(fixture) { ConcurrentHashMap.newKeySet() }
        seen.add(process.toHandle())
        val tracker = CompletableFuture.runAsync {
            do {
                process.descendants().forEach(seen::add)
                if (process.isAlive) Thread.sleep(PROCESS_TRACKING_INTERVAL_MS)
            } while (process.isAlive)
            // Include children visible at the instant the wrapper exits. In particular, gradle.bat
            // starts a native Gradle client below cmd.exe on Windows.
            process.descendants().forEach(seen::add)
        }
        processTrackers.computeIfAbsent(fixture) { ConcurrentHashMap.newKeySet() }.add(tracker)
    }

    private fun runGradle(fixture: Path, vararg args: String) {
        val log = fixture.resolve(if ("--stop" in args) "stop.log" else "warmup.log")
        val process = startBuild(fixture, gradleCommand(*args), log)
        try {
            check(
                process.waitFor(FIXTURE_READY_TIMEOUT.seconds, TimeUnit.SECONDS) &&
                    process.exitValue() == 0
            ) {
                "Fixture Gradle ${args.toList()} failed: ${Files.readString(log)}"
            }
        } finally {
            if (process.isAlive) ProcessTreeTeardown.destroyTree(process.toHandle())
            val daemonPid = buildJvm(fixture)?.pid()?.takeIf { fixture in reusableFixtures }
            awaitOwnedProcesses(fixture, setOfNotNull(daemonPid))
        }
    }

    private fun awaitOwnedJvm(launch: Launch): Long {
        val fixture = awaitFixtureReadiness(launch)
        val readyAt = Instant.now()
        val deadline = readyAt.plus(MARKER_DISCOVERY_TIMEOUT)
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
                    val readinessMs = Duration.between(launch.startedAt, readyAt).toMillis()
                    val discoveryMs = Duration.between(readyAt, Instant.now()).toMillis()
                    println(
                        "Fixture ${launch.capture.fileName}: readiness=${readinessMs}ms discovery=${discoveryMs}ms"
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

    private fun buildJvm(fixture: Path): ProcessHandle? =
        fixture.resolve("build.pid").takeIf(Files::isRegularFile)?.let {
            Files.readString(it).trim().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).orElse(null)
            }
        }

    private fun destroyLaunch(launch: Launch) {
        val singleUseDaemon =
            if (launch.capture in reusableFixtures) null else buildJvm(launch.capture)
        Files.createFile(launch.capture.resolve("fixture.release"))
        if (!launch.process.waitFor(15, TimeUnit.SECONDS)) {
            ProcessHandle.allProcesses()
                .filter { hasLaunchMarker(it.pid(), launch.marker) }
                .forEach { ProcessTreeTeardown.destroyTreeByPid(it.pid()) }
            ProcessTreeTeardown.destroyTree(launch.process.toHandle())
        }
        // Windows keeps cache files locked until the single-use build JVM exits.
        singleUseDaemon?.onExit()?.get(OWNED_PROCESS_EXIT_TIMEOUT.seconds, TimeUnit.SECONDS)
        val reusableDaemonPid = buildJvm(launch.capture)?.pid()?.takeIf { singleUseDaemon == null }
        awaitOwnedProcesses(launch.capture, setOfNotNull(reusableDaemonPid))
    }

    private fun awaitOwnedProcesses(fixture: Path, excludedPids: Set<Long> = emptySet()) {
        val deadline = Instant.now().plus(OWNED_PROCESS_EXIT_TIMEOUT)
        processTrackers[fixture].orEmpty().forEach { tracker ->
            val remainingMillis = Duration.between(Instant.now(), deadline).toMillis()
            check(remainingMillis > 0) { "Timed out tracking owned Gradle processes for $fixture" }
            tracker.get(remainingMillis, TimeUnit.MILLISECONDS)
        }
        var alive: List<ProcessHandle>
        do {
            alive =
                ownedProcesses[fixture].orEmpty().filter { it.pid() !in excludedPids && it.isAlive }
            if (alive.isEmpty()) return
            Thread.sleep(PROCESS_TRACKING_INTERVAL_MS)
        } while (Instant.now().isBefore(deadline))
        error(
            "Owned Gradle fixture processes did not exit for $fixture: " +
                alive.joinToString {
                    "pid=${it.pid()} command=${it.info().command().orElse("<unknown>")}"
                }
        )
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
        val OWNED_PROCESS_EXIT_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val PROCESS_TRACKING_INTERVAL_MS: Long = 25
    }
}

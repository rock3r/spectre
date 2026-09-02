@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release-gate proof that two **independent client JVMs** are serialised by the coordinator.
 *
 * `LocalCoordinatorServerTest` proves FIFO between two client sessions inside one JVM, and
 * `CoordinatorProcessLauncherTest` forks the coordinator but connects a single client. Neither
 * exercises the release gate's actual claim — two separate client processes contending for one
 * desktop — so a regression that only appears across process boundaries would pass both. This test
 * closes that gap and backs the `input-coord-contention` smoke cell (see docs/RELEASE-SMOKE.md).
 *
 * Non-overlap here is an invariant, not a timing race: the lease is mutually exclusive, so the
 * second process physically cannot hold it until the first releases. A failure means real
 * interleaving, never a slow machine.
 */
class TwoClientJvmContentionTest {

    private val temporaryDirectory: Path = Files.createTempDirectory("spc-2jvm-")
    private val endpoint =
        CoordinatorEndpoint(temporaryDirectory, temporaryDirectory.resolve("coordinator.sock"))
    private val resource = DesktopResourceKey("user:501/two-jvm-contention")
    private var server: LocalCoordinatorServer? = null
    private val children = mutableListOf<Process>()

    @AfterTest
    fun cleanUp() {
        children.forEach { child ->
            child.destroy()
            child.waitFor(CHILD_DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        runCatching { server?.close() }
        // Unlike the sibling coordinator tests, this one also leaves the probes' lease records
        // behind, so clear the whole tree rather than a fixed list of known files.
        Files.walk(temporaryDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun `two independent client JVMs never hold the desktop lease at the same time`() {
        server =
            LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1)).also {
                it.start()
            }
        val firstOutput = temporaryDirectory.resolve("first.txt")
        val secondOutput = temporaryDirectory.resolve("second.txt")

        // Started back to back so they genuinely contend; whichever wins the race holds first.
        val first = startProbe("first-jvm", firstOutput)
        val second = startProbe("second-jvm", secondOutput)

        assertEquals(0, first.waitFor(), "first client JVM exited non-zero")
        assertEquals(0, second.waitFor(), "second client JVM exited non-zero")

        val firstHeld = readHeldInterval(firstOutput)
        val secondHeld = readHeldInterval(secondOutput)

        val disjoint = firstHeld.second <= secondHeld.first || secondHeld.second <= firstHeld.first
        assertTrue(
            disjoint,
            "two client JVMs overlapped on one desktop lease: " +
                "first=[${firstHeld.first}, ${firstHeld.second}] " +
                "second=[${secondHeld.first}, ${secondHeld.second}]",
        )
    }

    private fun startProbe(ownerLabel: String, output: Path): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", javaExecutableName()).toString()
        val process =
            ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    PROBE_MAIN_CLASS,
                    endpoint.socketPath.toString(),
                    resource.value,
                    ownerLabel,
                    HOLD_MILLIS.toString(),
                    output.toString(),
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        children += process
        return process
    }

    /** Returns the child's `(acquiredAt, releasedAt)` wall-clock pair. */
    private fun readHeldInterval(output: Path): Pair<Long, Long> {
        assertTrue(Files.isRegularFile(output), "child JVM wrote no lease record at $output")
        val lines = Files.readAllLines(output)
        val acquired = lines.single { it.startsWith("ACQUIRED ") }.removePrefix("ACQUIRED ").trim()
        val released = lines.single { it.startsWith("RELEASED ") }.removePrefix("RELEASED ").trim()
        return acquired.toLong() to released.toLong()
    }

    private fun javaExecutableName(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }

    private companion object {
        const val PROBE_MAIN_CLASS: String =
            "dev.sebastiano.spectre.input.server.TwoClientJvmContentionProbeKt"

        /** Long enough that a genuine overlap is unmistakable at millisecond clock granularity. */
        const val HOLD_MILLIS: Long = 400

        const val CHILD_DESTROY_TIMEOUT_SECONDS: Long = 5
    }
}

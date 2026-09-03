package dev.sebastiano.spectre.sample

import androidx.compose.ui.awt.ComposePanel
import java.awt.Dimension
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * Release-gate proof that two processes driving **real OS input** cannot shred each other's
 * keystrokes — the `input-coord-headed-robot` smoke cell (#491, follow-up to #484).
 *
 * The `input-coord-*` protocol cells already prove the desktop lease is mutually exclusive across
 * process boundaries, `TwoClientJvmContentionTest` included. What none of them can observe is the
 * thing the lease exists for: every one of them passes headless and under SSH because not one
 * constructs a `RobotDriver`. This test does. Two forked JVMs each take
 * `RobotDriver(InputLeasePolicy.Required)` and type one block into the same focused text field of
 * the same fixture window; an `ababab` field is the failure being ruled out, and it is invisible to
 * every lease-level proof.
 *
 * **Why it is genuinely contended.** Starting two JVMs back to back proves nothing: JVM startup
 * plus coordinator launch easily outlasts a block of typing, the two would trivially serialise
 * themselves, and the assertion would pass against a coordinator enforcing nothing. So each probe
 * pays all of that up front, signals readiness, and parks on a parent-controlled gate — and the
 * test then *measures* the overlap rather than trusting it: the probe that asked for the keyboard
 * second must have asked while the first was still typing, or this fails as a broken barrier rather
 * than reporting a vacuous pass.
 *
 * **Red proof.** Mutual exclusion can be switched off without touching production code, because a
 * Linux desktop key is derived from `WAYLAND_DISPLAY` while `java.awt.Robot` follows `DISPLAY`:
 * ```
 * ./gradlew :sample-desktop:headedRobotContentionTest \
 *     -Dspectre.headedContention.distinctDesktopKeys=true
 * ```
 *
 * gives each probe its own fake `WAYLAND_DISPLAY`, so one coordinator hands both a lease at once
 * while both keep typing at the same X display. That run must fail, and it must fail with
 * [INTERLEAVED_FAILURE] — a failure for any other reason is not a red proof.
 *
 * Not part of `check`: it is tagged [HEADED_ROBOT_TAG], which `test` excludes and the dedicated
 * `headedRobotContentionTest` task selects. It needs a real desktop (macOS, Windows) or Xvfb.
 */
@Tag(HEADED_ROBOT_TAG)
class HeadedRobotContentionTest {

    private val workDirectory: Path = Files.createTempDirectory("spectre-headed-contention-")
    private val goFile: Path = workDirectory.resolve("go")
    private val children = mutableListOf<Process>()
    private var frame: JFrame? = null

    @AfterTest
    fun cleanUp() {
        children.forEach { child ->
            child.destroy()
            child.waitFor(CHILD_DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        SwingUtilities.invokeLater {
            frame?.isVisible = false
            frame?.dispose()
        }
        Files.walk(workDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun `two Robot JVMs typing into one field never interleave their keystrokes`() {
        runBlocking { proveKeystrokesNeverInterleave() }
    }

    private suspend fun proveKeystrokesNeverInterleave() {
        checkWaylandGate("HeadedRobotContentionTest")?.let { abort ->
            // A fail, never a skip: the release cell that runs this task treats an assumption-skip
            // as a failure precisely so an unrunnable host cannot report the headed claim green.
            error(abort)
        }
        val state = openFixtureWindow()
        val target =
            requireNotNull(awtCenter(state, state.textFieldBounds)) {
                "the shared text field never reported screen bounds, so no probe could aim at it"
            }
        printEnvironment("HeadedRobotContentionTest", state)
        println("headed contention: aiming both probes at $target, ${blockPlanSummary()}")

        val first = ProbeHandle("first", FIRST_BLOCK_CHARACTER)
        val second = ProbeHandle("second", SECOND_BLOCK_CHARACTER)
        start(first, target)
        start(second, target)

        awaitReady(first, second)
        Files.writeString(goFile, "go\n")

        assertEquals(0, waitForExit(first), "the '${first.character}' probe JVM exited non-zero")
        assertEquals(0, waitForExit(second), "the '${second.character}' probe JVM exited non-zero")

        assertDemandOverlapped(first.readRecord(), second.readRecord())

        val observed = awaitFieldContent(state)
        println("headed contention: field content was \"$observed\"")
        assertNull(
            describeContentionFailure(
                observed,
                FIRST_BLOCK_CHARACTER,
                SECOND_BLOCK_CHARACTER,
                BLOCK_LENGTH,
            ),
            "two RobotDriver(InputLeasePolicy.Required) JVMs did not keep their real input apart",
        )
    }

    /**
     * Fails unless the second probe wanted the keyboard while the first still had it.
     *
     * Without this the suite could pass on a host where startup skew serialised the probes on its
     * own, which is a green light that proves nothing about the coordinator.
     */
    private fun assertDemandOverlapped(first: ProbeRecord, second: ProbeRecord) {
        val (early, late) = listOf(first, second).sortedBy(ProbeRecord::requestedAt)
        assertTrue(
            late.requestedAt < early.typedTo,
            "the probes never contended: '${late.character}' first asked for the keyboard at " +
                "${late.requestedAt}, after '${early.character}' had already finished typing at " +
                "${early.typedTo}. The barrier failed to release them together, so a pass here " +
                "would say nothing about mutual exclusion.",
        )
    }

    private suspend fun openFixtureWindow(): SmokeState {
        val state = SmokeState()
        val frameReference = AtomicReference<JFrame>()
        SwingUtilities.invokeLater {
            val panel =
                ComposePanel().apply {
                    preferredSize = Dimension(SMOKE_PANEL_WIDTH, SMOKE_PANEL_HEIGHT)
                    setContent { SmokeContent(state) }
                }
            val opened =
                JFrame("Spectre headed Robot contention").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    contentPane = panel
                    pack()
                    setLocationRelativeTo(null)
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            state.frame = opened
            state.composePanel = panel
            frame = opened
            frameReference.set(opened)
        }
        waitForFrame(frameReference)
        waitForLayout(state)
        delay(POST_LAYOUT_WARMUP_MS.milliseconds)
        return state
    }

    private fun start(probe: ProbeHandle, target: Point) {
        val java = Path.of(System.getProperty("java.home"), "bin", javaExecutableName()).toString()
        val builder =
            ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    PROBE_MAIN_CLASS,
                    target.x.toString(),
                    target.y.toString(),
                    probe.character.toString(),
                    BLOCK_LENGTH.toString(),
                    probe.readyFile.toString(),
                    goFile.toString(),
                    probe.outputFile.toString(),
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
        if (distinctDesktopKeys()) {
            // Red-proof lever (see the class KDoc). A Linux desktop resource key is derived from
            // WAYLAND_DISPLAY when it is set, while java.awt.Robot dispatches through DISPLAY, so
            // a fake value per probe removes mutual exclusion and leaves both typing at the same
            // X display. Production code is untouched by this and never reads the property.
            builder.environment()["WAYLAND_DISPLAY"] = "/spectre-red-proof-${probe.label}"
        }
        children += builder.start().also { probe.process = it }
    }

    private fun waitForExit(probe: ProbeHandle): Int {
        val process = requireNotNull(probe.process) { "probe '${probe.label}' was never started" }
        assertTrue(
            process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the '${probe.character}' probe JVM never finished within " +
                "${PROBE_TIMEOUT_SECONDS}s of the barrier opening",
        )
        return process.exitValue()
    }

    /** Blocks until both probes are connected, focused on the field, and parked on the gate. */
    private suspend fun awaitReady(vararg probes: ProbeHandle) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (probes.all { Files.isRegularFile(it.readyFile) }) return
            delay(READY_POLL_MILLIS.milliseconds)
        }
        val missing = probes.filterNot { Files.isRegularFile(it.readyFile) }.map(ProbeHandle::label)
        error("probe JVMs never signalled readiness: ${missing.joinToString()}")
    }

    /**
     * Reads the field once both blocks have landed, then waits a beat longer.
     *
     * The extra settle is not padding: returning the instant the expected length is reached would
     * hide a stray extra keystroke, and an over-long field is exactly the kind of evidence the
     * verdict should be allowed to see.
     */
    private suspend fun awaitFieldContent(state: SmokeState): String {
        val expectedLength = BLOCK_LENGTH * 2
        val deadline = System.nanoTime() + FIELD_SETTLE_TIMEOUT_MS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if (state.textValue.text.length >= expectedLength) break
            delay(FIELD_POLL_MILLIS.milliseconds)
        }
        delay(POST_CLICK_SETTLE_MS.milliseconds)
        return state.textValue.text
    }

    private fun distinctDesktopKeys(): Boolean =
        System.getProperty("spectre.headedContention.distinctDesktopKeys").toBoolean()

    private fun blockPlanSummary(): String =
        "each probe types ${BLOCK_LENGTH}x its own character " +
            "('$FIRST_BLOCK_CHARACTER' and '$SECOND_BLOCK_CHARACTER') in one typeText call" +
            if (distinctDesktopKeys()) " with mutual exclusion DISABLED (red proof)" else ""

    private fun javaExecutableName(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }

    private inner class ProbeHandle(val label: String, val character: Char) {
        val readyFile: Path = workDirectory.resolve("$label.ready")
        val outputFile: Path = workDirectory.resolve("$label.txt")
        var process: Process? = null

        fun readRecord(): ProbeRecord {
            assertTrue(
                Files.isRegularFile(outputFile),
                "the '$character' probe JVM wrote no timing record at $outputFile",
            )
            val lines = Files.readAllLines(outputFile)
            fun value(prefix: String): Long =
                lines.single { it.startsWith("$prefix ") }.removePrefix("$prefix ").trim().toLong()
            return ProbeRecord(character, value("REQUESTED"), value("TYPED_TO"))
        }
    }

    private data class ProbeRecord(val character: Char, val requestedAt: Long, val typedTo: Long)

    private companion object {
        const val PROBE_MAIN_CLASS: String =
            "dev.sebastiano.spectre.sample.HeadedRobotContentionProbe"

        const val READY_TIMEOUT_SECONDS: Long = 120
        const val READY_POLL_MILLIS: Long = 25
        const val PROBE_TIMEOUT_SECONDS: Long = 120
        const val CHILD_DESTROY_TIMEOUT_SECONDS: Long = 5
        const val FIELD_SETTLE_TIMEOUT_MS: Long = 15_000
        const val FIELD_POLL_MILLIS: Long = 50
        const val NANOS_PER_MILLI: Long = 1_000_000
    }
}

@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.fixture.BUTTON_CLICKED_PREFIX
import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import dev.sebastiano.spectre.agent.fixture.TAG_TEXT_FIELD
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Regression guard for #460 — Robot-backed input is silently discarded when the target is not on
 * the **input desktop**, while attach, semantics reads, and composition all keep working.
 *
 * Windows discards `SendInput` from a process whose desktop is not the session's input desktop, and
 * the JDK ignores that failure, so `click()` returns normally having done nothing. That is what
 * makes #460 present to users as "the semantics tree went stale" rather than "input was refused".
 * The condition is reached here by creating a second desktop inside the current window station and
 * launching the fixture onto it — no lock, no session 0, no elevation, no credentials.
 *
 * The two arms must be read together. **Control** is the same code on the ordinary desktop; if its
 * click does not land, the host is not usable for this test and the condition arm proves nothing.
 *
 * This is the end-to-end proof of #460's fix: the condition arm asserts that `click` now *raises*
 * rather than silently succeeding, and that the failure survives the whole chain — the `:core`
 * delivery guard, the wire taxonomy (`inputRejected`), and the attacher-side exception.
 *
 * Opt-in on Windows via [WindowsAttachE2eGate], like the other Robot-backed attach e2e tests:
 * ```
 * ./gradlew :agent:test --tests '*Issue460NonInputDesktopTest*' \
 *   -Pspectre.agent.attachE2e.allowWindows=true
 * ```
 */
@EnabledOnOs(OS.WINDOWS)
class Issue460NonInputDesktopTest {

    @Test
    fun `input is discarded off the input desktop while reads keep working`() {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "needs a desktop")
        assumeTrue(
            WindowsAttachE2eGate.isAllowed(),
            "Robot-backed attach e2e is opt-in on Windows " +
                "(-P${"spectre.agent.attachE2e.allowWindows"}=true on a physical desktop).",
        )
        val agentJar = agentJar()
        assumeTrue(isPwshAvailable(), "the hidden-desktop launcher needs PowerShell 7 (pwsh)")

        // Control first: if a click cannot land on the ordinary desktop right now, nothing this
        // test observes on the hidden desktop is attributable to the desktop.
        val control = clickOutcome(agentJar, desktop = null)
        assertTrue(
            control.textAfterClick.startsWith(BUTTON_CLICKED_PREFIX),
            "control click did not land on the ordinary desktop (host unusable for this test): " +
                "'${control.textBeforeClick}' -> '${control.textAfterClick}'",
        )
        assertNull(control.clickFailure, "a deliverable click must not be rejected")

        val hidden = clickOutcome(agentJar, desktop = "spectre460-${System.nanoTime()}")

        // Reads keep working: attach succeeded and the semantics tree came back populated.
        assertTrue(
            hidden.buttonNodes > 0,
            "semantics read failed off the input desktop; expected the button node to be found",
        )
        // Input does not, and Spectre must now say so rather than returning normally.
        val failure = hidden.clickFailure
        assertNotNull(failure, "click() silently succeeded off the input desktop -- #460 regressed")
        assertTrue(
            failure is SpectreAgentException &&
                failure.category == AgentErrorCategory.InputRejected,
            "undeliverable input must surface as inputRejected, was: $failure",
        )
        assertTrue(
            failure.message.orEmpty().contains("input desktop"),
            "the failure must name the platform constraint, was: ${failure.message}",
        )
        assertEquals(
            hidden.textBeforeClick,
            hidden.textAfterClick,
            "click was delivered off the input desktop; #460's premise no longer holds",
        )
    }

    private class Outcome(
        val textBeforeClick: String,
        val textAfterClick: String,
        val buttonNodes: Int,
        val clickFailure: Throwable?,
    )

    /**
     * Spawns the stock fixture (on [desktop], or the ordinary one when null), clicks, reads back.
     */
    private fun clickOutcome(agentJar: Path, desktop: String?): Outcome {
        val process = spawnFixture(desktop)
        try {
            val pid = process.second
            AgentAttach.attach(pid, attachOptions(agentJar, pid)).use { a ->
                Thread.sleep(SETTLE_MS)
                val before = a.findByTestTag(TAG_TEXT_FIELD).first().editableText.orEmpty()
                val buttons = a.findByTestTag(TAG_BUTTON)
                val clickFailure = runCatching { a.click(buttons.first()) }.exceptionOrNull()
                Thread.sleep(SETTLE_MS)
                val after = a.findByTestTag(TAG_TEXT_FIELD).first().editableText.orEmpty()
                return Outcome(before, after, buttons.size, clickFailure)
            }
        } finally {
            killTree(process.first)
        }
    }

    /**
     * Returns the launcher process and the **fixture's own** pid. When a desktop is named the JVM
     * is a grandchild of this process, so the pid is parsed off the READY sentinel rather than
     * taken from the [Process] handle.
     *
     * The classpath travels in the environment: `CreateProcess` takes one command-line string with
     * a 32k limit that a Gradle test classpath can approach, and `java` honours `CLASSPATH`.
     */
    private fun spawnFixture(desktop: String?): Pair<Process, Long> {
        val javaExe = FixtureJavaHome.javaExecutable().toString()
        val args =
            listOf(
                "-XX:+EnableDynamicAgentLoading",
                "-Djava.awt.headless=false",
                "-Dcompose.application.configure.swing.globals=true",
                FIXTURE_MAIN,
            )
        val builder =
            if (desktop == null) {
                ProcessBuilder(listOf(javaExe) + args)
            } else {
                ProcessBuilder(
                    "pwsh",
                    "-NoProfile",
                    "-File",
                    launcherScript().toString(),
                    "-DesktopName",
                    desktop,
                )
            }
        builder.environment()["CLASSPATH"] = System.getProperty("java.class.path")
        // The exe and command line travel in the environment, never as arguments: both can contain
        // spaces, and Java's legacy Windows argument quoting does not escape embedded quotes, so a
        // JDK under Program Files would be split before pwsh ever parsed it.
        builder.environment()["SPECTRE_LAUNCH_EXE"] = javaExe
        builder.environment()["SPECTRE_LAUNCH_CMDLINE"] =
            (listOf("\"$javaExe\"") + args).joinToString(" ")
        builder.redirectErrorStream(true)

        val process = builder.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val pid = AtomicLong(-1)
        val ready = CountDownLatch(1)
        Thread {
                runCatching {
                    generateSequence(reader::readLine).forEach { line ->
                        if (line.startsWith(READY_SENTINEL) && ready.count > 0) {
                            pid.set(
                                line
                                    .substringAfter("pid=", "")
                                    .substringBefore(' ')
                                    .trim()
                                    .toLongOrNull() ?: -1L
                            )
                            ready.countDown()
                        }
                    }
                }
            }
            .apply {
                isDaemon = true
                name = "issue460-fixture-drainer"
                start()
            }
        try {
            check(ready.await(READY_TIMEOUT_S, TimeUnit.SECONDS)) {
                "fixture never signalled READY"
            }
            check(pid.get() > 0) { "could not parse the fixture pid from the READY sentinel" }
        } catch (failure: IllegalStateException) {
            // Nothing owns the process yet, so the caller's finally cannot reach it. Leaving a JVM
            // running on a desktop nobody can see or close is the worst possible leak.
            killTree(process)
            throw failure
        }
        Thread.sleep(SETTLE_MS)
        return process to pid.get()
    }

    /** The fixture JVM is a grandchild behind the launcher, so kill descendants first. */
    private fun killTree(process: Process) {
        process.descendants().forEach { runCatching { it.destroyForcibly() } }
        process.destroyForcibly()
        process.waitFor(SHUTDOWN_S, TimeUnit.SECONDS)
    }

    /** A box with only Windows PowerShell 5.1 should skip, not error. */
    private fun isPwshAvailable(): Boolean =
        runCatching {
                val probe = ProcessBuilder("pwsh", "-NoProfile", "-Command", "exit 0").start()
                probe.waitFor(PWSH_PROBE_S, TimeUnit.SECONDS) && probe.exitValue() == 0
            }
            .getOrDefault(false)

    /** The launcher ships as a test resource; PowerShell needs it as a real file on disk. */
    private fun launcherScript(): Path {
        // Unique per call: a fixed name in the shared temp dir races parallel test JVMs.
        val target =
            Paths.get(System.getProperty("java.io.tmpdir"), "spectre460-${System.nanoTime()}.ps1")
        javaClass.getResourceAsStream(LAUNCHER).use { stream ->
            checkNotNull(stream) { "missing test resource $LAUNCHER" }
            Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun agentJar(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(prop.isNullOrBlank(), "needs -Ddev.sebastiano.spectre.agent.runtimeJar")
        val path = Paths.get(prop!!)
        assumeFalse(!Files.isRegularFile(path), "agent runtime jar missing at $path")
        return path
    }

    private fun attachOptions(agentJar: Path, pid: Long) =
        AttachOptions(
            agentJarPath = agentJar,
            udsPath = AttachOptions.defaultUdsPath(pid),
            attachTimeoutMs = ATTACH_TIMEOUT_MS,
        )

    private companion object {
        const val FIXTURE_MAIN = "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt"
        const val LAUNCHER = "launch-on-desktop.ps1"
        const val SETTLE_MS = 900L
        const val ATTACH_TIMEOUT_MS = 15_000L
        const val READY_TIMEOUT_S = 60L
        const val SHUTDOWN_S = 3L
        const val PWSH_PROBE_S = 10L
    }
}

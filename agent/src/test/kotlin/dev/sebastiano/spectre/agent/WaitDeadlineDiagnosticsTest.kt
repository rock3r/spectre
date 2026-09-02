@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.runtime.ReflectiveAutomatorHandler
import dev.sebastiano.spectre.agent.runtime.WaitFakeAutomator
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.IpcClient
import dev.sebastiano.spectre.agent.transport.IpcServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Every wait's timeout diagnostics must survive attach.
 *
 * The wire deadline the client attaches to a wait op is enforced by the agent twice: a scheduler
 * claims the op at the deadline, and `executeOp` discards the handler's answer for "Deadline
 * elapsed during op" if the deadline passed while the handler ran. On an exact `now + timeoutMs`
 * deadline, both fire at the same instant the automator's own timeout does — a race the automator
 * always loses, so callers got the transport's phrase instead of the wait's real message.
 *
 * That message is what each wait exists to produce: `waitForIdle` names the busy idling resources,
 * `waitForVisualIdle` names the sample counts, `waitUntilGone` names the selector and how many
 * nodes are still present. This drives the **real** boundary — a public [AttachedAutomator] over a
 * real [IpcClient]/[IpcServer] pair on a Unix domain socket, through the reflective bridge — with
 * fakes that genuinely consume their wait budget before failing, so the race is live rather than
 * hypothetical.
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class WaitDeadlineDiagnosticsTest {
    private val udsPath: Path =
        udsBase().resolve("sp-wd-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `waitForNode timeout keeps the automator's message, not the wire deadline's`() {
        val diagnostic = "Timed out waiting for ${WAIT_MS} ms"
        val automator =
            WaitFakeAutomator(
                waitForNodeImpl = { _, _, timeoutRaw, _ -> failAfterBudget(timeoutRaw, diagnostic) }
            )

        withAttached(automator) { attached ->
            val failure =
                assertFailsWithAgentTimeout("waitForNode") {
                    attached.waitForNode(tag = "never-appears", timeoutMs = WAIT_MS)
                }
            assertDiagnosticSurvived(failure, diagnostic)
        }
    }

    @Test
    fun `waitForIdle timeout keeps the busy-resource diagnostic`() {
        val diagnostic = "waitForIdle timed out after ${WAIT_MS}ms: network queue has 3 in flight"
        val automator =
            WaitFakeAutomator(
                waitForIdleImpl = { timeoutRaw, _, _ -> failAfterBudget(timeoutRaw, diagnostic) }
            )

        withAttached(automator) { attached ->
            val failure =
                assertFailsWithAgentTimeout("waitForIdle") {
                    attached.waitForIdle(timeoutMs = WAIT_MS)
                }
            assertDiagnosticSurvived(failure, diagnostic)
        }
    }

    @Test
    fun `waitForVisualIdle timeout keeps the frame-sample diagnostic`() {
        val diagnostic =
            "waitForVisualIdle timed out after ${WAIT_MS}ms: 12 samples, pixels never settled"
        val automator =
            WaitFakeAutomator(
                waitForVisualIdleImpl = { timeoutRaw, _, _ ->
                    failAfterBudget(timeoutRaw, diagnostic)
                }
            )

        withAttached(automator) { attached ->
            val failure =
                assertFailsWithAgentTimeout("waitForVisualIdle") {
                    attached.waitForVisualIdle(timeoutMs = WAIT_MS)
                }
            assertDiagnosticSurvived(failure, diagnostic)
        }
    }

    @Test
    fun `waitUntilGone timeout keeps the still-present diagnostic`() {
        val diagnostic =
            """waitUntilGone timed out after ${WAIT_MS}ms: 2 node(s) matching tag="popup.body" """ +
                "still present in tracked windows"
        val automator =
            WaitFakeAutomator(
                waitUntilGoneImpl = { _, _, timeoutRaw, _ ->
                    failAfterBudget(timeoutRaw, diagnostic)
                }
            )

        withAttached(automator) { attached ->
            val failure =
                assertFailsWithAgentTimeout("waitUntilGone") {
                    attached.waitUntilGone(tag = "popup.body", timeoutMs = WAIT_MS)
                }
            assertDiagnosticSurvived(failure, diagnostic)
        }
    }

    /**
     * A wedged automator — one that never returns — must still fail closed on the wire rather than
     * hanging, so the slack that lets a real timeout through cannot become an unbounded wait.
     *
     * Asserts *promptness*, not a taxonomy. Which category a wedged op comes back as is a genuine
     * race in the session: [MultiplexedIpcSession]'s deadline task claims the response as `timeout`
     * after aborting the worker, while the aborted worker itself prefers `cancelled` if it wins the
     * same claim. Both are legitimate answers for "aborted at the deadline" — Linux happened to
     * land on `timeout` every time and Windows CI on `cancelled` — so pinning one would be pinning
     * a coin flip. Returning far sooner than the wedge is the property that actually matters here.
     */
    @Test
    fun `a wait that never returns still fails closed on the wire deadline`() {
        val automator =
            WaitFakeAutomator(
                waitUntilGoneImpl = { _, _, _, _ ->
                    Thread.sleep(WEDGED_SLEEP_MS)
                    error("wedged automator should have been claimed by the wire deadline")
                }
            )

        withAttached(automator) { attached ->
            val startedAt = System.nanoTime()
            val failure =
                assertFailsWithAgentTimeout("waitUntilGone") {
                    attached.waitUntilGone(tag = "popup.body", timeoutMs = WAIT_MS)
                }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(
                failure.category in setOf(AgentErrorCategory.Timeout, AgentErrorCategory.Cancelled),
                "a wedged wait must be claimed as an abort, got ${failure.category}: " +
                    failure.message,
            )
            assertTrue(
                elapsedMs < WEDGED_CLAIM_BUDGET_MS,
                "wedged wait took ${elapsedMs}ms to fail closed; the wire deadline should have " +
                    "claimed it around ${WAIT_MS}ms + slack, well before the ${WEDGED_SLEEP_MS}ms " +
                    "wedge — the slack must not become an unbounded wait",
            )
        }
    }

    /** Sleeps the wait's own budget, then fails the way the real automator does. */
    private fun failAfterBudget(timeoutRaw: Long, message: String): Nothing {
        Thread.sleep(millisFromDurationStorage(timeoutRaw))
        error(message)
    }

    private fun assertDiagnosticSurvived(failure: SpectreAgentException, diagnostic: String) {
        assertEquals(AgentErrorCategory.Timeout, failure.category)
        assertTrue(
            failure.message.orEmpty().contains(diagnostic),
            "wait diagnostics were replaced by the wire deadline's own phrase: ${failure.message}",
        )
    }

    private inline fun assertFailsWithAgentTimeout(
        op: String,
        block: () -> Unit,
    ): SpectreAgentException {
        val thrown =
            try {
                block()
                null
            } catch (ex: Exception) {
                ex
            }
        return assertIs<SpectreAgentException>(thrown, "$op should have failed, got $thrown")
    }

    private fun withAttached(automator: Any, block: (AttachedAutomator) -> Unit) {
        IpcServer(udsPath, ReflectiveAutomatorHandler(automator)).use {
            awaitSocket(udsPath)
            AttachedAutomator(pid = ProcessHandle.current().pid(), client = IpcClient(udsPath)) {}
                .use(block)
        }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS path $path did not appear within ${timeoutMs}ms")
    }

    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            Path.of(System.getProperty("java.io.tmpdir"))
        } else {
            Path.of("/tmp")
        }

    private companion object {
        /**
         * Short enough to keep the suite quick, long enough that the sleeping fake and the wire
         * deadline genuinely race rather than being ordered by scheduling luck.
         */
        const val WAIT_MS: Long = 300

        /** Longer than the wire deadline's slack, so the wedged case is claimed on the wire. */
        const val WEDGED_SLEEP_MS: Long = 30_000

        /**
         * Ceiling for the wedged case to fail closed. Generous against a loaded CI runner while
         * still far below [WEDGED_SLEEP_MS], so passing means the deadline fired rather than the
         * wedge simply finishing.
         */
        const val WEDGED_CLAIM_BUDGET_MS: Long = 15_000

        /**
         * Inverse of the reflective bridge's `durationStorageFromMs` for the nanos-storage form.
         */
        fun millisFromDurationStorage(raw: Long): Long = (raw shr 1) / 1_000_000L
    }
}

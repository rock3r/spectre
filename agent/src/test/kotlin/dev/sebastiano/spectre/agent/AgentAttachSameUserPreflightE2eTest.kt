@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * End-to-end same-user preflight through the real [AgentAttach.attach] entry point (#166).
 *
 * These tests intentionally do **not** mock UID resolvers: they use the production
 * [ProcessUidLookup] + [PosixUserPreflight] path that attach calls before `VirtualMachine.attach`.
 *
 * - **Same UID:** attach to a short-lived child JVM owned by this user must not fail preflight.
 * - **Different UID:** attach to a known foreign-UID process (typically pid 1 / root) must fail
 *   with [AttachPermissionDeniedException] before the JDK attach handshake.
 *
 * Windows is out of scope for this class (name-based preflight unchanged; no SID work in #166).
 *
 * Full Compose attach happy-path remains covered by [AgentAttachIntegrationTest]; this class
 * isolates ownership preflight on the shipped attach path.
 */
@DisabledOnOs(OS.WINDOWS)
class AgentAttachSameUserPreflightE2eTest {

    @Test
    fun `attach to a same-UID child JVM passes the ownership preflight`() {
        val agentJar = locateAgentJarOrSkip()
        val lookup = ProcessUidLookup.forOs()
        val myUid = lookup.uidOf(ProcessHandle.current().pid())
        assumeTrue(myUid != null, "host UID lookup must work for this e2e")

        spawnBareJvm().use { child ->
            val childUid = lookup.uidOf(child.pid)
            assumeTrue(childUid != null, "child UID must be resolvable")
            assertTrue(
                childUid == myUid,
                "expected same-UID child (myUid=$myUid childUid=$childUid pid=${child.pid})",
            )

            // Exercise the shipped preflight path. A bare JVM without a Compose UI may fail later
            // in agent bootstrap or attach timeout; ownership must not be the failure mode.
            val thrown =
                runCatching {
                        AgentAttach.attach(
                                child.pid,
                                AttachOptions(agentJarPath = agentJar, attachTimeoutMs = 3_000),
                            )
                            .close()
                    }
                    .exceptionOrNull()

            assertTrue(
                thrown !is AttachPermissionDeniedException,
                "same-UID child must not be rejected by same-user preflight; got: $thrown",
            )
        }
    }

    @Test
    fun `attach to a different-UID process fails preflight with uid diagnostics`() {
        val agentJar = locateAgentJarOrSkip()
        val lookup = ProcessUidLookup.forOs()
        val myUid = lookup.uidOf(ProcessHandle.current().pid())
        assumeTrue(myUid != null, "host UID lookup must work for this e2e")

        val foreign = findDifferentUidProcess(lookup, myUid!!)
        assumeTrue(foreign != null, "no different-UID process available on this host")

        val (foreignPid, foreignUid) = foreign!!
        val ex =
            assertFailsWith<AttachPermissionDeniedException> {
                AgentAttach.attach(
                    foreignPid,
                    AttachOptions(agentJarPath = agentJar, attachTimeoutMs = 2_000),
                )
            }

        val message = ex.message.orEmpty()
        assertTrue(
            message.contains("uid=$foreignUid") && message.contains("uid=$myUid"),
            "expected both UIDs in message, got: $message",
        )
        assertTrue(message.contains("pid=$foreignPid"), "expected target pid in message: $message")
    }

    /**
     * Prefer pid 1 (init/launchd, almost always root). Fall back to a short scan of live processes
     * when pid 1 is unreadable or same-UID (unusual containers).
     */
    private fun findDifferentUidProcess(lookup: ProcessUidLookup, myUid: Long): Pair<Long, Long>? {
        val pid1Uid = lookup.uidOf(1L)
        if (pid1Uid != null && pid1Uid != myUid) return 1L to pid1Uid

        return ProcessHandle.allProcesses()
            .limit(64)
            .map { handle ->
                val uid = lookup.uidOf(handle.pid())
                if (uid != null && uid != myUid) handle.pid() to uid else null
            }
            .filter { it != null }
            .findFirst()
            .orElse(null)
    }

    /**
     * Minimal child JVM — enough for ProcessHandle ownership + attach preflight. Does not open a
     * UI; only used to prove same-UID preflight on the real attach path.
     */
    private fun spawnBareJvm(): BareJvm {
        val javaHome = System.getProperty("java.home")
        val javaBin = Paths.get(javaHome, "bin", "java").toString()
        val process =
            ProcessBuilder(
                    javaBin,
                    "-cp",
                    System.getProperty("java.class.path"),
                    AgentAttachIdleJvmMain::class.java.name,
                )
                .redirectErrorStream(true)
                .start()

        var attempts = 0
        while (!process.isAlive && attempts < 40) {
            Thread.sleep(25)
            attempts++
        }
        check(process.isAlive) { "bare JVM child exited immediately" }
        return BareJvm(process)
    }

    private fun locateAgentJarOrSkip(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(
            prop.isNullOrBlank(),
            "Requires -Ddev.sebastiano.spectre.agent.runtimeJar; :agent:test sets it.",
        )
        return Paths.get(prop!!)
    }

    private class BareJvm(private val process: Process) : AutoCloseable {
        val pid: Long = process.pid()

        override fun close() {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}

/**
 * Keeps a JVM process alive until destroyed — test-only attach target for same-UID preflight e2e.
 * Lives as a top-level type so [ProcessBuilder] can resolve a stable main class name.
 */
object AgentAttachIdleJvmMain {
    @JvmStatic
    fun main(args: Array<String>) {
        // Park forever; parent destroyForcibly() ends the process.
        CountDownLatch(1).await()
    }
}

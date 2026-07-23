package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

/**
 * Grace-then-force process-tree teardown via [ProcessHandle].
 *
 * For **direct** launches, [destroyTree] is invoked on the launched process root so children are
 * reaped too. For **Gradle-ish** launches, call [destroyTree] only on the discovered app JVM —
 * never on the Gradle client or daemon.
 */
@ExperimentalSpectreAgentApi
public object ProcessTreeTeardown {

    public const val DEFAULT_GRACE_MS: Long = 2_000

    /**
     * Destroy [root] and all of its live descendants: first [ProcessHandle.destroy] (graceful),
     * wait up to [graceMs], then [ProcessHandle.destroyForcibly] on anything still alive.
     *
     * Descendants are signalled before the root so a parent waiting on children can exit cleanly
     * during the grace window when the OS allows it.
     *
     * @return true when [root] is no longer alive after the force pass (or was already dead).
     */
    public fun destroyTree(root: ProcessHandle, graceMs: Long = DEFAULT_GRACE_MS): Boolean {
        if (!root.isAlive) return true
        val descendants =
            try {
                root.descendants().asSequence().toList()
            } catch (_: UnsupportedOperationException) {
                emptyList()
            }
        for (child in descendants) {
            if (child.isAlive) {
                runCatching { child.destroy() }
            }
        }
        runCatching { root.destroy() }

        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
        while (System.nanoTime() < deadlineNs) {
            if (!root.isAlive && descendants.none { it.isAlive }) {
                return true
            }
            sleepQuietly(POLL_MS)
        }

        for (child in descendants) {
            if (child.isAlive) {
                runCatching { child.destroyForcibly() }
            }
        }
        if (root.isAlive) {
            runCatching { root.destroyForcibly() }
        }
        // Brief wait for force kills to register.
        val forceDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FORCE_WAIT_MS)
        while (System.nanoTime() < forceDeadlineNs && root.isAlive) {
            sleepQuietly(POLL_MS)
        }
        return !root.isAlive
    }

    /**
     * Destroy a single process tree identified by [pid] when the handle is still resolvable.
     *
     * @return true when the pid is gone (or was never resolvable as alive).
     */
    public fun destroyTreeByPid(pid: Long, graceMs: Long = DEFAULT_GRACE_MS): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return true
        return destroyTree(handle, graceMs)
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private const val POLL_MS: Long = 50
    private const val FORCE_WAIT_MS: Long = 500
}

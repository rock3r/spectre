package dev.sebastiano.spectre.core

import kotlin.time.Duration
import kotlinx.coroutines.delay

/** Thrown when [ComposeAutomator.waitForIdle] cannot reach an idle state before the timeout. */
class IdleTimeoutException(message: String) : RuntimeException(message)

internal interface MonotonicClock {
    fun now(): Long
}

internal class SystemClock : MonotonicClock {
    override fun now(): Long = System.nanoTime() / NANOS_PER_MILLI
}

internal class FakeClock(private var nowMs: Long = 0L) : MonotonicClock {
    override fun now(): Long = nowMs

    fun advance(duration: Duration) {
        nowMs += duration.inWholeMilliseconds
    }
}

/**
 * Core wait loop. Drains the EDT, queries every idling resource, samples the supplied fingerprint,
 * and returns once all resources have reported idle and the fingerprint has remained stable for
 * [quietPeriod].
 *
 * Production callers should use [ComposeAutomator.waitForIdle]. The injectable [clock]/[sleep] seam
 * exists so the loop can be exercised against virtual time in tests without coupling to a real EDT
 * or live Compose tree.
 */
internal suspend fun waitForIdleInternal(
    timeout: Duration,
    quietPeriod: Duration,
    pollInterval: Duration,
    idlingResources: () -> Collection<AutomatorIdlingResource>,
    drainEdt: () -> Unit,
    fingerprint: () -> String,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    val deadline = clock.now() + timeout.inWholeMilliseconds
    var stableSince: Long? = null
    var lastFingerprint: String? = null

    while (true) {
        drainEdt()
        val resources = idlingResources()
        val busy = resources.firstOrNull { !it.isIdleNow }
        var idleReached = false

        if (busy != null) {
            stableSince = null
            lastFingerprint = null
        } else {
            val fp = fingerprint()
            val now = clock.now()
            if (fp != lastFingerprint) {
                lastFingerprint = fp
                stableSince = now
            }
            // The elapsed check runs even on the first matching sample, so quietPeriod = 0
            // resolves to "first idle sample wins".
            val sinceMs = stableSince ?: now
            if (now - sinceMs >= quietPeriod.inWholeMilliseconds) idleReached = true
        }

        val nowAfterSample = clock.now()
        if (idleReached && nowAfterSample <= deadline) return
        if (nowAfterSample >= deadline) {
            val busyResources = resources.filter { !it.isIdleNow }
            val diagnostic =
                if (busyResources.isNotEmpty()) {
                    val messages = busyResources.mapNotNull { it.diagnosticMessage() }
                    if (messages.isNotEmpty()) {
                        messages.joinToString(separator = "; ")
                    } else {
                        // None of the busy resources implement diagnosticMessage(), but they
                        // are still the actual cause — say so instead of misattributing the
                        // timeout to the fingerprint.
                        "${busyResources.size} idling resource(s) reported busy"
                    }
                } else {
                    "UI fingerprint did not stabilise"
                }
            throw IdleTimeoutException(
                "waitForIdle timed out after ${timeout.inWholeMilliseconds}ms: $diagnostic"
            )
        }
        sleep(pollInterval)
    }
}

/**
 * Visual-idle loop: waits for [stableFrames] consecutive identical frame hashes.
 *
 * Stricter than [waitForIdleInternal]: the UI must not just be structurally stable, it must also
 * paint the same pixels for several frames in a row. Useful before screenshots and recordings to
 * avoid capturing in the middle of an animation.
 */
internal suspend fun waitForVisualIdleInternal(
    timeout: Duration,
    stableFrames: Int,
    pollInterval: Duration,
    frameHash: () -> Int,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    require(stableFrames > 0) { "stableFrames must be positive, was $stableFrames" }
    val deadline = clock.now() + timeout.inWholeMilliseconds
    val window = ArrayDeque<Int>(stableFrames)

    while (true) {
        val hash = frameHash()
        if (window.isNotEmpty() && window.last() != hash) {
            window.clear()
        }
        window.addLast(hash)
        if (window.size > stableFrames) window.removeFirst()
        val streakComplete = window.size == stableFrames && window.all { it == window.first() }

        val nowAfterSample = clock.now()
        if (streakComplete && nowAfterSample <= deadline) return
        if (nowAfterSample >= deadline) {
            throw IdleTimeoutException(
                "waitForVisualIdle timed out after ${timeout.inWholeMilliseconds}ms: " +
                    "frames did not stabilise across $stableFrames samples"
            )
        }
        sleep(pollInterval)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

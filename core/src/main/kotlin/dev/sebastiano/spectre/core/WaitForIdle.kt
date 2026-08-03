package dev.sebastiano.spectre.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.delay

/** Thrown when [ComposeAutomator.waitForIdle] cannot reach an idle state before the timeout. */
public class IdleTimeoutException(message: String) : RuntimeException(message)

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
    drainEdt: (remainingMs: Long) -> Unit,
    fingerprint: (remainingMs: Long) -> String,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    val deadline = clock.now() + timeout.inWholeMilliseconds
    var stableSince: Long? = null
    var lastFingerprint: String? = null

    while (true) {
        drainEdt((deadline - clock.now()).coerceAtLeast(0))
        val resources = idlingResources()
        val busy = resources.firstOrNull { !it.isIdleNow }
        var idleReached = false

        if (busy != null) {
            stableSince = null
            lastFingerprint = null
        } else {
            val fp = fingerprint((deadline - clock.now()).coerceAtLeast(0))
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
 *
 * A `null` frame hash means the sample was unsampleable (capture budget exceeded or no Compose
 * surface available). Those samples clear the streak and are counted so a timeout can name the real
 * cause instead of only blaming unstable pixels.
 */
internal suspend fun waitForVisualIdleInternal(
    timeout: Duration,
    stableFrames: Int,
    pollInterval: Duration,
    frameHash: suspend (remainingMs: Long) -> Int?,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    require(stableFrames > 0) { "stableFrames must be positive, was $stableFrames" }
    val deadline = clock.now() + timeout.inWholeMilliseconds
    val window = ArrayDeque<Int>(stableFrames)
    var sampleCount = 0
    var unsampleableCount = 0

    while (true) {
        val hash = frameHash((deadline - clock.now()).coerceAtLeast(0))
        sampleCount++
        val streakComplete =
            if (hash == null) {
                unsampleableCount++
                window.clear()
                false
            } else {
                if (window.isNotEmpty() && window.last() != hash) {
                    window.clear()
                }
                window.addLast(hash)
                if (window.size > stableFrames) window.removeFirst()
                window.size == stableFrames && window.all { it == window.first() }
            }

        val nowAfterSample = clock.now()
        if (streakComplete && nowAfterSample <= deadline) return
        if (nowAfterSample >= deadline) {
            throw IdleTimeoutException(
                "waitForVisualIdle timed out after ${timeout.inWholeMilliseconds}ms: " +
                    visualIdleTimeoutDiagnostic(
                        stableFrames = stableFrames,
                        sampleCount = sampleCount,
                        unsampleableCount = unsampleableCount,
                    )
            )
        }
        sleep(pollInterval)
    }
}

/**
 * Builds the diagnostic tail for a [waitForVisualIdleInternal] timeout.
 *
 * Pure so unit tests can pin the wording without driving the full wait loop.
 */
internal fun visualIdleTimeoutDiagnostic(
    stableFrames: Int,
    sampleCount: Int,
    unsampleableCount: Int,
): String {
    val unstable = "frames did not stabilise across $stableFrames samples"
    if (unsampleableCount <= 0 || sampleCount <= 0) return unstable
    val unsampleable =
        "$unsampleableCount/$sampleCount samples were unsampleable " +
            "(capture budget exceeded or no Compose surface available)"
    return if (unsampleableCount == sampleCount) {
        // Every attempt failed to produce a hash — do not send the caller hunting for animations.
        unsampleable
    } else {
        "$unsampleable; $unstable"
    }
}

/**
 * Applies the visual-idle capture budget without treating a cold native capture like a changed
 * frame. The first successful capture is allowed to use the caller's remaining timeout, because
 * platform capture paths can have a one-off startup cost. Once one sample has completed, every
 * later sample is capped at [steadyStateBudgetMs] so a stuck capture still cannot overrun the
 * public wait timeout.
 */
internal class BoundedFrameHasher(
    private val steadyStateBudgetMs: Long,
    private val sample: suspend (budgetMs: Long) -> Int?,
) {
    private val hasSuccessfulSample = AtomicBoolean(false)

    suspend fun hash(remainingMs: Long): Int? {
        val budgetMs =
            if (hasSuccessfulSample.get()) {
                remainingMs.coerceAtMost(steadyStateBudgetMs)
            } else {
                remainingMs
            }
        return sample(budgetMs)?.also { hasSuccessfulSample.set(true) }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

package dev.sebastiano.spectre.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal suspend fun <T : Any> waitUntil(
    timeout: Duration = 5.seconds,
    pollInterval: Duration = 100.milliseconds,
    predicate: () -> T?,
): T =
    withTimeout(timeout) {
        while (true) {
            predicate()?.let {
                return@withTimeout it
            }
            delay(pollInterval)
        }

        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

/**
 * Absence-wait loop behind [ComposeAutomator.waitUntilGone]: samples [countPresent] until it
 * reports zero matching nodes, then returns.
 *
 * Unlike [waitUntil], the timeout is a deadline the loop checks itself rather than a `withTimeout`
 * cancellation, so running out of time raises a plain [IllegalStateException] naming [selector],
 * the timeout, and how many nodes were still present at the last observation — the diagnostics an
 * absence wait exists to report. A bare `TimeoutCancellationException` says nothing about what
 * stayed on screen.
 *
 * The loop always samples at least once and takes one final sample at the deadline, so a zero
 * timeout still gets a real observation and the count in the error is the last thing seen. Each
 * sleep is clamped to the time left, so a [pollInterval] longer than the remaining timeout never
 * carries the wait a full interval past the deadline (Codex on #482).
 *
 * The injectable [clock]/[sleep] seam mirrors [waitForIdleInternal] so the loop can run on virtual
 * time in tests without a live Compose tree.
 */
internal suspend fun waitUntilGoneInternal(
    timeout: Duration,
    pollInterval: Duration,
    selector: String,
    countPresent: () -> Int,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    val deadline = clock.now() + timeout.inWholeMilliseconds
    while (true) {
        val present = countPresent()
        if (present == 0) return
        val remainingMs = deadline - clock.now()
        if (remainingMs <= 0) {
            error(
                "waitUntilGone timed out after ${timeout.inWholeMilliseconds}ms: " +
                    "$present node(s) matching $selector still present in tracked windows"
            )
        }
        sleep(minOf(pollInterval, remainingMs.milliseconds))
    }
}

/** Renders a tag/text node selector as `tag="…"`, `text="…"` for wait diagnostics. */
internal fun describeNodeSelector(tag: String?, text: String?): String =
    listOfNotNull(tag?.let { "tag=\"$it\"" }, text?.let { "text=\"$it\"" }).joinToString(", ")

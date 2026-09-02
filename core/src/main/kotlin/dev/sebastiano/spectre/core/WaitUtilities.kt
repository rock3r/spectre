package dev.sebastiano.spectre.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal suspend fun <T : Any> pollUntilNotNull(
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
 * Deadline-based poll loop shared by [waitUntilGoneInternal] and [waitUntilInternal]: samples
 * [satisfied] until it reports `true`, then returns; on running out of time raises a plain
 * [IllegalStateException] carrying [timeoutMessage].
 *
 * Unlike [pollUntilNotNull], the timeout is a deadline the loop checks itself rather than a
 * `withTimeout` cancellation, so the failure is an ordinary exception naming what was awaited
 * rather than a bare `TimeoutCancellationException` that says nothing about it. [timeoutMessage] is
 * evaluated only on timeout, so a caller can fold in whatever the last observation saw.
 *
 * The loop always samples at least once and takes one final sample at the deadline, so a zero
 * timeout still gets a real observation and the message describes the last thing seen. Each sleep
 * is clamped to the time left, so a [pollInterval] longer than the remaining timeout never carries
 * the wait a full interval past the deadline (Codex on #482).
 *
 * The injectable [clock]/[sleep] seam mirrors [waitForIdleInternal] so the loop can run on virtual
 * time in tests without a live Compose tree.
 */
private suspend fun pollUntilDeadline(
    timeout: Duration,
    pollInterval: Duration,
    satisfied: () -> Boolean,
    timeoutMessage: () -> String,
    clock: MonotonicClock,
    sleep: suspend (Duration) -> Unit,
) {
    val deadline = clock.now() + timeout.inWholeMilliseconds
    while (true) {
        if (satisfied()) return
        val remainingMs = deadline - clock.now()
        if (remainingMs <= 0) error(timeoutMessage())
        sleep(minOf(pollInterval, remainingMs.milliseconds))
    }
}

/**
 * Absence-wait loop behind [ComposeAutomator.waitUntilGone]: samples [countPresent] until it
 * reports zero matching nodes, then returns.
 *
 * Running out of time raises an [IllegalStateException] naming [selector], the timeout, and how
 * many nodes were still present at the last observation — the diagnostics an absence wait exists to
 * report. See [pollUntilDeadline] for the loop's timing contract.
 */
internal suspend fun waitUntilGoneInternal(
    timeout: Duration,
    pollInterval: Duration,
    selector: String,
    countPresent: () -> Int,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    var lastPresent = 0
    pollUntilDeadline(
        timeout = timeout,
        pollInterval = pollInterval,
        satisfied = {
            lastPresent = countPresent()
            lastPresent == 0
        },
        timeoutMessage = {
            "waitUntilGone timed out after ${timeout.inWholeMilliseconds}ms: " +
                "$lastPresent node(s) matching $selector still present in tracked windows"
        },
        clock = clock,
        sleep = sleep,
    )
}

/**
 * Predicate-wait loop behind [ComposeAutomator.waitUntil]: samples [condition] until it reports
 * `true`, then returns.
 *
 * Running out of time raises an [IllegalStateException] naming the caller's own [description] and
 * the timeout. The description is all the loop knows about the condition — a predicate, unlike a
 * node selector, cannot be rendered back into a diagnostic — which is why
 * [ComposeAutomator.waitUntil] requires a non-blank one. See [pollUntilDeadline] for the loop's
 * timing contract.
 */
internal suspend fun waitUntilInternal(
    timeout: Duration,
    pollInterval: Duration,
    description: String,
    condition: () -> Boolean,
    clock: MonotonicClock = SystemClock(),
    sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    pollUntilDeadline(
        timeout = timeout,
        pollInterval = pollInterval,
        satisfied = condition,
        timeoutMessage = {
            "waitUntil timed out after ${timeout.inWholeMilliseconds}ms: " +
                "condition \"$description\" never held in tracked windows"
        },
        clock = clock,
        sleep = sleep,
    )
}

/** Renders a tag/text node selector as `tag="…"`, `text="…"` for wait diagnostics. */
internal fun describeNodeSelector(tag: String?, text: String?): String =
    listOfNotNull(tag?.let { "tag=\"$it\"" }, text?.let { "text=\"$it\"" }).joinToString(", ")

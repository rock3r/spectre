package dev.sebastiano.spectre.testing

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default wall-clock timeout for [runSpectreTest]. Generous enough for multi-window UI tests;
 * override per call when a tighter bound is needed.
 */
public val DefaultSpectreTestTimeout: Duration = 2.minutes

/**
 * Runs a Spectre UI test body with **real wall-clock time** and structured-concurrency checks.
 *
 * Prefer this over [runBlocking] for Spectre tests: unfinished child coroutines started in the body
 * fail the test with a clear report instead of hanging the suite or being silently orphaned.
 *
 * Prefer this over `kotlinx.coroutines.test.runTest`: Spectre uses [kotlinx.coroutines.delay]
 * internally for timing-sensitive work (`longClick` hold durations, `swipe` step pacing,
 * clipboard-settle / post-paste settle in `pasteText`). `runTest` drives a virtual scheduler and
 * collapses those delays to zero, so holds never hold, swipes jump, and paste can race. This runner
 * keeps the system clock for every `delay` in the body and in Spectre.
 *
 * @param context additional coroutine context elements (combined with the real-time [runBlocking]
 *   dispatcher). Do not install a virtual-time test scheduler here if you need real delays.
 * @param timeout wall-clock budget for the entire body (including joined children). On expiry the
 *   runner fails with an [AssertionError] naming the timeout.
 * @param testBody suspend test body; [CoroutineScope.launch] children must be joined or cancelled
 *   before the body returns, or the runner reports a coroutine leak.
 * @return the body's result (use `fun mySpec(): Unit = runSpectreTest { … }` so JUnit sees `void`).
 */
public fun <T> runSpectreTest(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = DefaultSpectreTestTimeout,
    testBody: suspend CoroutineScope.() -> T,
): T =
    runBlocking(context) {
        // Free-standing job: not a child of runBlocking's job. That way a non-cooperative child
        // cannot hang runBlocking after the cleanup join budget expires (structured concurrency
        // would otherwise wait for a parented child forever).
        val testJob = Job()
        val testScope = CoroutineScope(coroutineContext + testJob + CoroutineName("runSpectreTest"))
        try {
            // Holder distinguishes "body returned null" from "outer timeout" without swallowing
            // nested TimeoutCancellationException from waitForNode / withTimeout inside the body
            // (withTimeoutOrNull only maps *its own* timeout to null; nested ones rethrow).
            val holder = arrayOfNulls<Any?>(1)
            val finished =
                withTimeoutOrNull(timeout) {
                    holder[0] = testScope.testBody()
                    true
                }
            if (finished == null) {
                throw AssertionError("runSpectreTest timed out after $timeout")
            }

            val unfinished = testJob.children.filter { it.isActive }.toList()
            if (unfinished.isNotEmpty()) {
                val detail = unfinished.joinToString(separator = "; ") { child -> child.toString() }
                throw AssertionError(
                    "runSpectreTest detected ${unfinished.size} unfinished coroutine(s) after " +
                        "the test body returned (coroutine leak). Join or cancel launched work " +
                        "before returning. Active: $detail"
                )
            }

            @Suppress("UNCHECKED_CAST")
            holder[0] as T
        } finally {
            testJob.cancel()
            // Bound cleanup so a stuck non-cooperative child cannot hang the runner; because
            // testJob is free-standing, giving up here lets runBlocking complete.
            withTimeoutOrNull(CLEANUP_JOIN_TIMEOUT) { testJob.join() }
        }
    }

private val CLEANUP_JOIN_TIMEOUT: Duration = 5.seconds

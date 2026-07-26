package dev.sebastiano.spectre.testing

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * @param childDispatcher dispatcher for [CoroutineScope.launch] / [async] children started from the
 *   body. Defaults to [Dispatchers.Default] so a blocking child cannot monopolize runBlocking's
 *   single-thread event loop and defeat the outer timeout (InjectDispatcher-friendly seam).
 * @param testBody suspend test body; [CoroutineScope.launch] children must be joined or cancelled
 *   before the body returns, or the runner reports a coroutine leak.
 * @return the body's result (use `fun mySpec(): Unit = runSpectreTest { … }` so JUnit sees `void`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun <T> runSpectreTest(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = DefaultSpectreTestTimeout,
    childDispatcher: CoroutineDispatcher = Dispatchers.Default,
    testBody: suspend CoroutineScope.() -> T,
): T =
    runBlocking(context) {
        // Free-standing supervisor: not a child of runBlocking's job (so non-cooperative children
        // cannot hang runBlocking after the cleanup budget). Child failures are collected via
        // CoroutineExceptionHandler / Deferred completion and rethrown after the body returns.
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> uncaught.add(throwable) }
        val testJob = SupervisorJob()
        val testScope =
            CoroutineScope(
                childDispatcher +
                    context.minusKey(Job) +
                    testJob +
                    exceptionHandler +
                    CoroutineName("runSpectreTest")
            )
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

            // Surface failures from completed Deferred children that were never awaited.
            for (child in testJob.children.toList()) {
                if (child is Deferred<*> && child.isCompleted) {
                    val failure = child.getCompletionExceptionOrNull()
                    if (failure != null) throw failure
                }
            }

            // launch failures under SupervisorJob land in [exceptionHandler]; rethrow the first.
            uncaught.firstOrNull()?.let { throw it }

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

package dev.sebastiano.spectre.testing

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
 * @param context additional coroutine context elements for the body/children scope. A dispatcher in
 *   [context] is stripped from the outer [runBlocking] (so the caller thread is never blocked on a
 *   single-thread dispatcher such as Swing) and is also overridden by [childDispatcher] for
 *   body/child work. Do not install a virtual-time test scheduler here if you need real delays.
 * @param timeout wall-clock budget for the entire body (including joined children). On expiry the
 *   runner fails with an [AssertionError] naming the timeout.
 * @param childDispatcher dispatcher for the test body coroutine and for [CoroutineScope.launch] /
 *   [async] children started from the body. Defaults to [Dispatchers.Default] so work is off
 *   runBlocking's single-thread event loop (InjectDispatcher-friendly seam). Always wins over any
 *   dispatcher supplied in [context] for that work.
 * @param testBody suspend test body; [CoroutineScope.launch] children must be joined or
 *   `cancelAndJoin`'d before the body returns, or the runner reports a coroutine leak (`cancel()`
 *   alone is not enough while NonCancellable cleanup is still running).
 * @return the body's result (use `fun mySpec(): Unit = runSpectreTest { … }` so JUnit sees `void`).
 */
public fun <T> runSpectreTest(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = DefaultSpectreTestTimeout,
    childDispatcher: CoroutineDispatcher = Dispatchers.Default,
    testBody: suspend CoroutineScope.() -> T,
): T =
    // Strip any caller dispatcher from runBlocking so we never block a single-thread dispatcher
    // (e.g. Dispatchers.Swing / EDT) that body completion and timeout need to resume on.
    runBlocking(context.minusKey(ContinuationInterceptor)) {
        // Free-standing Job (not parented under runBlocking): non-cooperative children cannot hang
        // runBlocking after the cleanup budget. Child failures cancel this job (and thus the body
        // async below); invokeOnCompletion retains the cause for rethrow after await.
        val failures = CopyOnWriteArrayList<Throwable>()
        val testJob = Job()
        testJob.invokeOnCompletion { cause -> if (cause != null) failures.add(cause) }
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> failures.add(throwable) }
        // childDispatcher is applied last so it always overrides a dispatcher in [context].
        val testScope =
            CoroutineScope(
                context.minusKey(Job) +
                    childDispatcher +
                    testJob +
                    exceptionHandler +
                    CoroutineName("runSpectreTest")
            )
        try {
            // Holder distinguishes "body returned null" from "outer timeout".
            val holder = arrayOfNulls<Any?>(1)
            // Body runs as a child of [testJob] so a failing sibling cancels/wakes it, but the
            // receiver for launch/async is [testScope] so those children are siblings of the body
            // (not nested under it). That way the body can return while they are still running
            // (leak detection) instead of structured-concurrency waiting for them.
            val bodyDeferred = testScope.async {
                // Use testScope as receiver, not this async scope.
                testScope.testBody()
            }
            val finished =
                withTimeoutOrNull(timeout) {
                    try {
                        holder[0] = bodyDeferred.await()
                        true
                    } catch (cancelled: CancellationException) {
                        // Prefer the original child failure over a bare cancellation from the
                        // structured Job when a sibling failed.
                        failures.firstOrNull()?.let { throw it }
                        throw cancelled
                    }
                }
            if (finished == null) {
                // Prefer a recorded child failure if the body was cancelled mid-timeout wait.
                failures.firstOrNull()?.let { throw it }
                throw AssertionError("runSpectreTest timed out after $timeout")
            }

            // Yield so concurrent child completions (and their parent invokeOnCompletion
            // callbacks) can land in [failures] before we declare success.
            yield()

            // Treat cancelling-but-not-completed children as unfinished (NonCancellable cleanup).
            // The bodyDeferred is already completed successfully and excluded by isCompleted.
            val unfinished = testJob.children.filter { !it.isCompleted }.toList()
            if (unfinished.isNotEmpty()) {
                val detail = unfinished.joinToString(separator = "; ") { child -> child.toString() }
                throw AssertionError(
                    "runSpectreTest detected ${unfinished.size} unfinished coroutine(s) after " +
                        "the test body returned (coroutine leak). Join or cancelAndJoin launched " +
                        "work before returning (cancel() alone is not enough during " +
                        "NonCancellable cleanup). Unfinished: $detail"
                )
            }

            // Recheck after the child scan: a child may have completed exceptionally while we
            // walked job.children, recording into [failures] only after isCompleted flipped.
            yield()
            failures.firstOrNull()?.let { throw it }

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

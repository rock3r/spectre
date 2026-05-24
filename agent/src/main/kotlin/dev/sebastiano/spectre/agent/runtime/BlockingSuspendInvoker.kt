package dev.sebastiano.spectre.agent.runtime

import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Drives a Kotlin `suspend` function reflectively by appending a synchronous [LatchingContinuation]
 * as the last argument and blocking on the latch until completion.
 *
 * Used by [ReflectiveAutomatorHandler] to call the suspend members of `ComposeAutomator` (`click`,
 * `typeText`, …) without pulling kotlinx-coroutines into the agent's runtime dependency tree. The
 * only stdlib types involved are [kotlin.coroutines.Continuation] /
 * [kotlin.coroutines.EmptyCoroutineContext] / [COROUTINE_SUSPENDED], all of which live in
 * kotlin-stdlib alongside the target's own suspend-function bytecode.
 *
 * Behaviour:
 * - Suspend function completes synchronously → `Method.invoke` returns the value directly, we
 *   return it.
 * - Suspend function suspends → invocation returns [COROUTINE_SUSPENDED] and the latch is released
 *   by a later `resumeWith` call from wherever the coroutine completes.
 *
 * A timeout guards against hangs (a misbehaving Compose tree, a robot driver stuck on a permission
 * dialog, …). The default [timeoutMs] of 30 s is generous for any single UI op; if v1.1 adds
 * genuinely long-running ops like `waitForVisualIdle` they should be modelled as their own
 * streaming wire op rather than relying on this synchronous bridge.
 */
internal class BlockingSuspendInvoker(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    /**
     * Invokes [method] on [target] with [args] plus an appended [LatchingContinuation]. Returns the
     * suspend function's eventual result, or throws the exception that resumed the continuation
     * with failure.
     */
    fun invoke(method: Method, target: Any, vararg args: Any?): Any? {
        val cont = LatchingContinuation()
        @Suppress("SpreadOperator") val argsWithCont: Array<Any?> = arrayOf(*args, cont)
        val raw = method.invoke(target, *argsWithCont)
        return if (raw === COROUTINE_SUSPENDED) cont.await(timeoutMs) else raw
    }

    private class LatchingContinuation : Continuation<Any?> {
        private val latch = CountDownLatch(1)
        @Volatile private var outcome: Result<Any?>? = null

        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resumeWith(result: Result<Any?>) {
            outcome = result
            latch.countDown()
        }

        fun await(timeoutMs: Long): Any? {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw TimeoutException("Suspend call did not complete within ${timeoutMs} ms")
            }
            return outcome?.getOrThrow()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000
    }
}

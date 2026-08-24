package dev.sebastiano.spectre.agent.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockingSuspendInvokerTest {

    @Test
    fun `interrupted caller remains attached until the continuation completes`() {
        val target = InterruptibleSuspendTarget()
        val method =
            InterruptibleSuspendTarget::class
                .java
                .getMethod("suspendUntilReleased", Continuation::class.java)
        val result = AtomicReference<Any?>()
        val invocationFinished = CountDownLatch(1)
        val thread =
            Thread {
                    try {
                        result.set(BlockingSuspendInvoker().invoke(method, target))
                    } finally {
                        invocationFinished.countDown()
                    }
                }
                .apply { start() }
        val continuation = target.continuation.get(3, TimeUnit.SECONDS)

        thread.interrupt()

        assertFalse(
            invocationFinished.await(200, TimeUnit.MILLISECONDS),
            "interruption must not orphan the still-running suspend continuation",
        )
        continuation.resumeWith(Result.success("finished"))
        assertTrue(invocationFinished.await(3, TimeUnit.SECONDS))
        assertEquals("finished", result.get())
        assertTrue(thread.isInterrupted)
    }
}

internal class InterruptibleSuspendTarget {
    val continuation = CompletableFuture<Continuation<Any?>>()

    @Suppress("unused")
    fun suspendUntilReleased(continuation: Continuation<Any?>): Any? {
        this.continuation.complete(continuation)
        return COROUTINE_SUSPENDED
    }
}

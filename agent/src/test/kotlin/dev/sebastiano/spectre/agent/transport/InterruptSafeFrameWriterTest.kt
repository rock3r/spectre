package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Codex P2: `writeFrame` must not enqueue after the worker has exited, or `awaitUninterruptibly`
 * hangs forever.
 */
class InterruptSafeFrameWriterTest {

    @Test
    fun `writeFrame after close fails fast`() {
        val writer = InterruptSafeFrameWriter("test-writer-closed") {}
        writer.close()
        val started = System.nanoTime()
        val ex = assertFailsWith<IOException> { writer.writeFrame(byteArrayOf(1)) }
        assertTrue(ex.message.orEmpty().contains("shut down"), "message=${ex.message}")
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(waitedMs < 500, "writeFrame after close hung for ${waitedMs}ms")
    }

    @Test
    fun `close racing writeFrame does not hang awaitUninterruptibly`() {
        repeat(40) { iteration ->
            val enteredWrite = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val writer =
                InterruptSafeFrameWriter("test-writer-race-$iteration") {
                    enteredWrite.countDown()
                    try {
                        releaseWrite.await(3, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            val inFlight = Thread {
                runCatching { writer.writeFrame(byteArrayOf(1)) }
            }
                .apply {
                    isDaemon = true
                    start()
                }
            assertTrue(
                enteredWrite.await(3, TimeUnit.SECONDS),
                "iteration $iteration: worker never entered performWrite",
            )

            val racerCount = 8
            val startRace = CyclicBarrier(racerCount + 1)
            val finished = AtomicInteger(0)
            val racers =
                List(racerCount) { idx ->
                    Thread {
                        startRace.await(3, TimeUnit.SECONDS)
                        runCatching { writer.writeFrame(byteArrayOf((idx + 2).toByte())) }
                        finished.incrementAndGet()
                    }
                        .apply {
                            isDaemon = true
                            name = "writer-racer-$iteration-$idx"
                            start()
                        }
                }
            startRace.await(3, TimeUnit.SECONDS)
            val closer = Thread {
                writer.close()
            }
                .apply {
                    isDaemon = true
                    start()
                }
            // Let close() poison/exit the worker while racers are in writeFrame.
            Thread.sleep(5)
            releaseWrite.countDown()

            inFlight.join(2_000)
            closer.join(2_000)
            racers.forEach { it.join(2_000) }
            assertFalse(inFlight.isAlive, "iteration $iteration: in-flight write hung")
            assertFalse(closer.isAlive, "iteration $iteration: close hung")
            assertTrue(
                racers.none { it.isAlive },
                "iteration $iteration: racer hung; finished=${finished.get()}",
            )
            val started = System.nanoTime()
            assertFailsWith<IOException> { writer.writeFrame(byteArrayOf(99)) }
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(
                waitedMs < 500,
                "iteration $iteration: post-close write hung for ${waitedMs}ms",
            )
        }
    }
}

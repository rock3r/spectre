package dev.sebastiano.spectre.core

import java.awt.Panel
import java.awt.Toolkit
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Exercises the shipped Toolkit AWT listener, not a reimplementation. Distinguishes **dispatched in
 * this JVM** from **never dispatched** independently of `clickCount` / `typeText` — the
 * diagnostic #470 needs for an unfocused Xvfb click miss.
 *
 * Bypasses [inputDeliveryVerifiable] via [AwtInputDeliveryWitness.attachListener] so the oracle can
 * be proven on hosts where the production gate declines (macOS).
 */
class AwtInputDeliveryWitnessTest {

    @Test
    fun `shipped AWT listener reports dispatched when a matching press is posted`() {
        val observation = AwtInputDeliveryWitness.attachListener(DispatchedInput.Press)
        assumeTrue(observation != null, "toolkit refused the AWT listener")
        checkNotNull(observation).use { watch ->
            val press =
                MouseEvent(
                    Panel(),
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    1,
                    1,
                    1,
                    false,
                )
            Toolkit.getDefaultToolkit().systemEventQueue.postEvent(press)
            val dispatched = watch.awaitDelivery(INPUT_WITNESS_WAIT_MS)
            println("AWT click verdict: dispatched in this JVM (posted MOUSE_PRESSED)=$dispatched")
            assertTrue(dispatched, "posted MOUSE_PRESSED must count as dispatched in this JVM")
        }
    }

    @Test
    fun `shipped AWT listener reports never dispatched when the queue drains with no press`() {
        val observation = AwtInputDeliveryWitness.attachListener(DispatchedInput.Press)
        assumeTrue(observation != null, "toolkit refused the AWT listener")
        checkNotNull(observation).use { watch ->
            val seen = watch.awaitDelivery(NO_EVENT_WAIT_MS)
            val drained = watch.awaitQueueDrain(DRAIN_WAIT_MS)
            val late = watch.awaitDelivery(0)
            val neverDispatched = !seen && drained && !late
            println(
                "AWT click verdict: never dispatched in this JVM=$neverDispatched " +
                    "(seen=$seen drained=$drained late=$late; no press posted)"
            )
            assertFalse(seen, "no press was posted, so none should have been dispatched")
            assertTrue(drained, "EDT must drain for a never-dispatched verdict to be trustworthy")
            assertFalse(late)
            assertTrue(neverDispatched)
        }
    }

    private companion object {
        const val INPUT_WITNESS_WAIT_MS = 2_000L
        const val NO_EVENT_WAIT_MS = 50L
        const val DRAIN_WAIT_MS = 500L
    }
}

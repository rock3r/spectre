package dev.sebastiano.spectre.recording

import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** Waits until a window manager has finished mapping the test windows at their exact fixtures. */
internal fun stabilizeMappedAwtFrames(vararg frames: Pair<Window, Rectangle>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    var consecutiveMatches = 0
    var actualFrames = emptyArray<Pair<Rectangle, Rectangle>>()

    EventQueue.invokeAndWait {
        for ((window, expected) in frames) {
            if (window.bounds != expected) window.bounds = expected
        }
    }

    do {
        Thread.sleep(25)
        EventQueue.invokeAndWait {
            actualFrames =
                frames.map { (window, expected) -> expected to window.bounds }.toTypedArray()
        }
        if (actualFrames.all { (expected, actual) -> actual == expected }) {
            consecutiveMatches++
        } else {
            consecutiveMatches = 0
        }
    } while (consecutiveMatches < 10 && System.nanoTime() < deadline)

    assertStableAwtFrames(consecutiveMatches, actualFrames)
}

internal fun assertStableAwtFrames(
    consecutiveMatches: Int,
    frames: Array<Pair<Rectangle, Rectangle>>,
) {
    assertEquals(10, consecutiveMatches, "AWT fixture did not remain stable for 10 samples")
    for ((expected, actual) in frames) {
        assertEquals(expected, actual, "AWT fixture did not reach its requested outer frame")
    }
}

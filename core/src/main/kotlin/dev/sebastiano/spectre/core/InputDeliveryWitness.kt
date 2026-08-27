package dev.sebastiano.spectre.core

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** The AWT event a dispatched input verb should produce in this JVM if the OS delivered it. */
internal enum class DispatchedInput(internal val awtEventId: Int) {
    /** Click, double-click, long-click and swipe all begin with a button press. */
    Press(MouseEvent.MOUSE_PRESSED),

    /** `scrollWheel` never presses a button, so a press would never arrive for it. */
    Wheel(MouseEvent.MOUSE_WHEEL),
}

/**
 * Answers one question from inside the JVM that owns the target UI: **did injected input actually
 * arrive?**
 *
 * `java.awt.Robot` drives `SendInput` on Windows, which the OS discards when the calling process is
 * not on the session's active input desktop — a locked workstation, or a non-interactive desktop.
 * The JDK does not surface that refusal, so the verb returns normally having done nothing and the
 * failure only shows up downstream as "the semantics tree went stale" (#460).
 *
 * An AWT event listener is the best available oracle: it sits below Compose, below hit-testing and
 * below focus, so it separates "no event ever arrived" from every downstream reason input might not
 * change anything. Be precise about what it proves, though — it fires during *dispatch on the EDT*,
 * so it attests that a matching event was dispatched in this JVM, not that this particular dispatch
 * was the cause. Two consequences follow, and both are handled deliberately: an event that is
 * queued but not dispatched (a busy or blocked EDT) must not be reported as a discarded one, which
 * is what [Observation.awaitQueueDrain] exists for; and an unrelated matching event can satisfy the
 * wait, which fails open to the old silent behaviour rather than inventing a failure.
 *
 * Observation is best-effort by contract. [observe] returns `null` when it cannot watch — the
 * caller then dispatches unverified rather than failing, because being unable to check is not
 * evidence of a problem.
 */
internal interface InputDeliveryWitness {

    /** Begins watching for [input], or returns `null` when watching is not possible. */
    fun observe(input: DispatchedInput): Observation?

    /** An active watch. Must be closed; closing detaches the listener. */
    interface Observation : AutoCloseable {
        /** Waits up to [timeoutMs] for the watched event to be dispatched in this JVM. */
        fun awaitDelivery(timeoutMs: Long): Boolean

        /**
         * Waits up to [timeoutMs] for this JVM's event queue to drain past the point the watched
         * event would have been dispatched, returning false if it never does.
         *
         * This is what makes a non-delivery verdict trustworthy. An AWT listener only fires during
         * dispatch, so "no event seen" also describes a JVM whose EDT is busy or blocked — for
         * instance a caller doing `runBlocking { click(node) }` on the EDT itself, which parks the
         * very thread that would dispatch the press. Posting a marker behind whatever is already
         * queued distinguishes the two: if the marker runs, the queue drained past our event's
         * slot, so the event genuinely never arrived.
         */
        fun awaitQueueDrain(timeoutMs: Long): Boolean
    }
}

/**
 * Default [InputDeliveryWitness], backed by a scoped `Toolkit` AWT event listener.
 *
 * The listener is attached per dispatch rather than kept alive for the lifetime of the driver:
 * Spectre runs inside someone else's application, and a permanent global hook on every mouse event
 * in the host app is a bigger imposition than the microseconds it costs to add and remove one.
 */
internal object AwtInputDeliveryWitness : InputDeliveryWitness {

    override fun observe(input: DispatchedInput): InputDeliveryWitness.Observation? {
        if (!inputDeliveryVerifiable()) return null
        val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull() ?: return null
        val delivered = CountDownLatch(1)
        val listener = AWTEventListener { event ->
            if (event is MouseEvent && event.id == input.awtEventId) delivered.countDown()
        }
        // Wheel events need their own mask; subscribing to both keeps one code path for all verbs.
        val mask = AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK
        // Headless toolkits and (historically) a restrictive SecurityManager can refuse the hook.
        if (runCatching { toolkit.addAWTEventListener(listener, mask) }.isFailure) return null

        return object : InputDeliveryWitness.Observation {
            override fun awaitDelivery(timeoutMs: Long): Boolean =
                delivered.await(timeoutMs, TimeUnit.MILLISECONDS)

            // invokeLater is FIFO behind already-queued events, so a press that was merely late
            // will have been dispatched by the time this marker runs.
            override fun awaitQueueDrain(timeoutMs: Long): Boolean =
                runCatching {
                        val drained = CountDownLatch(1)
                        EventQueue.invokeLater { drained.countDown() }
                        drained.await(timeoutMs, TimeUnit.MILLISECONDS)
                    }
                    .getOrDefault(false)

            override fun close() {
                runCatching { toolkit.removeAWTEventListener(listener) }
            }
        }
    }
}

/**
 * Whether "no AWT event arrived" is sound evidence that input was discarded, on this platform.
 *
 * Only on Windows. #460 is a Windows problem and Windows is where the behaviour was measured, but
 * the real constraint is that the oracle is **unsound elsewhere**: on macOS, AppKit consumes the
 * click that activates an inactive application without delivering it to the window at all, so a
 * perfectly healthy first click legitimately produces no event. `AgentAttachIntegrationTest`
 * already compensates for exactly that by retrying, and treating the swallowed activation click as
 * a failure would break the retry it depends on. X11 has its own divergences (the wheel is button
 * 4/5 presses, so it emits nothing for a zero-notch scroll).
 *
 * Declining to observe reuses the existing "cannot testify" path, so non-Windows behaviour is
 * exactly what it was before this guard existed.
 */
internal fun inputDeliveryVerifiable(
    osName: String = System.getProperty("os.name").orEmpty()
): Boolean = osName.startsWith("Windows", ignoreCase = true)

/**
 * [RobotDriver.click], plus verification that the OS actually delivered the press to this JVM.
 *
 * Reserved for node-targeted callers ([ComposeAutomator.click]), where the input is aimed at one of
 * this JVM's own Compose nodes and an event that never arrives is unambiguously a failure. The
 * public coordinate overloads stay unverified on purpose: a caller may quite legitimately drive
 * input outside this JVM's windows, for instance to dismiss a popup.
 *
 * These live outside [RobotDriver] as extensions rather than members because the driver is already
 * at the project's per-class function ceiling, and because keeping the whole delivery-verification
 * feature in one file makes it easier to reason about than five methods scattered through the input
 * surface.
 *
 * Throws [IllegalStateException] when the input was discarded — see #460.
 */
internal suspend fun RobotDriver.clickVerified(screenX: Int, screenY: Int) {
    verifyingDelivery("click", screenX, screenY, DispatchedInput.Press) { click(screenX, screenY) }
}

/** [RobotDriver.doubleClick] with the delivery verification described on [clickVerified]. */
internal suspend fun RobotDriver.doubleClickVerified(screenX: Int, screenY: Int) {
    verifyingDelivery("doubleClick", screenX, screenY, DispatchedInput.Press) {
        doubleClick(screenX, screenY)
    }
}

/** [RobotDriver.longClick] with the delivery verification described on [clickVerified]. */
internal suspend fun RobotDriver.longClickVerified(screenX: Int, screenY: Int, holdFor: Duration) {
    verifyingDelivery("longClick", screenX, screenY, DispatchedInput.Press) {
        longClick(screenX, screenY, holdFor)
    }
}

/**
 * [RobotDriver.swipe] with the delivery verification described on [clickVerified].
 *
 * A swipe opens with a button press at the start point, so the press is the event to watch. The
 * intermediate moves are deliberately not verified: a drag that starts is a drag that was
 * delivered, and per-move checking would be noise.
 */
internal suspend fun RobotDriver.swipeVerified(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    steps: Int,
    duration: Duration,
) {
    verifyingDelivery("swipe", startX, startY, DispatchedInput.Press) {
        swipe(startX, startY, endX, endY, steps, duration)
    }
}

/**
 * [RobotDriver.scrollWheel] with the delivery verification described on [clickVerified].
 *
 * Watches for a wheel event rather than a press: scrolling never presses a button, so a press
 * oracle would report every scroll as undelivered.
 */
internal suspend fun RobotDriver.scrollWheelVerified(screenX: Int, screenY: Int, wheelClicks: Int) {
    if (wheelClicks == 0) {
        // A zero-notch scroll is a legitimate no-op that emits no wheel event on any backend -- on
        // X11 the wheel is button 4/5 presses, so zero notches dispatches nothing at all. Waiting
        // for an event here would invent a failure out of a request that did exactly what it said.
        scrollWheel(screenX, screenY, wheelClicks)
        return
    }
    verifyingDelivery("scrollWheel", screenX, screenY, DispatchedInput.Wheel) {
        scrollWheel(screenX, screenY, wheelClicks)
    }
}

/**
 * Runs [dispatch] while watching for the event it should produce, and fails loudly if none arrives.
 *
 * Verification is **skipped rather than failed** whenever it could not mean anything: backends that
 * do not drive real OS input (synthetic, headless, test fakes) never produce an AWT event for a
 * dispatch, and a witness that cannot attach cannot testify either way. Being unable to check is
 * not evidence of a problem.
 */
private suspend fun RobotDriver.verifyingDelivery(
    operation: String,
    screenX: Int,
    screenY: Int,
    input: DispatchedInput,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    dispatch: suspend () -> Unit,
) {
    if (!inputCapabilities.realOsInput) {
        dispatch()
        return
    }
    val observation = deliveryWitness.observe(input)
    if (observation == null) {
        dispatch()
        return
    }
    observation.use {
        dispatch()
        // runInterruptible, not withContext: docs/guide/interactions.md promises a cancelled
        // coroutine cancels rather than parking a worker, and a bare latch await would park one
        // for the full budget.
        if (runInterruptible(dispatcher) { it.awaitDelivery(INPUT_DELIVERY_TIMEOUT_MS) }) return
        // Nothing dispatched. Before accusing the OS, establish that this JVM was actually
        // dispatching at all — see Observation.awaitQueueDrain.
        if (!runInterruptible(dispatcher) { it.awaitQueueDrain(EDT_DRAIN_TIMEOUT_MS) }) return
        // The queue drained past our event's slot, so a merely-late event would already have
        // landed. Re-check before failing.
        if (runInterruptible(dispatcher) { it.awaitDelivery(0) }) return
        error(undeliveredInputMessage(operation, screenX, screenY))
    }
}

/**
 * Generous on purpose. The success path returns the instant the event lands (single-digit
 * milliseconds in practice), so this budget is only ever spent when the input really was discarded;
 * a tight one would turn a loaded machine into a spurious "input was discarded".
 */
private const val INPUT_DELIVERY_TIMEOUT_MS = 2_000L

/**
 * Short: by the time this is consulted the input budget has already elapsed, and a queue that is
 * draining at all will run a freshly posted marker almost immediately.
 */
private const val EDT_DRAIN_TIMEOUT_MS = 500L

/**
 * Names the platform constraint rather than the symptom. The silent-no-op shape of #460 is exactly
 * what made it hard to diagnose, so the message has to say what was not delivered and why the OS
 * does that. It distinguishes what Spectre has actually measured from what it infers, because
 * overstating the cause is how the original investigation lost time.
 */
private fun undeliveredInputMessage(operation: String, screenX: Int, screenY: Int): String =
    "Spectre dispatched $operation at ($screenX, $screenY) but no matching mouse event reached " +
        "this JVM within ${INPUT_DELIVERY_TIMEOUT_MS}ms, and this JVM was dispatching events " +
        "normally, so the input was not delivered here. Real OS input needs the target process " +
        "to be on the session's active input desktop, and needs the event to land on one of its " +
        "own windows. Measured causes: the workstation is locked, or the process is on a desktop " +
        "that is not the input desktop. The same is expected for a non-interactive session " +
        "(Windows session 0, an SSH shell, a scheduled task run while logged out). Input routed " +
        "elsewhere looks identical from here — another window covering the target, or a scroll " +
        "sent to the focused window instead of the one under the pointer. " +
        "See https://github.com/rock3r/spectre/issues/460"

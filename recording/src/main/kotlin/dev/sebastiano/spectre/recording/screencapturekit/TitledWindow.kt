package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal view onto a window object that exposes a mutable title. Lives as an interface so
 * [TitleDiscriminator] can be tested without an AWT/Compose window — the production binding for
 * `androidx.compose.ui.awt.ComposeWindow` (which extends `java.awt.Frame` and inherits `getTitle` /
 * `setTitle`) is the [asTitledWindow] adapter below.
 */
public interface TitledWindow {
    public var title: String?
}

/**
 * Adapt an AWT [Frame] (or any subclass — `ComposeWindow`, `JFrame`, `JDialog` parent frame) to the
 * [TitledWindow] surface [ScreenCaptureKitRecorder] expects. The adapter delegates `getTitle` /
 * `setTitle` and marshals all calls onto the AWT/Swing EDT via [EventQueue.invokeAndWait] so
 * callers can drive the recorder lifecycle from any thread (worker threads, coroutines, test
 * runners) without violating AWT's thread-confinement contract. Calls that originate on the EDT
 * skip the marshalling and run inline.
 *
 * Null titles set through this adapter are written as the empty string, mirroring AWT's own
 * behaviour for `Frame.setTitle(null)`.
 */
public fun Frame.asTitledWindow(): TitledWindow =
    object : TitledWindow {
        override var title: String?
            get() = onEdt { this@asTitledWindow.title }
            set(value) {
                onEdt { this@asTitledWindow.title = value ?: "" }
            }
    }

/**
 * Run [block] on the AWT EDT and return its result. If already on the EDT, runs inline. Any
 * exception thrown by [block] propagates to the caller with its original type (the
 * `InvocationTargetException` wrapper that `invokeAndWait` would otherwise add is unwrapped).
 */
private fun <T> onEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val resultRef = AtomicReference<Any?>()
    val errorRef = AtomicReference<Throwable?>()
    EventQueue.invokeAndWait {
        @Suppress("TooGenericExceptionCaught")
        try {
            resultRef.set(block())
        } catch (t: Throwable) {
            errorRef.set(t)
        }
    }
    errorRef.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return resultRef.get() as T
}

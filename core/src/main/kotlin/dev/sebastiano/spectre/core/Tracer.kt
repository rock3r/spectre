package dev.sebastiano.spectre.core

import java.nio.file.Path

/**
 * Records performance/profiling traces around an automator scenario.
 *
 * The v1 contract assumes the **app under test owns its own instrumentation** (typically via
 * `androidx.tracing` 2.0, which automatically tags Compose's composition / layout / measure / draw
 * phases). The automator's job is to bracket the scenario: start a recording, drive the UI, stop
 * the recording, and flush it to disk so the developer can open it in Perfetto.
 *
 * `JfrTracer` is the v1 default — it uses Java Flight Recorder, which is built into every modern
 * JDK and produces `.jfr` files that Perfetto can ingest natively. Callers that want a different
 * recorder (a Perfetto SDK binding, an Android-style atrace shim, an in-memory event collector for
 * tests) can plug their own `Tracer` into [ComposeAutomator.withTracing].
 */
interface Tracer {

    /** Begin a recording. Implementations may throw if a recording is already in progress. */
    fun start()

    /**
     * Stop the recording and write the captured trace to [output]. The file extension callers use
     * should match the format the implementation produces (`.jfr` for [JfrTracer]).
     */
    fun stop(output: Path)
}

/**
 * Bracket [block] with a [Tracer] recording. Public callers go through
 * [ComposeAutomator.withTracing]; this top-level helper exists so the bracket contract can be
 * exercised in unit tests without instantiating an automator (and therefore without requiring an
 * AWT-capable test environment).
 *
 * Guarantees:
 * - [Tracer.start] is invoked before [block] runs.
 * - [Tracer.stop] is invoked after [block] completes — including when [block] throws, so the
 *   captured trace is always flushed.
 * - The block's return value is propagated.
 * - If both [block] and [Tracer.stop] throw, the block's exception is the one rethrown and the stop
 *   failure is attached as a suppressed exception so neither failure is silently lost.
 */
internal suspend fun <T> withTracingInternal(
    output: Path,
    tracer: Tracer,
    block: suspend () -> T,
): T {
    tracer.start()
    var blockError: Throwable? = null
    var result: T? = null
    // The block runs untrusted scenario code; we want every failure mode propagated to the
    // caller after the trace has been flushed, hence the broad catch.
    @Suppress("TooGenericExceptionCaught")
    try {
        result = block()
    } catch (t: Throwable) {
        blockError = t
    }
    @Suppress("TooGenericExceptionCaught")
    try {
        tracer.stop(output)
    } catch (stopError: Throwable) {
        if (blockError != null) {
            blockError.addSuppressed(stopError)
        } else {
            throw stopError
        }
    }
    if (blockError != null) throw blockError
    @Suppress("UNCHECKED_CAST")
    return result as T
}

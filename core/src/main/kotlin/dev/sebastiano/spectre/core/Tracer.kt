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

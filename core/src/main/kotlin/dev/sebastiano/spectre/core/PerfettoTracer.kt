package dev.sebastiano.spectre.core

import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import java.nio.file.Files
import java.nio.file.Path

/**
 * Default [Tracer] backed by `androidx.tracing` 2.0 (`tracing-wire-desktop`).
 *
 * Writes Perfetto-compatible trace files into the directory passed to [start]. The directory is
 * created if it does not already exist; each tracing session emits one or more files keyed off
 * [sequenceId] (see Perfetto's documentation on multi-sequence trace ingestion).
 *
 * This is a low-overhead, in-process tracer that produces the same Perfetto trace format the
 * Android tooling uses, so the resulting traces can be opened directly in
 * [ui.perfetto.dev](https://ui.perfetto.dev) or imported into Android Studio's profiler.
 *
 * The app under test typically also installs its own `androidx.tracing` instrumentation (around
 * recompositions, RPC calls, state updates, etc.) — this tracer just provides the recording
 * lifecycle that brackets the scenario.
 */
public class PerfettoTracer(private val sequenceId: Int = DEFAULT_SEQUENCE_ID) : Tracer {

    private var driver: TraceDriver? = null

    override fun start(output: Path) {
        check(driver == null) { "PerfettoTracer is already recording" }
        Files.createDirectories(output)
        val sink = TraceSink(directory = output.toFile(), sequenceId = sequenceId)
        driver = TraceDriver(sink = sink)
    }

    override fun stop() {
        val active = checkNotNull(driver) { "PerfettoTracer.stop called before start" }
        // Try/finally so a failing close still resets the driver reference and lets the tracer
        // be re-used cleanly afterwards.
        try {
            active.close()
        } finally {
            driver = null
        }
    }

    public companion object {

        public const val DEFAULT_SEQUENCE_ID: Int = 1
    }
}

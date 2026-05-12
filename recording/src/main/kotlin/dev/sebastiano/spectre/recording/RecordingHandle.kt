package dev.sebastiano.spectre.recording

import java.nio.file.Path

/**
 * Handle to an in-progress recording.
 *
 * Call [stop] (or use the recorder's [AutoCloseable] form) to flush the pending frames and finalise
 * the file at [output]. Stopping a handle that has already been stopped is a no-op.
 */
public interface RecordingHandle : AutoCloseable {

    /** The destination file the recorder is writing to. */
    public val output: Path

    /** True once [stop] has been called and the recorder has flushed/exited. */
    public val isStopped: Boolean

    /** Flush, terminate the underlying recording process/pipeline, and finalise [output]. */
    public fun stop()

    override fun close() {
        stop()
    }
}

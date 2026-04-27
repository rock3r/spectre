package dev.sebastiano.spectre.core

import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Configuration
import jdk.jfr.Recording

/**
 * Default [Tracer] backed by Java Flight Recorder.
 *
 * JFR is built into every supported JDK, so this implementation requires no additional
 * dependencies. The output file is a standard `.jfr` recording, which Perfetto can ingest directly
 * (see https://perfetto.dev/docs/quickstart/heap-profiling for the JFR import flow).
 *
 * The default JFR configuration is `default.jfc`, which is suitable for most scenario-level
 * profiling and matches what Mission Control / VisualVM use out of the box. Pass a different
 * [configurationName] (e.g. `"profile"`) for a more detailed but heavier-weight profile.
 */
class JfrTracer(private val configurationName: String = DEFAULT_CONFIGURATION) : Tracer {

    private var recording: Recording? = null

    override fun start() {
        check(recording == null) { "JfrTracer is already recording" }
        val configuration = Configuration.getConfiguration(configurationName)
        recording = Recording(configuration).also { it.start() }
    }

    override fun stop(output: Path) {
        val active = checkNotNull(recording) { "JfrTracer.stop called before start" }
        Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())
        active.stop()
        active.dump(output)
        active.close()
        recording = null
    }

    companion object {

        const val DEFAULT_CONFIGURATION: String = "default"
    }
}

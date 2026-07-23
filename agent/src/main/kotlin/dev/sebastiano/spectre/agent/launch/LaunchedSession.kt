package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A process launched by [LaunchAndAttach] with a live [automator] session.
 *
 * Closing detaches the agent and tears down the app process tree (never the Gradle daemon on
 * Gradle-ish launches). Capture files under [stdoutPath] / [stderrPath] are left in place for
 * failure diagnostics.
 *
 * @property launchedPid OS pid of the process started from [LaunchSpec.command] (gradlew client on
 *   Gradle-ish launches).
 * @property attachedPid OS pid of the JVM Spectre attached to (equals [launchedPid] for direct
 *   launches; the discovered app JVM for Gradle-ish launches).
 * @property gradleish true when the launch was treated as Gradle-driven.
 * @property warnings human-readable warnings emitted for this launch (e.g. Gradle caveats).
 */
@ExperimentalSpectreAgentApi
public class LaunchedSession
internal constructor(
    public val launchedPid: Long,
    public val attachedPid: Long,
    public val automator: AttachedAutomator,
    public val stdoutPath: Path,
    public val stderrPath: Path,
    public val gradleish: Boolean,
    public val warnings: List<String>,
    private val onClose: () -> Unit,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { automator.close() }
        onClose()
    }
}

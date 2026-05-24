package dev.sebastiano.spectre.agent.runtime

/**
 * Base class for all errors raised by the Spectre agent runtime during bootstrap or operation.
 *
 * Distinguishing from generic exceptions makes it easy for callers on the attaching side to tell
 * "the target JVM rejected the agent for a known reason" apart from "the JVM crashed or the attach
 * API itself failed".
 */
internal sealed class SpectreAgentBootstrapException(message: String) : RuntimeException(message)

/**
 * Thrown by [AgentBootstrap] when no class with the fully-qualified name
 * `dev.sebastiano.spectre.core.ComposeAutomator` is found among the target JVM's loaded classes.
 *
 * The thin-agent design (UC-1 in the issue #153 workshop plan) requires the target application to
 * already have Spectre on its classpath. This exception is the agent's way of saying "you attached
 * to a JVM that doesn't have Spectre — add the dependency and try again".
 */
internal class SpectreNotOnClasspathException :
    SpectreAgentBootstrapException(
        "No `dev.sebastiano.spectre.core.ComposeAutomator` class found in the target JVM's " +
            "loaded classes. The Spectre agent requires the target application to include " +
            "`dev.sebastiano.spectre:core` on its classpath. The agent JAR itself is supplied " +
            "by the attaching JVM via `VirtualMachine.loadAgent`, not added as a target-side " +
            "dependency. See https://github.com/rock3r/spectre/issues/153 for the thin-agent " +
            "design rationale."
    )

/**
 * Thrown by [AgentBootstrap] when multiple distinct [ClassLoader]s have loaded a
 * `dev.sebastiano.spectre.core.ComposeAutomator` class, and the disambiguation rule (D-14 in the
 * issue #153 workshop plan) can't pick a clear winner.
 *
 * This is preferable to silently choosing one of the candidates — picking the wrong loader would
 * mean the agent's reflective calls would operate on a different `ComposeAutomator` instance than
 * the one used by the app, surfacing as empty window lists or "is" checks that mysteriously fail.
 *
 * @property candidates the [ClassLoader]s that each have a `ComposeAutomator` class. Logged in the
 *   exception message; useful for diagnosing why multiple copies exist.
 */
internal class AmbiguousSpectreClasspathException(val candidates: List<ClassLoader>) :
    SpectreAgentBootstrapException(
        "Multiple `dev.sebastiano.spectre.core.ComposeAutomator` classes were found with no " +
            "clear winner under the IntelliJ PluginClassLoader selection rule. The agent " +
            "cannot decide which copy of Spectre to bootstrap. Candidate ClassLoaders:\n" +
            candidates.joinToString("\n") { "  - $it (${it.javaClass.name})" }
    )

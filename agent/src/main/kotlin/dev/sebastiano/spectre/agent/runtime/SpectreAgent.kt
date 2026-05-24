package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.IpcServer
import java.lang.instrument.Instrumentation
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Spectre's Java agent entry points.
 *
 * Listed in the agent JAR's manifest as both `Premain-Class` and `Agent-Class`:
 * - [premain] is invoked when the JVM is started with
 *   `-javaagent:spectre-agent-runtime-<v>-all.jar=<udsPath>`.
 * - [agentmain] is invoked when the JAR is dynamically loaded via
 *   [com.sun.tools.attach.VirtualMachine.loadAgent].
 *
 * Both go through [bootstrap]:
 * 1. Locate Spectre on the target's classpath via [AgentBootstrap.findSpectreClassLoader].
 * 2. Reflectively construct a `ComposeAutomator.inProcess(...)` instance in that classloader.
 * 3. If `agentArgs` carries a UDS path, start an [IpcServer] there that dispatches
 *    [dev.sebastiano.spectre.agent.transport.AgentRequest]s against the automator via
 *    [ReflectiveAutomatorHandler].
 *
 * **Failure propagation**: any exception thrown by [bootstrap] escapes [premain] / [agentmain]. The
 * JVM's `loadClassAndStartAgent` rethrows it, which causes `VirtualMachine.loadAgent` on the
 * attaching side to fail with `AgentInitializationException` carrying the real cause. This is
 * required so attachers don't silently time out when the agent can't find Spectre, can't bind the
 * UDS, etc.
 *
 * **Idempotency** (plan R-4): a second invocation while the IPC server is already running is a
 * no-op (logs to stderr and returns). The JVM caches loaded agent classes between `loadAgent` calls
 * so this matters in practice.
 *
 * **Detach contract** (plan D-7): [onClientDetach] performs the full Path A cleanup — closes the
 * [IpcServer] (releases ServerSocketChannel + unlinks UDS), removes the shutdown hook, and clears
 * the global state slot. A registered shutdown hook (Path B) is the backstop for crashes.
 */
@ExperimentalSpectreAgentApi
public object SpectreAgent {
    /**
     * Holds the single live agent state per JVM. [AtomicReference] keeps the idempotency CAS
     * race-free even when two `loadAgent` calls fire in close succession.
     */
    private val agentState = AtomicReference<AgentState?>(null)

    /**
     * Static-attach entry point. Called by the JVM at startup when `-javaagent:` is on the cmdline.
     */
    @JvmStatic
    public fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        bootstrap("premain", agentArgs, instrumentation)
    }

    /** Dynamic-attach entry point. Called by the JVM when `VirtualMachine.loadAgent` runs. */
    @JvmStatic
    public fun agentmain(agentArgs: String?, instrumentation: Instrumentation) {
        bootstrap("agentmain", agentArgs, instrumentation)
    }

    /**
     * Runs the full bootstrap pipeline. **Throws** on any failure so the attaching side sees the
     * real cause via `AgentInitializationException`.
     */
    private fun bootstrap(
        entryPoint: String,
        agentArgs: String?,
        instrumentation: Instrumentation,
    ) {
        System.err.println(
            "[spectre-agent] $entryPoint invoked (agentArgs=${agentArgs ?: "<none>"}, " +
                "loadedClasses=${instrumentation.allLoadedClasses.size})"
        )

        if (agentState.get() != null) {
            System.err.println(
                "[spectre-agent] already bootstrapped on this JVM; ignoring re-entry"
            )
            return
        }

        // findSpectreClassLoader throws SpectreNotOnClasspathException or
        // AmbiguousSpectreClasspathException; createAutomatorReflectively throws
        // ReflectiveOperationException. Both propagate to the JVM agent layer which surfaces
        // them at the attaching `VirtualMachine.loadAgent` call site.
        val loader = AgentBootstrap.findSpectreClassLoader(instrumentation)
        System.err.println("[spectre-agent] found Spectre via $loader")

        val automator = createAutomatorReflectively(loader)
        System.err.println("[spectre-agent] ComposeAutomator ready: $automator")

        val udsPath = agentArgs.takeUnless { it.isNullOrBlank() }?.let(Path::of)
        if (udsPath == null) {
            // No UDS path → M-1 diagnostic spike (window count to stderr). Useful when a user
            // just wants to verify Spectre is correctly on a target's classpath.
            val count = invokeWindowsCountReflectively(automator)
            System.err.println(
                "[spectre-agent] no UDS path provided; spike mode reports getWindows().size = $count"
            )
            return
        }

        // IpcServer's constructor throws IOException on bind failure (path too long,
        // permission issue, …). Let it propagate.
        val handler = ReflectiveAutomatorHandler(automator)
        val server = IpcServer(udsPath = udsPath, handler = handler, onDetach = ::onClientDetach)

        // Register the shutdown hook BEFORE publishing AgentState so a crash between here and
        // CAS leaves no orphans. We carry the hook Thread in AgentState so onClientDetach can
        // unregister it.
        val shutdownHook =
            Thread(
                {
                    // Path B — crash safety. close() handles its own idempotency.
                    runCatching { server.close() }
                },
                SHUTDOWN_HOOK_NAME,
            )
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        val newState = AgentState(server = server, udsPath = udsPath, shutdownHook = shutdownHook)
        if (!agentState.compareAndSet(null, newState)) {
            // Idempotency race: someone bootstrapped between our earlier `get()` check and now.
            // Roll back: close our just-created server and remove our hook so the existing
            // state remains the source of truth.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            runCatching { server.close() }
            System.err.println(
                "[spectre-agent] lost idempotency race; rolled back duplicate IPC server"
            )
            return
        }

        System.err.println(
            "[spectre-agent] IPC server listening on $udsPath — ready for client connections"
        )
    }

    /**
     * Called by [IpcServer] when it processes an `AgentRequest.Detach`. Performs the full D-7 Path
     * A cleanup: clears the global slot, closes the server (idempotent), removes the shutdown hook.
     *
     * The server has *already* set its `running` flag to false before invoking this; the `close()`
     * here ensures the ServerSocketChannel native fd is released and the UDS path unlinked. Without
     * it, the channel would leak until JVM exit.
     */
    private fun onClientDetach() {
        val state = agentState.getAndSet(null) ?: return
        runCatching { state.server.close() }
        runCatching { Runtime.getRuntime().removeShutdownHook(state.shutdownHook) }
        System.err.println("[spectre-agent] detached cleanly; resources released")
    }

    private fun createAutomatorReflectively(classLoader: ClassLoader): Any {
        val automatorClass = classLoader.loadClass(COMPOSE_AUTOMATOR_FQN)
        val companion = automatorClass.getField("Companion").get(null)

        val robotDriverClass = classLoader.loadClass(ROBOT_DRIVER_FQN)
        val robotDriver = robotDriverClass.getDeclaredConstructor().newInstance()

        val inProcessMethod =
            companion.javaClass.methods.firstOrNull {
                it.name == "inProcess" && it.parameterCount == 2
            }
                ?: error(
                    "Could not find ComposeAutomator.Companion.inProcess(robotDriver, " +
                        "discoverWindows) on ${companion.javaClass}"
                )
        return inProcessMethod.invoke(companion, robotDriver, true)
    }

    private fun invokeWindowsCountReflectively(automator: Any): Int {
        val getWindowsMethod = automator.javaClass.getMethod("getWindows")
        val windows = getWindowsMethod.invoke(automator) as List<*>
        return windows.size
    }

    private const val ROBOT_DRIVER_FQN = "dev.sebastiano.spectre.core.RobotDriver"
    private const val SHUTDOWN_HOOK_NAME = "spectre-agent-shutdown"

    /** Single live agent state, swapped under [agentState] atomically. */
    private data class AgentState(
        val server: IpcServer,
        val udsPath: Path,
        val shutdownHook: Thread,
    )
}

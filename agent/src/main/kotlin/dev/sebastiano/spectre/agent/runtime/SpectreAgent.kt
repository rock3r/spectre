package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.AttachInputCoordination
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits
import dev.sebastiano.spectre.agent.transport.IpcServer
import java.lang.instrument.Instrumentation
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Spectre's Java agent entry points.
 *
 * Listed in the agent JAR's manifest as both `Premain-Class` and `Agent-Class`:
 * - [premain] is invoked when the JVM is started with
 *   `-javaagent:spectre-agent-runtime-<version>.jar=<udsPath>`.
 * - [agentmain] is invoked when the JAR is dynamically loaded via
 *   [com.sun.tools.attach.VirtualMachine.loadAgent].
 *
 * Both go through [bootstrap]:
 * 1. Locate Spectre on the target's classpath via [AgentBootstrap.findSpectreClassLoader].
 * 2. Reflectively construct a `ComposeAutomator.inProcess(...)` instance in that classloader.
 * 3. If `agentArgs` carries a UDS path (see [AgentBootstrapArgs] for the accepted forms), start an
 *    [IpcServer] there that dispatches [dev.sebastiano.spectre.agent.transport.AgentRequest]s
 *    against the automator via [ReflectiveAutomatorHandler].
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
 * [IpcServer] (releases ServerSocketChannel + unlinks UDS), closes target-side input coordination,
 * removes the shutdown hook, and clears the global state slot. A registered shutdown hook (Path B)
 * is the backstop for crashes.
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

        // findSpectre throws SpectreNotOnClasspathException /
        // AmbiguousSpectreClasspathException / ComposeNotOnClasspathException;
        // createAutomatorReflectively throws ReflectiveOperationException. Both propagate to the
        // JVM agent layer which surfaces them at the attaching `VirtualMachine.loadAgent` call
        // site.
        val bootstrap = AgentBootstrap.findSpectre(instrumentation)
        // When AgentState is published successfully, detach/shutdown owns target-input and inject
        // cleanup. Until then (or on any failure path), we release both in the finally below.
        var resourcesOwnedByAgentState = false
        var targetInputResource: AutoCloseable? = null
        try {
            val loader = bootstrap.classLoader
            System.err.println("[spectre-agent] found Spectre via $loader")

            // Open AWT peer packages so core window-identity can resolve host HWND/NSWindow*/XID
            // for embedded ComposePanel surfaces (Compose windowHandle is 0 there). Best-effort:
            // failures are logged and identity falls back to null handles.
            AwtPeerModuleOpener.openFor(loader, instrumentation)

            // Parsed before the automator is built, not after: the driver's coordination policy is
            // fixed at construction, and the attacher's decision about it arrives in agentArgs.
            val parsedArgs = AgentBootstrapArgs.parse(agentArgs)

            val agentAutomator = createAutomatorReflectively(loader, parsedArgs.inputCoordination)
            val automator = agentAutomator.instance
            targetInputResource = agentAutomator.targetInputResource
            System.err.println("[spectre-agent] ComposeAutomator ready: $automator")

            // The attacher's frame budget has to be adopted before the IPC server can answer: this
            // JVM writes the bulky screenshot frames and cannot read the daemon's environment.
            parsedArgs.maxFrameBytes?.let { budget ->
                runCatching { FrameLimits.configure(budget) }
                    .onFailure {
                        System.err.println(
                            "[spectre-agent] ignoring invalid maxFrameBytes=$budget: ${it.message}"
                        )
                    }
            }
            val udsPath = parsedArgs.udsPath?.let(Path::of)
            if (udsPath == null) {
                // No UDS path means manual diagnostic mode: report window count to stderr so a
                // user can verify Spectre is correctly on a target's classpath.
                val count = invokeWindowsCountReflectively(automator)
                System.err.println(
                    "[spectre-agent] no UDS path provided; spike mode reports " +
                        "getWindows().size = $count"
                )
                return
            }

            // IpcServer's constructor throws IOException on bind failure (path too long,
            // permission issue, …). Let it propagate.
            val handler = ReflectiveAutomatorHandler(automator)
            val server =
                IpcServer(udsPath = udsPath, handler = handler, onDetach = ::onClientDetach)

            // Register the shutdown hook BEFORE publishing AgentState so a crash between here and
            // CAS leaves no orphans. We carry the hook Thread in AgentState so onClientDetach can
            // unregister it.
            val shutdownHook =
                Thread(
                    {
                        // Path B — crash safety. close() handles its own idempotency.
                        runCatching { server.close() }
                        runCatching { targetInputResource?.close() }
                        bootstrap.releaseInjectResources()
                    },
                    SHUTDOWN_HOOK_NAME,
                )
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            val newState =
                AgentState(
                    server = server,
                    udsPath = udsPath,
                    shutdownHook = shutdownHook,
                    bootstrap = bootstrap,
                    targetInputResource = targetInputResource,
                )
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

            resourcesOwnedByAgentState = true
            System.err.println(
                "[spectre-agent] IPC server listening on $udsPath — ready for client connections"
            )
        } finally {
            if (!resourcesOwnedByAgentState) {
                runCatching { targetInputResource?.close() }
                bootstrap.releaseInjectResources()
            }
        }
    }

    /**
     * Called by [IpcServer] when it processes an `AgentRequest.Detach`. Performs the full D-7 Path
     * A cleanup: clears the global slot, closes the server and target-side input coordination (both
     * idempotent), removes the shutdown hook, and releases inject payload resources when the attach
     * used injection (#209).
     *
     * The server has *already* set its `running` flag to false before invoking this; the `close()`
     * here ensures the ServerSocketChannel native fd is released and the UDS path unlinked. Without
     * it, the channel would leak until JVM exit.
     */
    private fun onClientDetach() {
        val state = agentState.getAndSet(null) ?: return
        runCatching { state.server.close() }
        runCatching { state.targetInputResource?.close() }
        runCatching { Runtime.getRuntime().removeShutdownHook(state.shutdownHook) }
        state.bootstrap.releaseInjectResources()
        System.err.println("[spectre-agent] detached cleanly; resources released")
    }

    private fun createAutomatorReflectively(
        classLoader: ClassLoader,
        inputCoordination: AttachInputCoordination,
    ): AgentAutomator {
        val automatorClass = classLoader.loadClass(COMPOSE_AUTOMATOR_FQN)
        val companion = automatorClass.getField("Companion").get(null)

        val robotDriverClass = classLoader.loadClass(ROBOT_DRIVER_FQN)
        val robotDriver =
            createCoordinatedRobotDriverOrLegacyFallback(
                classLoader,
                robotDriverClass,
                inputCoordination,
            )

        val inProcessMethod =
            companion.javaClass.methods.firstOrNull {
                it.name == "inProcess" && it.parameterCount == 2
            }
                ?: error(
                    "Could not find ComposeAutomator.Companion.inProcess(robotDriver, " +
                        "discoverWindows) on ${companion.javaClass}"
                )
        return AgentAutomator(
            instance = inProcessMethod.invoke(companion, robotDriver, true),
            targetInputResource = robotDriver as? AutoCloseable,
        )
    }

    /**
     * Builds the target's `RobotDriver` on the coordination policy [inputCoordination] names.
     *
     * The default is and stays [AttachInputCoordination.Required] (#472): coordination is the
     * mutual exclusion that stops two Spectre processes interleaving real input on one desktop, so
     * an unreachable coordinator has to fail loudly rather than quietly stop policing. The
     * parameter only ever carries a choice the *attacher* made deliberately — nothing on this side
     * infers it from a failure, a timeout, or a property the target happens to carry.
     *
     * Targets predating `InputLeasePolicy` still fall back to the no-argument driver, which was
     * never coordinated; the opt-out is a no-op there rather than an error, since it asks for what
     * that target already does.
     */
    internal fun createCoordinatedRobotDriverOrLegacyFallback(
        classLoader: ClassLoader,
        robotDriverClass: Class<*>,
        inputCoordination: AttachInputCoordination = AttachInputCoordination.Required,
    ): Any =
        try {
            val policyClass = classLoader.loadClass(INPUT_LEASE_POLICY_FQN)
            val policyName = inputCoordination.leasePolicyName
            val policy =
                policyClass.enumConstants.firstOrNull { (it as Enum<*>).name == policyName }
                    ?: error("Could not resolve InputLeasePolicy.$policyName from $policyClass")
            if (inputCoordination != AttachInputCoordination.Required) {
                System.err.println(
                    "[spectre-agent] desktop input coordination is DISABLED in this target " +
                        "(InputLeasePolicy.$policyName), by explicit request from the attacher. " +
                        "Concurrent Spectre runs can now interleave real input on this desktop. " +
                        "See https://github.com/rock3r/spectre/issues/472"
                )
            }
            invokeSyntheticFactory(robotDriverClass, policy)
                ?: robotDriverClass.getDeclaredConstructor(policyClass).newInstance(policy)
        } catch (_: ClassNotFoundException) {
            invokeSyntheticFactory(robotDriverClass, policy = null)
                ?: robotDriverClass.getDeclaredConstructor().newInstance()
        } catch (_: NoSuchMethodException) {
            invokeSyntheticFactory(robotDriverClass, policy = null)
                ?: robotDriverClass.getDeclaredConstructor().newInstance()
        }

    /**
     * Prefers `RobotDriver.Companion.synthetic(policy)` / `synthetic()` so attach defaults to
     * synthetic AWT input. Falls back to `null` when the target core predates those factories.
     */
    private fun invokeSyntheticFactory(robotDriverClass: Class<*>, policy: Any?): Any? {
        val companionField =
            runCatching { robotDriverClass.getField("Companion") }.getOrNull() ?: return null
        val companion = companionField.get(null) ?: return null
        val methods = companion.javaClass.methods.filter { method -> method.name == "synthetic" }
        if (policy != null) {
            val withPolicy = methods.firstOrNull { method ->
                method.parameterCount == 1 && method.parameterTypes[0].isInstance(policy)
            }
            if (withPolicy != null) return withPolicy.invoke(companion, policy)
        }
        return methods.firstOrNull { method -> method.parameterCount == 0 }?.invoke(companion)
    }

    private fun invokeWindowsCountReflectively(automator: Any): Int {
        // `ComposeAutomator.windows` is a stale `@Volatile` cache populated by
        // `refreshWindows()` — reading the getter directly without refreshing first reports
        // 0 even when the target has visible windows. The main per-request handler in
        // `ReflectiveAutomatorHandler.handleWindows` refreshes before every read; this
        // attach-time diagnostic must do the same or it reports "windows = 0" misleadingly.
        // Bugbot caught it (LOW).
        automator.javaClass.getMethod("refreshWindows").invoke(automator)
        val getWindowsMethod = automator.javaClass.getMethod("getWindows")
        val windows = getWindowsMethod.invoke(automator) as List<*>
        return windows.size
    }

    private const val ROBOT_DRIVER_FQN = "dev.sebastiano.spectre.core.RobotDriver"
    private const val INPUT_LEASE_POLICY_FQN = "dev.sebastiano.spectre.core.InputLeasePolicy"
    private const val SHUTDOWN_HOOK_NAME = "spectre-agent-shutdown"

    private data class AgentAutomator(val instance: Any, val targetInputResource: AutoCloseable?)

    /** Single live agent state, swapped under [agentState] atomically. */
    private data class AgentState(
        val server: IpcServer,
        val udsPath: Path,
        val shutdownHook: Thread,
        val bootstrap: SpectreBootstrapResult,
        val targetInputResource: AutoCloseable?,
    )
}

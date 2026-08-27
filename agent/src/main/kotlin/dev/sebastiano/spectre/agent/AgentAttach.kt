@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.runtime.AgentBootstrapArgs
import dev.sebastiano.spectre.agent.transport.FrameLimits
import dev.sebastiano.spectre.agent.transport.IpcClient
import dev.sebastiano.spectre.agent.transport.UdsPathLimits
import dev.sebastiano.spectre.input.InputCoordinatorClientFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Entry point for attaching the Spectre agent to a running JVM.
 *
 * Usage:
 * ```
 * @OptIn(ExperimentalSpectreAgentApi::class)
 * AgentAttach.attach(pid = 12345).use { automator ->
 *     val windows = automator.windows()
 *     // …
 * } // Detach + cleanup on AutoCloseable.close()
 * ```
 *
 * Implementation: locates the loadable agent runtime JAR, runs Attach-API preconditions (per plan
 * D-13), picks a fresh UDS path, calls `VirtualMachine.attach(pid).loadAgent(jar, udsPath)`, polls
 * for the UDS path to appear, and connects an [IpcClient].
 */
@ExperimentalSpectreAgentApi
public object AgentAttach {

    /**
     * Builds the `agentArgs` string for [pid]'s injected runtime.
     *
     * Always carries the budget, including the default one. The target may have been launched with
     * a `SPECTRE_MAX_FRAME_BYTES` of its own, and letting that win would leave the daemon and the
     * JVM it injected disagreeing about the hop between them — a 16MiB target under a 64MiB daemon
     * would fail captures the daemon could carry.
     */
    private fun agentArgsFor(
        options: AttachOptions,
        udsPath: Path,
        inputCoordination: AttachInputCoordination,
    ): String =
        AgentBootstrapArgs.render(
            udsPath = udsPath.toString(),
            maxFrameBytes = options.maxFrameBytes ?: FrameLimits.maxFrameBytes,
            inputCoordination = inputCoordination,
        )

    /**
     * Resolves the coordination mode for one attach: the explicit option if there is one, otherwise
     * [AttachInputCoordination.PROPERTY], otherwise [AttachInputCoordination.Required].
     *
     * The property is not redundant with the option. `DaemonSessionRegistry` attaches with
     * `AgentAttach.attach(pid)` and no options at all, so for anyone driving Spectre through the
     * CLI the property is the *only* channel that reaches this decision; the option is for
     * embedders, and for tests that must not depend on a JVM-global.
     *
     * [property] is a parameter so the precedence above can be tested without mutating a global.
     */
    internal fun resolveInputCoordination(
        options: AttachOptions,
        property: String? = System.getProperty(AttachInputCoordination.PROPERTY),
    ): AttachInputCoordination =
        options.inputCoordination ?: AttachInputCoordination.fromProperty(property)

    /** Attach to the JVM identified by [pid] and return a connected [AttachedAutomator]. */
    @Throws(SpectreAttachException::class)
    public fun attach(pid: Long, options: AttachOptions = AttachOptions()): AttachedAutomator {
        AttachRuntimePreflight.requireSupported()
        AgentPlatformPreflight.requireSupported()
        // Resolve and length-check the UDS path before anything expensive: the agent binds it
        // inside the *target* JVM, so an overlong path surfaces there as an opaque
        // "agent failed to initialize" with the real cause in the target's stderr (#442).
        // `defaultUdsPath` already picks a base that fits; this catches a caller-supplied one.
        val udsPath = effectiveUdsPath(options.udsPath, pid)
        if (UdsPathLimits.exceedsLimit(udsPath)) throw UdsPathTooLongException(listOf(udsPath))
        val agentJar = resolveAgentJar(options)
        // Pre-flight: ensure the path doesn't already exist (collisions would confuse the bind).
        Files.deleteIfExists(udsPath)

        // Same-user preflight (plan D-13). The Attach API's underlying error when users differ is
        // generic ("Operation not permitted") and hard to diagnose; this gives a clear message
        // before we even open the VM connection.
        checkSameUser(pid)
        val inputCoordination = resolveInputCoordination(options)
        announceInputCoordination(inputCoordination)
        // Started unconditionally, including when this attach has opted out. Skipping it would save
        // `connectOrStart`'s 5s budget on a wedged host, but the runtime jar is selectable
        // (AttachOptions.agentJarPath, or the runtime-jar property) and one predating #472 ignores
        // the unknown agentArgs field and builds a Required driver regardless. Not standing a
        // coordinator up would leave that pairing with nothing to reach — turning a working
        // coordinated session into a broken one, in the name of an opt-out it never received.
        // Five seconds on an already-broken host is the cheaper side of that trade. Codex caught
        // this on the first review of #472.
        val coordinatorClient = runCatching(::ensureInputCoordinator).getOrNull()
        var coordinatorOwnedByResult = false
        try {

            val (vmClass, vm) = openVirtualMachine(pid)
            try {
                loadAgentReflectively(
                    vmClass,
                    vm,
                    agentJar.toString(),
                    agentArgsFor(options, udsPath, inputCoordination),
                )
            } finally {
                detachVirtualMachine(vmClass, vm)
            }

            waitForUdsPath(udsPath, options.attachTimeoutMs)

            val client =
                try {
                    IpcClient(udsPath)
                } catch (ex: SpectreAgentException) {
                    // Preserve taxonomy from handshake (e.g. protocolMismatch) — do not wrap.
                    throw ex
                } catch (ex: IOException) {
                    throw SpectreAttachExceptionImpl(
                        "Failed to connect to agent's UDS at $udsPath: ${ex.message}",
                        ex,
                    )
                }

            val attached =
                AttachedAutomator(pid = pid, client = client) {
                    coordinatorClient?.close()
                    // Detacher: best-effort UDS cleanup after the AttachedAutomator closes. The
                    // agent's own shutdown hook handles crash cleanup.
                    runCatching { Files.deleteIfExists(udsPath) }
                }
            coordinatorOwnedByResult = true
            return attached
        } finally {
            if (!coordinatorOwnedByResult) coordinatorClient?.close()
        }
    }

    private fun ensureInputCoordinator() =
        InputCoordinatorClientFactory.connectOrStart(ownerLabel = "spectre-agent-attacher")

    /**
     * Says on stderr that the escape hatch is in force, for as long as it is.
     *
     * A session running without desktop coordination has to be identifiable as one after the fact.
     * The interesting failures here are the ones that only make sense once you know two runs were
     * driving the same mouse, and by then the only evidence left is the log.
     */
    private fun announceInputCoordination(mode: AttachInputCoordination) {
        if (mode == AttachInputCoordination.Required) return
        val switch =
            "-D${AttachInputCoordination.PROPERTY}=${AttachInputCoordination.DISABLE_VALUE}"
        System.err.println(
            "[spectre-attach] desktop input coordination is DISABLED for this attach " +
                "($switch, or AttachOptions.inputCoordination). Nothing will stop another " +
                "Spectre process from driving the same mouse and keyboard concurrently. " +
                "See https://github.com/rock3r/spectre/issues/472"
        )
    }

    /**
     * Resolve the agent runtime JAR by trying in order:
     * 1. [AttachOptions.agentJarPath] if non-null.
     * 2. The system property `dev.sebastiano.spectre.agent.runtimeJar`.
     * 3. A `spectre-agent-runtime-<version>.jar` or `agent-runtime-<version>.jar` entry on the
     *    attacher's `java.class.path`.
     * 4. In-repo fallback at `<spectre-checkout>/agent-runtime/build/libs/agent-runtime-*.jar`,
     *    only when the current working directory is inside a Spectre source checkout.
     */
    private fun resolveAgentJar(options: AttachOptions): Path =
        AgentJarResolution.resolveRuntimeJar(
            agentJarPath = options.agentJarPath,
            runtimeJarSystemProperty = System.getProperty(AGENT_JAR_PROPERTY),
            classPath = System.getProperty("java.class.path").orEmpty(),
            cwd = Paths.get(System.getProperty("user.dir")),
        )

    /**
     * Resolves the public `com.sun.tools.attach.VirtualMachine` class and calls its static
     * `attach(String)` factory. Returns the (class, instance) pair so subsequent reflective calls
     * (`loadAgent`, `detach`) can be looked up on the *public* class rather than the concrete
     * subclass — HotSpot returns an instance of internal `sun.tools.attach.HotSpotVirtualMachine`,
     * whose methods are not reflectively accessible from outside the `jdk.attach` module.
     */
    private fun openVirtualMachine(pid: Long): Pair<Class<*>, Any> {
        val vmClass =
            try {
                Class.forName("com.sun.tools.attach.VirtualMachine")
            } catch (ex: ClassNotFoundException) {
                throw AttachUnsupportedException(ex)
            }
        val attach = vmClass.getMethod("attach", String::class.java)
        val vm =
            try {
                attach.invoke(null, pid.toString())
            } catch (ex: ReflectiveOperationException) {
                val cause = ex.cause ?: ex
                throw SpectreAttachExceptionImpl(
                    "VirtualMachine.attach($pid) failed: ${cause.javaClass.simpleName}: ${cause.message}",
                    cause,
                )
            }
        return vmClass to vm
    }

    /**
     * Same-user preflight, delegated to the per-platform [AttachUserPreflight] seam. See that type
     * for the rationale (the JDK Attach API only rendezvous across same-user processes) and the
     * per-OS ownership-comparison semantics.
     */
    private fun checkSameUser(targetPid: Long) {
        AttachUserPreflight.forOs().requireSameUser(targetPid)
    }

    private fun loadAgentReflectively(
        vmClass: Class<*>,
        vm: Any,
        jarPath: String,
        agentArgs: String,
    ) {
        // Look up the method on the *public* VirtualMachine class — looking it up on `vm.javaClass`
        // returns the override declared on `sun.tools.attach.HotSpotVirtualMachine`, which is in
        // an unexported module and rejects reflective access.
        val loadAgent = vmClass.getMethod("loadAgent", String::class.java, String::class.java)
        try {
            loadAgent.invoke(vm, jarPath, agentArgs)
        } catch (ex: ReflectiveOperationException) {
            val cause = ex.cause ?: ex
            throw SpectreAttachExceptionImpl(
                dynamicAgentLoadingGuidance(cause.message)
                    ?: "VirtualMachine.loadAgent($jarPath) failed: " +
                        "${cause.javaClass.simpleName}: ${cause.message}",
                cause,
            )
        }
    }

    private fun detachVirtualMachine(vmClass: Class<*>, vm: Any) {
        runCatching { vmClass.getMethod("detach").invoke(vm) }
    }

    private fun waitForUdsPath(udsPath: Path, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(udsPath)) return
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (ex: InterruptedException) {
                // Preserve interrupt status so well-behaved callers can re-check it.
                Thread.currentThread().interrupt()
                // Throw a dedicated cancellation exception rather than returning silently.
                // Returning would let the caller proceed to `IpcClient(udsPath)`, whose
                // SocketChannel.open would then throw `ClosedByInterruptException` —
                // wrapped further as "Failed to connect to agent's UDS at …", burying the
                // real cause (interruption) under a misleading connect failure. Bugbot
                // caught the misleading-error path (LOW); pinning the contract here.
                throw AttachInterruptedException(udsPath, ex)
            }
        }
        throw AgentBootstrapTimeoutException(udsPath, timeoutMs)
    }

    private const val AGENT_JAR_PROPERTY = "dev.sebastiano.spectre.agent.runtimeJar"
    private const val POLL_INTERVAL_MS: Long = 50L
}

/** Internal concrete subclass — sealed parent prevents downstream subclassing. */
@OptIn(ExperimentalSpectreAgentApi::class)
private class SpectreAttachExceptionImpl(message: String, cause: Throwable?) :
    SpectreAttachException(message, cause)

/**
 * The UDS path [AgentAttach.attach] will actually use: [explicit] when the caller supplied one,
 * otherwise [AttachOptions.defaultUdsPath], resolved to an absolute path either way.
 *
 * Resolving matters because the two ends of the attach do not share a working directory. The
 * attacher polls `Files.exists(udsPath)` in its own; the target JVM binds in its own, which differs
 * for every launch mode. A relative path would name two different files and the attach could only
 * time out. Resolving here also makes the `sun_path` length check measure the path the target
 * really binds.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun effectiveUdsPath(explicit: Path?, targetPid: Long): Path =
    (explicit ?: AttachOptions.defaultUdsPath(targetPid)).toAbsolutePath()

internal fun dynamicAgentLoadingGuidance(message: String?): String? =
    if (
        message?.contains("Dynamic agent loading is not enabled") == true &&
            message.contains("EnableDynamicAgentLoading")
    ) {
        "The target JVM does not allow dynamic agent loading. Restart it with " +
            "`-XX:+EnableDynamicAgentLoading` and retry the attach."
    } else {
        null
    }

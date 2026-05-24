package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.IpcClient
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
 * Implementation: locates the agent fat JAR, runs Attach-API preconditions (per plan D-13), picks a
 * fresh UDS path, calls `VirtualMachine.attach(pid).loadAgent(jar, udsPath)`, polls for the UDS
 * path to appear, and connects an [IpcClient].
 */
@ExperimentalSpectreAgentApi
public object AgentAttach {

    /** Attach to the JVM identified by [pid] and return a connected [AttachedAutomator]. */
    @Throws(SpectreAttachException::class)
    public fun attach(pid: Long, options: AttachOptions = AttachOptions()): AttachedAutomator {
        val agentJar = resolveAgentJar(options)
        val udsPath = options.udsPath ?: AttachOptions.defaultUdsPath(pid)
        // Pre-flight: ensure the path doesn't already exist (collisions would confuse the bind).
        Files.deleteIfExists(udsPath)

        // Same-UID preflight (plan D-13). The Attach API's underlying error when UIDs differ is
        // generic ("Operation not permitted") and hard to diagnose; this gives a clear message
        // before we even open the VM connection.
        checkSameUid(pid)

        val (vmClass, vm) = openVirtualMachine(pid)
        try {
            loadAgentReflectively(vmClass, vm, agentJar.toString(), udsPath.toString())
        } finally {
            detachVirtualMachine(vmClass, vm)
        }

        waitForUdsPath(udsPath, options.attachTimeoutMs)

        val client =
            try {
                IpcClient(udsPath)
            } catch (ex: IOException) {
                throw SpectreAttachExceptionImpl(
                    "Failed to connect to agent's UDS at $udsPath: ${ex.message}",
                    ex,
                )
            }

        return AttachedAutomator(pid = pid, client = client) {
            // Detacher: best-effort UDS cleanup after the AttachedAutomator closes. The agent's
            // own shutdown hook handles crash cleanup.
            runCatching { Files.deleteIfExists(udsPath) }
        }
    }

    /**
     * Resolve the agent fat JAR by trying in order:
     * 1. [AttachOptions.agentJarPath] if non-null.
     * 2. The system property `dev.sebastiano.spectre.agent.runtimeJar`.
     * 3. `<cwd>/agent/build/libs/agent-*-all.jar` (any match).
     */
    @Suppress("ReturnCount")
    private fun resolveAgentJar(options: AttachOptions): Path {
        val tried = mutableListOf<Path>()

        options.agentJarPath?.let { path ->
            tried.add(path)
            if (Files.isRegularFile(path)) return path
        }

        val sysProp = System.getProperty(AGENT_JAR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (sysProp != null) {
            val path = Paths.get(sysProp)
            tried.add(path)
            if (Files.isRegularFile(path)) return path
        }

        val cwdGuess = Paths.get(System.getProperty("user.dir")).resolve("agent/build/libs")
        if (Files.isDirectory(cwdGuess)) {
            val match =
                Files.list(cwdGuess).use { stream ->
                    stream
                        .filter { p ->
                            val n = p.fileName.toString()
                            n.startsWith("agent-") && n.endsWith("-all.jar")
                        }
                        .findFirst()
                        .orElse(null)
                }
            if (match != null) return match
            tried.add(cwdGuess.resolve("agent-*-all.jar"))
        }

        throw AgentJarNotFoundException(tried)
    }

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
     * Same-UID preflight. The Attach API's POSIX implementation requires both processes share an
     * effective UID; otherwise the rendezvous file under `/tmp/.java_pid<pid>` is unreadable and
     * `VirtualMachine.attach` fails with a hard-to-diagnose error.
     *
     * We use `ProcessHandle` which is portable; missing process info (some sandboxes return empty
     * `user()`) is treated as "can't tell — let the attach proceed and surface whatever error it
     * gives".
     */
    private fun checkSameUid(targetPid: Long) {
        val handle = ProcessHandle.of(targetPid).orElse(null) ?: return
        val targetUser = handle.info().user().orElse(null) ?: return
        val currentUser = System.getProperty("user.name") ?: return
        if (targetUser != currentUser) {
            throw AttachPermissionDeniedException(targetPid, targetUser)
        }
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
                "VirtualMachine.loadAgent($jarPath) failed: ${cause.javaClass.simpleName}: ${cause.message}",
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
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
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

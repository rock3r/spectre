package dev.sebastiano.spectre.agent

/**
 * Read-only view of one JVM process visible via the JDK Attach API.
 *
 * @property pid OS process id.
 * @property displayName the descriptor returned by `VirtualMachine.list()` — usually the main class
 *   plus any program arguments. Useful for human-readable filtering.
 */
@ExperimentalSpectreAgentApi
public data class JvmProcessInfo(public val pid: Long, public val displayName: String)

/**
 * JVM-process enumeration helper. Wraps `com.sun.tools.attach.VirtualMachine.list()` with a small
 * typed surface and a `findByName` convenience.
 *
 * Limits documented in the plan's R-6: `VirtualMachine.list()` only enumerates JVMs owned by the
 * current user with `hsperfdata` enabled (the JVM default). Targets started with `-XX:-UsePerfData`
 * won't appear here but can still be attached to by explicit pid.
 */
@ExperimentalSpectreAgentApi
public object SpectreProcesses {
    /**
     * Returns every JVM the JDK Attach API can see from this process. Throws
     * [AttachUnsupportedException] if the attacher is on a JRE rather than a JDK.
     */
    public fun listJvmProcesses(): List<JvmProcessInfo> {
        return try {
            val vmClass = Class.forName("com.sun.tools.attach.VirtualMachine")
            val listMethod = vmClass.getMethod("list")
            @Suppress("UNCHECKED_CAST") val descriptors = listMethod.invoke(null) as List<Any>
            descriptors.map { descriptor ->
                val id = (descriptor.javaClass.getMethod("id").invoke(descriptor) as String)
                val name =
                    descriptor.javaClass.getMethod("displayName").invoke(descriptor) as String
                JvmProcessInfo(pid = id.toLong(), displayName = name)
            }
        } catch (ex: ClassNotFoundException) {
            throw AttachUnsupportedException(ex)
        }
    }

    /**
     * Returns the JVMs whose [JvmProcessInfo.displayName] contains [nameFilter] (case-insensitive).
     * Convenience for `listJvmProcesses().filter { ... }`.
     */
    public fun findByName(nameFilter: String): List<JvmProcessInfo> =
        listJvmProcesses().filter { it.displayName.contains(nameFilter, ignoreCase = true) }
}

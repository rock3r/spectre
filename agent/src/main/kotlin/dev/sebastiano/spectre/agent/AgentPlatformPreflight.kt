package dev.sebastiano.spectre.agent

import java.io.IOException
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel

/**
 * Gate for [AgentAttach.attach]: the agent transport rides on native `AF_UNIX` sockets, so the host
 * must support them.
 *
 * Linux and macOS have supported `AF_UNIX` since the JDK 16 minimum Spectre targets, so they pass
 * unconditionally. Windows gained native `AF_UNIX` in Windows 10 version 1803 / Windows Server
 * 2019; rather than parse a build number (`os.version` is just `"10.0"` on both Windows 10 and 11),
 * we probe the actual capability by opening — without binding — a `ServerSocketChannel` in the
 * `UNIX` protocol family. That throws [UnsupportedOperationException] on hosts without `AF_UNIX`.
 */
@ExperimentalSpectreAgentApi
internal object AgentPlatformPreflight {
    fun requireSupported(
        osName: String = System.getProperty("os.name").orEmpty(),
        afUnixSupported: () -> Boolean = ::isAfUnixSupported,
    ) {
        // Only Windows needs the capability check; Linux/macOS always have AF_UNIX on JDK 16+.
        if (!osName.startsWith("Windows", ignoreCase = true)) return
        if (!afUnixSupported()) throw AttachPlatformUnsupportedException(osName)
    }

    /**
     * True when this JVM can open an `AF_UNIX` socket channel. Opening without binding creates no
     * filesystem entry; it throws [UnsupportedOperationException] on platforms without native
     * `AF_UNIX` (pre-1803 Windows).
     */
    private fun isAfUnixSupported(): Boolean =
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { true }
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            // An unexpected low-level failure opening the (unbound) probe channel is not evidence
            // that AF_UNIX is unsupported — only UnsupportedOperationException is. Treat it as
            // supported and let the real bind in IpcServer surface any genuine problem, rather than
            // letting a raw IOException escape the SpectreAttachException contract of attach().
            true
        }
}

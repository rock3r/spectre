package dev.sebastiano.spectre.agent.transport

import java.nio.charset.Charset
import java.nio.file.Path

/**
 * The `sockaddr_un.sun_path` budget for Unix Domain Socket paths, and a readable diagnostic for
 * paths that blow past it.
 *
 * The kernel copies a UDS path into a fixed-size `sun_path` array, so `bind` fails outright once
 * the path no longer fits. The JDK surfaces that as `SocketException: Unix domain path too long`,
 * which names neither the path nor the cap — and in Spectre's case the bind happens inside the
 * *target* JVM, so the attaching process sees only "agent failed to initialize" (#442). Everything
 * here exists to turn that into a message a user can act on.
 */
internal object UdsPathLimits {
    /** `sun_path` size on macOS/BSD. */
    private const val MAC_SUN_PATH_SIZE: Int = 104

    /** `sun_path` size on Linux and Windows. */
    private const val DEFAULT_SUN_PATH_SIZE: Int = 108

    /** Usable path bytes on this host — `sun_path` minus one byte for the NUL terminator. */
    val maxPathBytes: Int = maxPathBytesFor(System.getProperty("os.name").orEmpty())

    /**
     * Usable path bytes on [osName]. Extracted from [maxPathBytes] so the per-OS arithmetic is
     * testable on any host.
     */
    fun maxPathBytesFor(osName: String): Int =
        if (
            osName.startsWith("Mac", ignoreCase = true) ||
                osName.startsWith("Darwin", ignoreCase = true)
        ) {
            MAC_SUN_PATH_SIZE - 1
        } else {
            DEFAULT_SUN_PATH_SIZE - 1
        }

    /**
     * Length of [path] in bytes, using the same encoding the JDK uses to hand paths to the OS
     * (`sun.jnu.encoding`). Counting characters would under-report for non-ASCII paths — a home
     * directory with accented characters costs more bytes than it looks.
     */
    fun byteLength(path: Path): Int = path.toString().toByteArray(NATIVE_CHARSET).size

    /** True when binding a UDS at [path] would fail because the path does not fit `sun_path`. */
    fun exceedsLimit(path: Path): Boolean = byteLength(path) > maxPathBytes

    /** Diagnostic for a [path] that [exceedsLimit]: names the path, its size, and the cap. */
    fun tooLongMessage(path: Path): String =
        "Unix domain socket path is ${byteLength(path)} bytes, but this platform's " +
            "sockaddr_un.sun_path holds at most $maxPathBytes. Path: $path"

    private val NATIVE_CHARSET: Charset =
        System.getProperty("sun.jnu.encoding")?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        } ?: Charset.defaultCharset()
}

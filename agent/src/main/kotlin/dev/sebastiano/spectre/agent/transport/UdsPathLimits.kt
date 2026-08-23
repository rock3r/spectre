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

    /**
     * Bytes of `sun_path` the JDK will not let a path occupy. It is **two**, not the one byte a NUL
     * terminator would suggest, and getting this wrong defeats the whole guard: a path one byte
     * over would pass here and then be refused by `bind` with the opaque error this object exists
     * to replace.
     *
     * Measured, not derived. `ServerSocketChannel.bind` on an `AF_UNIX` channel:
     * - macOS 15, JDK 21.0.12 — 102 bytes binds, 103 throws `SocketException`. `sun_path` is 104.
     * - Windows 11, JBR 21.0.10 — 106 bytes binds, 107 throws. `sun_path` is 108.
     *
     * `UdsPathLimitsTest` re-measures the boundary with a real bind on whatever host it runs on, so
     * a platform that disagrees fails there rather than in the field.
     */
    private const val SUN_PATH_RESERVED_BYTES: Int = 2

    /** Usable path bytes on this host. */
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
            MAC_SUN_PATH_SIZE - SUN_PATH_RESERVED_BYTES
        } else {
            DEFAULT_SUN_PATH_SIZE - SUN_PATH_RESERVED_BYTES
        }

    /**
     * Charset the JDK encodes a UDS path with before handing it to the OS. It is **not** the same
     * on every platform, and the difference is load-bearing:
     * - **Windows**: `sun.nio.fs.WindowsFileSystemProvider.getSunPathForSocketFile` ends in
     *   `s.getBytes(StandardCharsets.UTF_8)` — always UTF-8, whatever the JVM's other encodings
     *   say.
     * - **Linux/macOS**: `sun.nio.fs.UnixPath.encode` goes through `Util.jnuEncoding()`, which is
     *   the `sun.jnu.encoding` property.
     *
     * A Windows JVM commonly reports `sun.jnu.encoding=Cp1252`, where an accented letter costs one
     * byte and UTF-8 costs two. Measuring a `C:\Users\<accented name>\…` path with the code page
     * would under-count it, keep a candidate that does not actually fit, and reproduce #442 by
     * another route.
     */
    fun nativeCharsetFor(osName: String): Charset =
        if (osName.startsWith("Windows", ignoreCase = true)) Charsets.UTF_8 else jnuCharset()

    /**
     * Length of [path] in bytes as the OS will receive it. Counting characters would under-report
     * for non-ASCII paths — a home directory with accented characters costs more bytes than it
     * looks.
     */
    fun byteLength(path: Path): Int = byteLength(path, NATIVE_CHARSET)

    /** [byteLength] against an explicit [charset]; the per-platform choice is testable that way. */
    fun byteLength(path: Path, charset: Charset): Int = path.toString().toByteArray(charset).size

    /** True when binding a UDS at [path] would fail because the path does not fit `sun_path`. */
    fun exceedsLimit(path: Path): Boolean = byteLength(path) > maxPathBytes

    /** [exceedsLimit] against an explicit [charset] and [limit], for the per-platform tests. */
    fun exceedsLimit(path: Path, charset: Charset, limit: Int): Boolean =
        byteLength(path, charset) > limit

    /** Diagnostic for a [path] that [exceedsLimit]: names the path, its size, and the cap. */
    fun tooLongMessage(path: Path): String =
        "Unix domain socket path is ${byteLength(path)} bytes, but this platform's " +
            "sockaddr_un.sun_path holds at most $maxPathBytes. Path: $path"

    private fun jnuCharset(): Charset =
        System.getProperty("sun.jnu.encoding")?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        } ?: Charset.defaultCharset()

    private val NATIVE_CHARSET: Charset = nativeCharsetFor(System.getProperty("os.name").orEmpty())
}

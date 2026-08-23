package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class UdsPathLimitsTest {

    @Test
    fun `the usable budget is sun_path minus two bytes`() {
        // Two, not one. Measured with a real bind: macOS (sun_path 104) takes 102 and refuses
        // 103; Windows (sun_path 108) takes 106 and refuses 107. The boundary test below
        // re-measures this on whatever host runs it.
        //
        // If these numbers ever change, four places mirror them and the compiler checks none of
        // them: the `udsPath` KDoc on AttachOptions, the UdsPathTooLongException KDoc,
        // docs/guide/agent.md, and docs/guide/troubleshooting.md. Codex review on PR #445 caught
        // exactly that drift after the reservation went from one byte to two.
        assertEquals(102, UdsPathLimits.maxPathBytesFor("Mac OS X"))
        assertEquals(102, UdsPathLimits.maxPathBytesFor("Darwin"))
        assertEquals(106, UdsPathLimits.maxPathBytesFor("Linux"))
        assertEquals(106, UdsPathLimits.maxPathBytesFor("Windows 11"))
    }

    @Test
    fun `maxPathBytes is the largest path this host actually binds`() {
        // The arithmetic helper alone cannot catch an off-by-one — the first version of this
        // constant reserved one byte instead of two and would have waved through a path that
        // `bind` rejects, which is precisely the failure #442 is about. Codex review on PR #445
        // asked for the boundary to be proven against a real socket; this is that proof, and it
        // runs on every platform CI covers.
        val base = shortestUsableBaseDir()
        assumeTrue(
            fitsUnder(base, UdsPathLimits.maxPathBytes + 1),
            "base directory $base is too long to build a boundary-length path under",
        )

        assertTrue(
            bindSucceeds(pathOfLength(base, UdsPathLimits.maxPathBytes)),
            "a path of exactly ${UdsPathLimits.maxPathBytes} bytes must bind on this host",
        )
        assertFalse(
            bindSucceeds(pathOfLength(base, UdsPathLimits.maxPathBytes + 1)),
            "a path of ${UdsPathLimits.maxPathBytes + 1} bytes must be refused on this host",
        )
    }

    @Test
    fun `Windows counts sun_path bytes as UTF-8, POSIX as the jnu encoding`() {
        // Verified against the JDK 21 sources: WindowsFileSystemProvider.getSunPathForSocketFile
        // ends in `s.getBytes(StandardCharsets.UTF_8)`, while UnixPath.encode goes through
        // Util.jnuEncoding().
        assertEquals(Charsets.UTF_8, UdsPathLimits.nativeCharsetFor("Windows 11"))

        val jnu = Charset.forName(System.getProperty("sun.jnu.encoding"))
        assertEquals(jnu, UdsPathLimits.nativeCharsetFor("Linux"))
        assertEquals(jnu, UdsPathLimits.nativeCharsetFor("Mac OS X"))
    }

    @Test
    fun `an accented Windows path fits a legacy code page but not UTF-8`() {
        // Cp1252 spends one byte on an accented letter; UTF-8 spends two. A Windows JVM commonly
        // reports sun.jnu.encoding=Cp1252, so measuring a `C:\Users\<accented name>\...` path
        // with it would under-count the path, keep a candidate that does not actually fit, and
        // reproduce #442 by another route. Raised by Codex review on PR #445.
        val cp1252 = Charset.forName("windows-1252")
        val windowsLimit = UdsPathLimits.maxPathBytesFor("Windows 11")
        val accents = 4
        val head = "C:\\Users\\" + "\u00e9".repeat(accents)
        val tail = "\\AppData\\Local\\Temp\\sp-a-1-abcdef12\\agent.sock"
        // Pad the user name so the code-page measurement lands exactly on the limit.
        val path = Path.of(head + "x".repeat(windowsLimit - head.length - tail.length) + tail)

        assertEquals(windowsLimit, UdsPathLimits.byteLength(path, cp1252))
        assertEquals(windowsLimit + accents, UdsPathLimits.byteLength(path, Charsets.UTF_8))
        assertFalse(
            UdsPathLimits.exceedsLimit(path, cp1252, windowsLimit),
            "the code-page measurement is what made this path look usable",
        )
        assertTrue(
            UdsPathLimits.exceedsLimit(path, Charsets.UTF_8, windowsLimit),
            "the UTF-8 measurement is the one the Windows socket provider actually applies",
        )
    }

    @Test
    fun `byteLength counts the path as the OS sees it`() {
        val path = Path.of("/tmp/sp-a-1234-abcdef12/agent.sock")
        assertEquals(path.toString().length, UdsPathLimits.byteLength(path))
    }

    @Test
    fun `a path exactly at the limit fits, one byte more does not`() {
        val limit = UdsPathLimits.maxPathBytes
        val atLimit = Path.of("/tmp/" + "x".repeat(limit - "/tmp/".length))
        val overLimit = Path.of("/tmp/" + "x".repeat(limit - "/tmp/".length + 1))

        assertEquals(limit, UdsPathLimits.byteLength(atLimit))
        assertFalse(UdsPathLimits.exceedsLimit(atLimit), "$atLimit is exactly at the limit")
        assertTrue(UdsPathLimits.exceedsLimit(overLimit), "$overLimit is one byte past the limit")
    }

    @Test
    fun `tooLongMessage names the path, its length, and the limit`() {
        val path = Path.of("/tmp/" + "x".repeat(200) + "/agent.sock")
        val message = UdsPathLimits.tooLongMessage(path)

        assertTrue(path.toString() in message, "message should name the path, got: $message")
        assertTrue(
            "${UdsPathLimits.byteLength(path)}" in message,
            "message should name the path's byte length, got: $message",
        )
        assertTrue(
            "${UdsPathLimits.maxPathBytes}" in message,
            "message should name the limit, got: $message",
        )
    }

    // ---- helpers for the real-bind boundary probe ----

    /** `/tmp` on POSIX; `%TEMP%` on Windows, where `/tmp` is drive-relative nonsense. */
    private fun shortestUsableBaseDir(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            Path.of(System.getProperty("java.io.tmpdir"))
        } else {
            Path.of("/tmp")
        }

    private fun fitsUnder(base: Path, totalBytes: Int): Boolean =
        UdsPathLimits.byteLength(base.resolve("x")) < totalBytes

    /** A socket path directly under [base] whose total length is exactly [totalBytes]. */
    private fun pathOfLength(base: Path, totalBytes: Int): Path {
        val oneChar = base.resolve("x")
        val padding = totalBytes - UdsPathLimits.byteLength(oneChar) + 1
        return base.resolve("x".repeat(padding))
    }

    private fun bindSucceeds(path: Path): Boolean =
        try {
            Files.deleteIfExists(path)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.bind(UnixDomainSocketAddress.of(path))
                true
            }
        } catch (_: IOException) {
            false
        } finally {
            runCatching { Files.deleteIfExists(path) }
        }
}

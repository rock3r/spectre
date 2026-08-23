package dev.sebastiano.spectre.agent.transport

import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UdsPathLimitsTest {

    @Test
    fun `macOS reserves 104 bytes of sun_path, everything else 108`() {
        // The usable budget is one byte less than sun_path: the kernel needs the NUL terminator.
        assertEquals(103, UdsPathLimits.maxPathBytesFor("Mac OS X"))
        assertEquals(103, UdsPathLimits.maxPathBytesFor("Darwin"))
        assertEquals(107, UdsPathLimits.maxPathBytesFor("Linux"))
        assertEquals(107, UdsPathLimits.maxPathBytesFor("Windows 11"))
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
}

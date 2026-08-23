package dev.sebastiano.spectre.agent.transport

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

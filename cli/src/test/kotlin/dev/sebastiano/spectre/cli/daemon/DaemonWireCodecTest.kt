package dev.sebastiano.spectre.cli.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DaemonWireCodecTest {
    @Test
    fun `writes and reads framed daemon requests`() {
        val output = ByteArrayOutputStream()
        val request = DaemonRequest.Hello(clientVersion = DaemonProtocol.CurrentVersion)

        DaemonWireCodec.writeRequest(output, request)

        val decoded = DaemonWireCodec.readRequest(ByteArrayInputStream(output.toByteArray()))
        val hello = assertIs<DaemonRequest.Hello>(decoded)
        assertEquals(DaemonProtocol.CurrentVersion, hello.clientVersion)
    }

    @Test
    fun `writes and reads framed daemon responses`() {
        val output = ByteArrayOutputStream()
        val response =
            DaemonResponse.Hello(daemonVersion = DaemonProtocolVersion(major = 1, minor = 2))

        DaemonWireCodec.writeResponse(output, response)

        val decoded = DaemonWireCodec.readResponse(ByteArrayInputStream(output.toByteArray()))
        val hello = assertIs<DaemonResponse.Hello>(decoded)
        assertEquals(DaemonProtocolVersion(major = 1, minor = 2), hello.daemonVersion)
    }

    @Test
    fun `read helpers return null on clean eof`() {
        assertNull(DaemonWireCodec.readRequest(ByteArrayInputStream(ByteArray(0))))
        assertNull(DaemonWireCodec.readResponse(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `read helpers surface truncated frames`() {
        val truncatedFrame = ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 1))

        kotlin.test.assertFailsWith<EOFException> { DaemonWireCodec.readRequest(truncatedFrame) }
    }
}

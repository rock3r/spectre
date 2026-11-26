package dev.sebastiano.spectre.cli.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonWireCodecTest {
    @Test
    fun `diagnosed size separates polymorphic response bytes from real CBOR envelope`() {
        val responses =
            listOf(
                DaemonResponse.Hello(DaemonProtocol.CurrentVersion),
                DaemonResponse.Attached("session", 42),
                DaemonResponse.Screenshot("session", ByteArray(65_537) { it.toByte() }),
                DaemonResponse.Error(DaemonErrorCode.OperationFailed, "failed-é"),
            )
        val warningSets =
            listOf(
                listOf("one"),
                List(23) { "warning-$it-é" },
                List(200) { "" },
            )

        responses.forEach { response ->
            val sizer = DiagnosedResponseSizer(response)
            warningSets.forEach { warnings ->
                assertEquals(
                    DaemonWireCodec.encodeResponse(DaemonResponse.Diagnosed(response, warnings))
                        .size,
                    sizer.encodedSize(warnings),
                )
            }
        }
    }

    @Test
    fun `two hundred warnings require only logarithmically many CBOR envelope measurements`() {
        var encodingCount = 0
        val encoder: (DaemonResponse) -> ByteArray = { response ->
            encodingCount++
            DaemonWireCodec.encodeResponse(response)
        }
        val response = DaemonResponse.Screenshot("session", ByteArray(256 * 1_024))
        val warnings = List(200) { "warning-$it" }
        val retainedCount = 137
        val expectedWarnings =
            warnings.take(retainedCount) + truncationMarker(warnings.size - retainedCount)
        val exactBudget =
            DaemonWireCodec.encodeResponse(DaemonResponse.Diagnosed(response, expectedWarnings))
                .size

        val result =
            assertIs<DaemonResponse.Diagnosed>(
                diagnosedResponse(
                    response,
                    warnings,
                    acceptsDiagnosedResponses = true,
                    maxFrameBytes = exactBudget,
                    encoder = encoder,
                )
            )

        assertEquals(expectedWarnings, result.warnings)
        assertTrue(encodingCount <= 11, "encoded $encodingCount times")
    }

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
    fun `accessibility enable permission survives the daemon wire round trip`() {
        val output = ByteArrayOutputStream()
        DaemonWireCodec.writeRequest(
            output,
            DaemonRequest.AccessibilityTree(
                processId = 42,
                allowAccessibilityEnable = true,
            ),
        )

        val decoded =
            assertIs<DaemonRequest.AccessibilityTree>(
                DaemonWireCodec.readRequest(ByteArrayInputStream(output.toByteArray()))
            )
        assertTrue(decoded.allowAccessibilityEnable)
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

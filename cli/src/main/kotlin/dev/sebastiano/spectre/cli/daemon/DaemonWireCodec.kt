package dev.sebastiano.spectre.cli.daemon

import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi

/** Encodes daemon protocol messages as CBOR payloads inside length-prefixed frames. */
@OptIn(ExperimentalSerializationApi::class)
public object DaemonWireCodec {
    /** Write one framed daemon request. */
    @Throws(java.io.IOException::class)
    public fun writeRequest(output: OutputStream, request: DaemonRequest): Unit =
        DaemonFraming.writeFrame(
            output,
            DaemonProtocol.cbor.encodeToByteArray(DaemonRequest.serializer(), request),
        )

    /** Write one framed daemon response. */
    @Throws(java.io.IOException::class)
    public fun writeResponse(output: OutputStream, response: DaemonResponse): Unit =
        DaemonFraming.writeFrame(
            output,
            DaemonProtocol.cbor.encodeToByteArray(DaemonResponse.serializer(), response),
        )

    /** Read one framed daemon request, or `null` on clean EOF before a frame starts. */
    @Throws(java.io.IOException::class)
    public fun readRequest(input: InputStream): DaemonRequest? =
        DaemonFraming.readFrame(input)?.let { bytes ->
            DaemonProtocol.cbor.decodeFromByteArray(DaemonRequest.serializer(), bytes)
        }

    /** Read one framed daemon response, or `null` on clean EOF before a frame starts. */
    @Throws(java.io.IOException::class)
    public fun readResponse(input: InputStream): DaemonResponse? =
        DaemonFraming.readFrame(input)?.let { bytes ->
            DaemonProtocol.cbor.decodeFromByteArray(DaemonResponse.serializer(), bytes)
        }
}

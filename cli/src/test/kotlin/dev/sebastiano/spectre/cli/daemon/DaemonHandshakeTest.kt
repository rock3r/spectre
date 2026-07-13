package dev.sebastiano.spectre.cli.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class DaemonHandshakeTest {
    @Test
    fun `hello request round trips through cbor with current protocol version`() {
        val wire: DaemonRequest = DaemonRequest.Hello(clientVersion = DaemonProtocol.CurrentVersion)

        val bytes = DaemonProtocol.cbor.encodeToByteArray(DaemonRequest.serializer(), wire)
        val decoded = DaemonProtocol.cbor.decodeFromByteArray(DaemonRequest.serializer(), bytes)

        val hello = assertIs<DaemonRequest.Hello>(decoded)
        assertEquals(1, hello.clientVersion.major)
        assertEquals(0, hello.clientVersion.minor)
    }

    @Test
    fun `version compatibility requires matching major and daemon minor not older than client`() {
        assertEquals(
            VersionCompatibility.Compatible,
            DaemonProtocol.checkCompatibility(
                client = DaemonProtocolVersion(major = 1, minor = 0),
                daemon = DaemonProtocolVersion(major = 1, minor = 1),
            ),
        )
        assertEquals(
            VersionCompatibility.MajorMismatch,
            DaemonProtocol.checkCompatibility(
                client = DaemonProtocolVersion(major = 1, minor = 0),
                daemon = DaemonProtocolVersion(major = 2, minor = 0),
            ),
        )
        assertEquals(
            VersionCompatibility.DaemonTooOld,
            DaemonProtocol.checkCompatibility(
                client = DaemonProtocolVersion(major = 1, minor = 1),
                daemon = DaemonProtocolVersion(major = 1, minor = 0),
            ),
        )
    }
}

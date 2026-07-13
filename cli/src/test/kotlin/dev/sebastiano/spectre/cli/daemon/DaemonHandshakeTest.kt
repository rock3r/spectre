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
        assertEquals(1, hello.clientVersion.minor)
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

@OptIn(ExperimentalSerializationApi::class)
class DaemonSessionCommandProtocolTest {
    @Test
    fun `session lifecycle requests round trip through cbor`() {
        val requests =
            listOf<DaemonRequest>(
                DaemonRequest.Attach(targetPid = 1234),
                DaemonRequest.Detach(sessionId = "session-1234"),
                DaemonRequest.ListSessions,
                DaemonRequest.Shutdown,
            )

        val decoded = requests.map { request ->
            val bytes = DaemonProtocol.cbor.encodeToByteArray(DaemonRequest.serializer(), request)
            DaemonProtocol.cbor.decodeFromByteArray(DaemonRequest.serializer(), bytes)
        }

        assertEquals(requests, decoded)
    }

    @Test
    fun `session lifecycle responses round trip through cbor`() {
        val responses =
            listOf<DaemonResponse>(
                DaemonResponse.Attached(sessionId = "session-1234", targetPid = 1234),
                DaemonResponse.Detached(sessionId = "session-1234"),
                DaemonResponse.Sessions(
                    sessions =
                        listOf(DaemonSessionSummary(sessionId = "session-1234", targetPid = 1234))
                ),
                DaemonResponse.ShuttingDown,
                DaemonResponse.Error(code = DaemonErrorCode.SessionNotFound, message = "missing"),
            )

        val decoded = responses.map { response ->
            val bytes = DaemonProtocol.cbor.encodeToByteArray(DaemonResponse.serializer(), response)
            DaemonProtocol.cbor.decodeFromByteArray(DaemonResponse.serializer(), bytes)
        }

        assertEquals(responses, decoded)
    }
}

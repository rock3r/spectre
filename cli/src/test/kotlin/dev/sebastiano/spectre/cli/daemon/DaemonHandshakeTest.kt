package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
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
        assertEquals(3, hello.clientVersion.minor)
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

    @Test
    fun `JVM process listing requires the daemon protocol minor that introduced it`() {
        assertEquals(
            DaemonProtocolVersion(major = 1, minor = 0),
            DaemonProtocol.minimumDaemonVersion(DaemonRequest.ListSessions),
        )
        assertEquals(
            DaemonProtocolVersion(major = 1, minor = 3),
            DaemonProtocol.minimumDaemonVersion(DaemonRequest.ListJvmProcesses(requesterPid = 1234)),
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
class DaemonSessionCommandProtocolTest {
    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `session operation requests round trip through cbor`() {
        val requests =
            listOf<DaemonRequest>(
                DaemonRequest.Windows("session-1234"),
                DaemonRequest.AllNodes("session-1234"),
                DaemonRequest.FindByTestTag("session-1234", "submit"),
                DaemonRequest.Click("session-1234", "main:0:1"),
                DaemonRequest.TypeText("session-1234", "hello"),
                DaemonRequest.Screenshot("session-1234"),
            )

        val decoded = requests.map(::roundTripRequest)

        assertEquals(requests, decoded)
    }

    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `session operation responses round trip through cbor`() {
        val bounds = RectDto(x = 0, y = 0, width = 100, height = 100)
        val responses =
            listOf<DaemonResponse>(
                DaemonResponse.Windows(
                    sessionId = "session-1234",
                    windows =
                        listOf(
                            WindowSummaryDto(
                                index = 0,
                                surfaceId = "main",
                                title = "Fixture",
                                isPopup = false,
                                bounds = bounds,
                            )
                        ),
                ),
                DaemonResponse.Nodes(
                    sessionId = "session-1234",
                    nodes =
                        listOf(
                            NodeSnapshotDto(
                                key = "main:0:1",
                                testTag = "submit",
                                texts = listOf("Submit"),
                                role = "Button",
                                contentDescription = null,
                                isVisible = true,
                                bounds = bounds,
                            )
                        ),
                ),
                DaemonResponse.Completed("session-1234"),
                DaemonResponse.Screenshot("session-1234", byteArrayOf(1, 2, 3)),
            )

        val decoded = responses.map(::roundTripResponse)

        assertEquals(responses, decoded)
    }

    @Test
    fun `session lifecycle requests round trip through cbor`() {
        val requests =
            listOf<DaemonRequest>(
                DaemonRequest.Attach(targetPid = 1234),
                DaemonRequest.Detach(sessionId = "session-1234"),
                DaemonRequest.ListSessions,
                DaemonRequest.ListJvmProcesses(requesterPid = 1234),
                DaemonRequest.Shutdown,
            )

        val decoded = requests.map(::roundTripRequest)

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
                DaemonResponse.JvmProcesses(
                    processes = listOf(DaemonJvmProcessSummary(pid = 1234, displayName = "Fixture"))
                ),
                DaemonResponse.ShuttingDown,
                DaemonResponse.Error(code = DaemonErrorCode.SessionNotFound, message = "missing"),
            )

        val decoded = responses.map(::roundTripResponse)

        assertEquals(responses, decoded)
    }

    private fun roundTripRequest(request: DaemonRequest): DaemonRequest {
        val bytes = DaemonProtocol.cbor.encodeToByteArray(DaemonRequest.serializer(), request)
        return DaemonProtocol.cbor.decodeFromByteArray(DaemonRequest.serializer(), bytes)
    }

    private fun roundTripResponse(response: DaemonResponse): DaemonResponse {
        val bytes = DaemonProtocol.cbor.encodeToByteArray(DaemonResponse.serializer(), response)
        return DaemonProtocol.cbor.decodeFromByteArray(DaemonResponse.serializer(), bytes)
    }
}

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.recording.RecordingResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonRecordingFinalizationTest {
    @Test
    fun `finalize returns the backend observations on detach and repeated shutdown`() {
        val result = RecordingResult(Path.of("movie.mp4"), 6, true)
        val registry = DaemonSessionRegistry {
            object : DaemonSessionAutomator by TestDaemonSessionAutomator() {
                override fun finalizeRecording(remainingLiveSessionIds: Set<String>) = result
            }
        }
        val session = assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(42)))
        val expected = DaemonResponse.RecordingDiagnostics(6, true)
        val detached =
            assertIs<DaemonResponse.Detached>(
                registry.handle(DaemonRequest.Detach(session.sessionId))
            )
        assertEquals(result.output.toString(), detached.finalizedRecordingPath)
        assertEquals(expected, detached.diagnostics)

        registry.handle(DaemonRequest.Attach(42))
        repeat(2) {
            val shutdown =
                assertIs<DaemonResponse.ShuttingDownWithRecordings>(
                    registry.handle(DaemonRequest.Shutdown)
                )
            assertEquals(expected, shutdown.recordings.single().diagnostics)
        }
    }
}

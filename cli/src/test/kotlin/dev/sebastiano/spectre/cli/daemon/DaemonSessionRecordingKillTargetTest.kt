@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
import dev.sebastiano.spectre.recording.RecordingHandle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #185 kill-target acceptance without real OS capture: the daemon owns [RecordingHandle]; stop must
 * still finalise after the attach target is gone (identity reads may fail; the handle must not).
 */
class DaemonSessionRecordingKillTargetTest {

    @Test
    fun `stop finalizes a daemon-owned recording after the target can no longer answer identity`() {
        val output = Files.createTempFile("spectre-kill-target-", ".mp4")
        val stopCount = AtomicInteger(0)
        val handle = fakeHandle(output, stopCount)
        val identity = uniqueIdentity(title = "Kill Target Fixture")
        val identities = AtomicReference<List<WindowIdentityDto>>(listOf(identity))

        val recording =
            DaemonSessionRecording(
                sessionId = "session-kill-target",
                targetPid = 42_424L,
                windowIdentities = { index ->
                    val list = identities.get()
                    if (list.isEmpty()) {
                        throw IOException("target process is gone")
                    }
                    if (index == null) list else list.filter { it.index == index }
                },
                startWindowByTitle = { _, _, _, _, _, _ -> handle },
            )

        val started = recording.start(outputPath = output.toString(), windowIndex = 0)
        assertEquals(output.toAbsolutePath().normalize().toString(), started)
        assertTrue(recording.status().active)

        // Simulate target death: identity reads now fail, but the daemon still holds the handle.
        identities.set(emptyList())

        val stoppedPath = recording.stop(liveSessionIds = emptySet())
        assertEquals(output.toAbsolutePath().normalize().toString(), stoppedPath)
        assertEquals(1, stopCount.get(), "stop must invoke the daemon-owned handle once")
        assertTrue(Files.size(output) > 0, "finalized output must be non-empty")
        assertFalse(recording.status().active)
        Files.deleteIfExists(output)
    }

    @Test
    fun `finalizeIfActive stops an in-flight recording when the attach session closes`() {
        val output = Files.createTempFile("spectre-finalize-", ".mp4")
        val stopCount = AtomicInteger(0)
        val handle = fakeHandle(output, stopCount)
        val identity = uniqueIdentity(title = "Finalize Fixture")
        val recording =
            DaemonSessionRecording(
                sessionId = "session-finalize",
                targetPid = 99L,
                windowIdentities = { listOf(identity) },
                startWindowByTitle = { _, _, _, _, _, _ -> handle },
            )
        recording.start(outputPath = output.toString(), windowIndex = 0)
        recording.finalizeIfActive(liveSessionIds = emptySet())
        assertEquals(1, stopCount.get())
        assertFalse(recording.status().active)
        Files.deleteIfExists(output)
    }

    private fun fakeHandle(output: Path, stopCount: AtomicInteger): RecordingHandle =
        object : RecordingHandle {
            private val stopped = AtomicBoolean(false)
            override val output: Path = output
            override val isStopped: Boolean
                get() = stopped.get()

            override fun stop() {
                if (stopped.compareAndSet(false, true)) {
                    stopCount.incrementAndGet()
                    Files.write(output, ByteArray(64) { 0x00 })
                }
            }
        }

    private fun uniqueIdentity(title: String): WindowIdentityDto {
        val bounds = RectDto(0, 0, 320, 200)
        return WindowIdentityDto(
            index = 0,
            surfaceId = "embedded:0",
            title = title,
            isPopup = false,
            nativeHandle = 1L,
            cropRequired = false,
            windowBoundsOnScreen = bounds,
            surfaceBoundsOnScreen = bounds,
            surfaceBoundsInWindow = bounds,
            scaleX = 1.0,
            scaleY = 1.0,
        )
    }
}

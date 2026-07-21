package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.screencapturekit.MacOsScreenCaptureAccess
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureAccessDeniedException
import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Path

/** Operation surface retained by one daemon session. */
@OptIn(ExperimentalSpectreAgentApi::class)
internal interface DaemonSessionAutomator : AutoCloseable {
    @Throws(IOException::class) fun windows(): List<WindowSummaryDto>

    @Throws(IOException::class) fun allNodes(): List<NodeSnapshotDto>

    @Throws(IOException::class) fun findByTestTag(tag: String): List<NodeSnapshotDto>

    @Throws(IOException::class) fun click(nodeKey: String)

    @Throws(IOException::class) fun typeText(text: String)

    @Throws(IOException::class) fun screenshot(): ByteArray

    @Throws(IOException::class) fun capture(windowIndex: Int = 0): AtomicCaptureResult

    @Throws(IOException::class) fun startRecording(outputPath: String): String

    @Throws(IOException::class) fun stopRecording(): String
}

@OptIn(ExperimentalSpectreAgentApi::class)
internal class AttachedDaemonSession(private val delegate: AttachedAutomator) :
    DaemonSessionAutomator {
    private var recording: RecordingHandle? = null

    override fun windows(): List<WindowSummaryDto> = delegate.windows()

    override fun allNodes(): List<NodeSnapshotDto> = delegate.allNodes()

    override fun findByTestTag(tag: String): List<NodeSnapshotDto> = delegate.findByTestTag(tag)

    override fun click(nodeKey: String): Unit = delegate.click(nodeKey)

    override fun typeText(text: String): Unit = delegate.typeText(text)

    override fun screenshot(): ByteArray = delegate.screenshot()

    override fun capture(windowIndex: Int): AtomicCaptureResult = delegate.capture(windowIndex)

    override fun startRecording(outputPath: String): String {
        if (recording != null)
            throw IOException("a recording is already in progress for this session")
        // Fail fast on macOS before any capture path that could pop a TCC prompt (#187).
        try {
            MacOsScreenCaptureAccess.requireGranted()
        } catch (exception: ScreenCaptureAccessDeniedException) {
            throw IOException(exception.message ?: "Screen Recording not granted", exception)
        }
        val window =
            windows().firstOrNull { !it.isPopup }
                ?: throw IOException("the target has no non-popup window to record")
        val destination = Path.of(outputPath)
        return try {
            recording =
                AutoRecorder()
                    .startRegion(
                        Rectangle(
                            window.bounds.x,
                            window.bounds.y,
                            window.bounds.width,
                            window.bounds.height,
                        ),
                        destination,
                    )
            outputPath
        } catch (exception: ScreenCaptureAccessDeniedException) {
            throw IOException(exception.message ?: "Screen Recording not granted", exception)
        } catch (exception: IllegalStateException) {
            throw IOException(exception.message ?: "failed to start recording", exception)
        } catch (exception: IllegalArgumentException) {
            throw IOException(exception.message ?: "failed to start recording", exception)
        }
    }

    override fun stopRecording(): String {
        val active = recording ?: throw IOException("no recording is in progress for this session")
        return try {
            active.stop()
            active.output.toString()
        } catch (exception: IllegalStateException) {
            throw IOException(exception.message ?: "failed to stop recording", exception)
        } finally {
            recording = null
        }
    }

    override fun close() {
        try {
            runCatching { recording?.stop() }
        } finally {
            recording = null
            delegate.close()
        }
    }
}

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.Recorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Insets
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaylandPortalWindowRecorderTest {

    @Test
    fun `applies queried GTK frame extents to the supplied screen-pixel region`() {
        // Mutter's window-source-type stream renders the picked window at stream coord
        // (extents.left, extents.top); cropping there with the window's pixel size gives a
        // window-aligned mp4. Verify the recorder forwards the stream-relative crop.
        val captured = StubRecorderCapture()
        val recorder =
            WaylandPortalWindowRecorder.createForInternalUse(
                delegate = captured.asWaylandPortalRecorder(),
                frameExtentsLookup = {
                    Insets(/* top= */ 25, /* left= */ 25, /* bottom= */ 25, /* right= */ 25)
                },
            )
        val output = tempMov()
        try {
            recorder.start(
                window = StubTitledWindow("MyApp"),
                region = Rectangle(305, 280, 480, 240),
                output = output,
                options = RecordingOptions(),
            )

            assertEquals(Rectangle(25, 25, 480, 240), captured.lastRegion)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `throws when xprop has no _GTK_FRAME_EXTENTS for the window`() {
        // Without WM frame extents we'd produce a silently-mis-cropped mp4 (close button
        // clipped, shadow leaking in, anchor offset wrong). #85 mandates a hard error
        // instead so the failure mode is visible.
        val captured = StubRecorderCapture()
        val recorder =
            WaylandPortalWindowRecorder.createForInternalUse(
                delegate = captured.asWaylandPortalRecorder(),
                frameExtentsLookup = { null },
            )
        val output = tempMov()
        try {
            val ex =
                assertFailsWith<IllegalStateException> {
                    recorder.start(
                        window = StubTitledWindow("MyApp"),
                        region = Rectangle(0, 0, 480, 240),
                        output = output,
                        options = RecordingOptions(),
                    )
                }

            assertTrue(
                ex.message?.contains("_GTK_FRAME_EXTENTS") == true,
                "Error must name the missing X11 property; got '${ex.message}'",
            )
            assertEquals(
                false,
                captured.startCalled,
                "Delegate must not be hit when extents are unknown",
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `throws when window title is null or blank`() {
        // No way to query xprop without a window name — emit a clear error rather than a
        // mysterious "couldn't find extents". Untitled embedded surfaces should go to the
        // region path (WaylandPortalRecorder), which AutoRecorder routes for null windows.
        val captured = StubRecorderCapture()
        val recorder =
            WaylandPortalWindowRecorder.createForInternalUse(
                delegate = captured.asWaylandPortalRecorder(),
                frameExtentsLookup = { error("should not be called") },
            )
        val output = tempMov()
        try {
            for (title in listOf<String?>(null, "", "   ")) {
                val ex =
                    assertFailsWith<IllegalStateException> {
                        recorder.start(
                            window = StubTitledWindow(title),
                            region = Rectangle(0, 0, 480, 240),
                            output = output,
                            options = RecordingOptions(),
                        )
                    }
                assertTrue(
                    ex.message?.contains("title") == true,
                    "Error must name the title issue; got '${ex.message}'",
                )
            }
            assertEquals(false, captured.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }
}

// Test stand-in for the WaylandPortalRecorder delegate. WaylandPortalWindowRecorder takes
// `Recorder` (interface) precisely so unit tests can drop in a stub like this without
// having to subclass the real recorder (which is final and constructs a helper-binary
// extractor at init time).
private class StubRecorderCapture : Recorder {
    var startCalled: Boolean = false
        private set

    var lastRegion: Rectangle? = null
        private set

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCalled = true
        lastRegion = region
        return NoopHandle(output)
    }

    fun asWaylandPortalRecorder(): Recorder = this
}

private class StubTitledWindow(override var title: String?) : TitledWindow {
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class NoopHandle(override val output: Path) : RecordingHandle {
    override val isStopped: Boolean = false

    override fun stop() = Unit
}

private fun tempMov(): Path = Files.createTempFile("spectre-wayland-window-recorder-test-", ".mov")

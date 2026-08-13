package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeFalse

class WaylandPortalStartCommandFactoryTest {
    @Test
    fun `monitor start includes AWT virtual desktop size`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val command =
            WaylandPortalRecorder.defaultStartCommandFactory(listOf(SourceType.MONITOR))(
                Rectangle(10, 20, 480, 240),
                Path.of("/tmp/out.mp4"),
                RecordingOptions(),
            )
        val screen = awtVirtualDesktopSize()
        assertEquals(screen, command.screenSize)
        assertEquals(Region(10, 20, 480, 240), command.region)
    }

    @Test
    fun `window start does not send AWT screen size`() {
        val command =
            WaylandPortalRecorder.defaultStartCommandFactory(listOf(SourceType.WINDOW))(
                Rectangle(25, 25, 480, 240),
                Path.of("/tmp/out.mp4"),
                RecordingOptions(),
            )
        assertNull(command.screenSize)
    }
}

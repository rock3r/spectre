package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class WaylandPortalWindowRecorderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `identical titles and geometry cannot identify which window a portal grant selected`() {
        val bounds = Rectangle(10, 20, 300, 200)
        val recorder: WaylandWindowSourceRecorder = WaylandPortalWindowRecorder()
        val output = directory.resolve("window.mp4")
        fun twin() =
            object : TitledWindow {
                override var title: String? = "Shared title"
                override val bounds = Rectangle(bounds)
            }
        for (window in listOf(twin(), twin())) {
            val failure =
                assertFailsWith<IllegalStateException> { recorder.start(window, bounds, output) }
            assertContains(failure.message.orEmpty(), "cannot verify")
            assertFalse(Files.exists(output))
        }
    }
}

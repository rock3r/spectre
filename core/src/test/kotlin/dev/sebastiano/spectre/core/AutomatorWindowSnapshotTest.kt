@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertSame
import org.junit.jupiter.api.condition.EnabledIf

@EnabledIf("liveAwtAvailable")
class AutomatorWindowSnapshotTest {

    @Test
    fun `tree window keeps the TrackedWindow identity from the snapshot`() {
        val frame = JFrame("snapshot")
        try {
            val tracked =
                TrackedWindow(
                    surfaceId = "popup:1",
                    window = frame,
                    composePanel = null,
                    isPopup = true,
                )
            val window =
                AutomatorWindow(windowIndex = 1, trackedWindow = tracked, nodes = emptyList())
            assertSame(tracked, window.trackedWindow)
        } finally {
            frame.dispose()
        }
    }

    companion object {
        @JvmStatic
        fun liveAwtAvailable(): Boolean =
            !GraphicsEnvironment.isHeadless() &&
                (!detectMacOs() || System.getProperty("spectre.test.liveAwt").toBoolean())
    }
}

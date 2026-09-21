package dev.sebastiano.spectre.recording.windows

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Locks the Mattone region-capture failure: `GraphicsCaptureItem.As<IInspectable>()` throws
 * `PlatformNotSupportedException` under this CsWinRT / .NET 8 combination, so the helper must
 * create the monitor item through `RoGetActivationFactory` instead.
 */
class WindowsGraphicsCaptureHelperSourceTest {

    @Test
    fun `monitor capture does not marshal GraphicsCaptureItem statics as IInspectable`() {
        val source = Files.readString(helperSource())
        assertTrue(
            !source.contains("As<IGraphicsCaptureItemStatics2>()"),
            "GraphicsCaptureItem.As<IGraphicsCaptureItemStatics2>() throws " +
                "PlatformNotSupportedException (marshalling as IInspectable)",
        )
        assertTrue(
            source.contains("RoGetActivationFactory"),
            "TryCreateFromDisplayId must go through RoGetActivationFactory",
        )
        assertTrue(
            "IGraphicsCaptureItemInterop" in source && "CreateForMonitor" in source,
            "CreateForMonitor remains the fallback when DisplayId capture fails",
        )
    }

    private fun helperSource(): Path {
        val candidates =
            listOf(
                Path.of("native/windows/Program.cs"),
                Path.of("recording/native/windows/Program.cs"),
            )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error(
                "spectre-window-capture Program.cs not found from ${Path.of("").toAbsolutePath()}"
            )
    }
}

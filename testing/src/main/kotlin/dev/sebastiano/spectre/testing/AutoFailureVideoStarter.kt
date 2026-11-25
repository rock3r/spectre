@file:OptIn(dev.sebastiano.spectre.core.InternalSpectreApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.nio.file.Path

/**
 * Starts a window-scoped recording for an in-process tracked AWT window.
 *
 * There is deliberately no region or display fallback: those modes can capture unrelated apps and
 * violate the window-capture invariant. The session reports the thrown reason when no video can be
 * produced.
 */
internal object AutoFailureVideoStarter : FailureVideoStarter {
    override fun start(output: Path, automator: ComposeAutomator): RecordingHandle? {
        automator.refreshWindows()
        val tracked =
            automator.windows.firstOrNull { !it.isPopup }
                ?: error("no tracked non-popup window is available for window-scoped recording")
        val identity = automator.windowIdentity(automator.windows.indexOf(tracked))
        val titled =
            when (val window = tracked.window) {
                is Frame -> window.asTitledWindow()
                is Dialog -> window.asTitledWindow()
                else ->
                    error(
                        "tracked ${window.javaClass.name} cannot be adapted for window-scoped recording"
                    )
            }
        val crop = identity.surfaceBoundsInWindow.takeIf { identity.cropRequired }?.let(::Rectangle)
        return AutoRecorder()
            .startWindow(
                window = titled,
                output = output,
                cropInWindow = crop,
                scaleX = identity.scaleX,
                scaleY = identity.scaleY,
            )
    }
}

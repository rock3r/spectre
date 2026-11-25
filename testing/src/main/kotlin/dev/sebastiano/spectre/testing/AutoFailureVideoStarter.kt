@file:OptIn(dev.sebastiano.spectre.core.InternalSpectreApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
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
    override fun start(
        output: Path,
        automator: ComposeAutomator,
        scope: FailureVideoScope,
    ): RecordingHandle? =
        when (scope) {
            FailureVideoScope.Window -> start(output, automator)
            is FailureVideoScope.Region -> AutoRecorder().startRegion(scope.bounds, output)
            is FailureVideoScope.Display -> {
                val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                require(scope.index in devices.indices) {
                    "display ${scope.index} is not available"
                }
                AutoRecorder().startRegion(devices[scope.index].defaultConfiguration.bounds, output)
            }
        }

    override fun start(output: Path, automator: ComposeAutomator): RecordingHandle? {
        automator.refreshWindows()
        val surfaceId =
            discoverWindow(automator) ?: error("no tracked non-popup window is available")
        return startWindow(output, automator, surfaceId)
    }

    fun discoverWindow(automator: ComposeAutomator): String? {
        automator.refreshWindows()
        return automator.windows.firstOrNull { !it.isPopup }?.surfaceId
    }

    fun startWindow(
        output: Path,
        automator: ComposeAutomator,
        surfaceId: String,
    ): RecordingHandle? {
        automator.refreshWindows()
        val index = automator.surfaceIds().indexOf(surfaceId)
        check(index >= 0) { "tracked window $surfaceId disappeared before recording started" }
        val tracked = automator.windows[index]
        check(!tracked.isPopup) { "tracked surface $surfaceId is a popup" }
        val identity = automator.windowIdentity(index)
        check(identity.surfaceId == surfaceId) {
            "window identity changed during recording discovery"
        }
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

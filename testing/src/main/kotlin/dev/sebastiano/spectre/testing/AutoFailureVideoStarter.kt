@file:OptIn(dev.sebastiano.spectre.core.InternalSpectreApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
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
                val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val devices = environment.screenDevices
                require(scope.index in devices.indices) {
                    "display ${scope.index} is not available"
                }
                val requested = devices[scope.index]
                val request =
                    displayRecordingRequest(
                        requested = requested,
                        devices = devices.toList(),
                        primary = environment.defaultScreenDevice,
                    )
                AutoRecorder()
                    .startRegion(
                        region = request.region,
                        output = output,
                        options = RecordingOptions(screenIndex = request.backendIndex),
                    )
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
                is Frame -> routeX11SafeWindow(window) { it }
                is Dialog -> routeX11SafeWindow(window) { it }
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

internal fun <T> routeX11SafeWindow(
    window: Window,
    windows: List<Window> = Window.getWindows().toList(),
    isLinuxX11: Boolean = isLinuxX11Session(),
    start: (TitledWindow) -> T,
): T {
    val titled =
        when (window) {
            is Frame -> {
                requireUnambiguousX11Title(window, window.title, windows, isLinuxX11)
                window.asTitledWindow()
            }
            is Dialog -> {
                requireUnambiguousX11Title(window, window.title, windows, isLinuxX11)
                window.asTitledWindow()
            }
            else -> error("unsupported titled window ${window.javaClass.name}")
        }
    return start(titled)
}

internal fun requireUnambiguousX11Title(
    requested: Window,
    title: String?,
    windows: List<Window> = Window.getWindows().toList(),
    isLinuxX11: Boolean = isLinuxX11Session(),
) {
    if (!isLinuxX11) return
    require(!title.isNullOrBlank()) {
        "Linux X11 failure-video capture cannot establish a blank-titled AWT window identity"
    }
    val ambiguous = windows.any { candidate ->
        candidate !== requested &&
            candidate.isDisplayable &&
            candidate.titleOrNull()?.contains(title) == true
    }
    check(!ambiguous) {
        "Linux X11 failure-video capture cannot establish the requested AWT window identity: " +
            "more than one displayable window matches title ${title.quote()}"
    }
}

internal fun macOsBackendDisplayIndex(
    requested: GraphicsDevice,
    devices: List<GraphicsDevice>,
    primary: GraphicsDevice,
    isMacOs: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
): Int {
    val awtIndex = devices.indexOf(requested)
    check(awtIndex >= 0) { "requested display is not in the AWT display list" }
    if (!isMacOs) return awtIndex
    check(
        devices.count { it.defaultConfiguration.bounds == requested.defaultConfiguration.bounds } ==
            1
    ) {
        "cannot map AWT display to ScreenCaptureKit: display bounds are ambiguous"
    }
    devices.forEach { device ->
        val transform = device.defaultConfiguration.defaultTransform
        check(transform.scaleX > 0.0 && transform.scaleY > 0.0) {
            "cannot map AWT display to ScreenCaptureKit: invalid display scale"
        }
    }
    val ordered =
        devices.sortedWith(
            compareBy<GraphicsDevice> { if (it === primary) 0 else 1 }
                .thenBy { it.defaultConfiguration.bounds.x }
                .thenBy { it.defaultConfiguration.bounds.y }
        )
    return ordered.indexOf(requested).also { index ->
        check(index >= 0) { "cannot map AWT display to ScreenCaptureKit" }
    }
}

internal data class DisplayRecordingRequest(val region: Rectangle, val backendIndex: Int)

internal fun displayRecordingRequest(
    requested: GraphicsDevice,
    devices: List<GraphicsDevice>,
    primary: GraphicsDevice,
    isMacOs: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
): DisplayRecordingRequest {
    val bounds = requested.defaultConfiguration.bounds
    return if (isMacOs) {
        DisplayRecordingRequest(
            region = Rectangle(0, 0, bounds.width, bounds.height),
            backendIndex = macOsBackendDisplayIndex(requested, devices, primary, isMacOs = true),
        )
    } else {
        DisplayRecordingRequest(
            region = Rectangle(bounds),
            backendIndex = RecordingOptions.DEFAULT_SCREEN_INDEX,
        )
    }
}

private fun Window.titleOrNull(): String? =
    when (this) {
        is Frame -> title
        is Dialog -> title
        else -> null
    }

private fun String.quote(): String = "\"$this\""

private fun isLinuxX11Session(): Boolean =
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
        System.getenv("WAYLAND_DISPLAY").isNullOrBlank() &&
        !System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)

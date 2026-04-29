@file:JvmName("WindowsHiDpiDiagnostic")

package dev.sebastiano.spectre.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.sebastiano.spectre.core.composeBoundsToAwtCenter
import dev.sebastiano.spectre.core.composeBoundsToAwtRectangle
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.IllegalComponentStateException
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual diagnostic for [dev.sebastiano.spectre.core.HiDpiMapper] on Windows DPI scaling. Run via
 * `./gradlew :sample-desktop:runWindowsHiDpiDiagnostic` — opens a JFrame containing a ComposePanel
 * with a "Reprint" button. Each click writes a snapshot to stdout with everything Spectre's
 * coordinate-mapping math depends on, sampled at that moment.
 *
 * What each snapshot captures:
 * - host: `os.name`, `os.version` (printed once at boot)
 * - per-monitor `defaultTransform.scaleX/Y` for every `GraphicsDevice` (boot + each click — the
 *   per-click reprint is what catches a re-enumerated device list after a DPI change)
 * - the JFrame's *current* `graphicsConfiguration.defaultTransform.scaleX/Y` (the monitor it's on
 *   right now, which may differ from the GC at construction time if the user dragged it)
 * - the embedded `ComposePanel`'s `locationOnScreen` + size in AWT pixels
 * - Compose's `LocalDensity.current.density` + `fontScale` (what the running composition sees)
 * - the `boundsInWindow` of a single tracked Compose box, plus the panel-relative AWT-pixel
 *   conversion that [composeBoundsToAwtCenter] / [composeBoundsToAwtRectangle] would produce
 *
 * The intent is for the operator to:
 * 1. Run on a 100% scale monitor, click Reprint, paste the block.
 * 2. Drag the window to a 125%/150%/200% monitor, click Reprint, paste again.
 * 3. Per-monitor mixed-DPI: drag from 100% → 200% (or vice versa), click Reprint mid-drag and again
 *    after settling. We want to know if the GC and density update synchronously or with a delay.
 *
 * No assertions, no test harness — this is a data-gathering tool for issue #21. The goal is to
 * discover whether the [dev.sebastiano.spectre.core.HiDpiMapper] formula needs a Windows-specific
 * branch or a per-axis tweak.
 */
fun main() {
    var exitCode = 0
    try {
        runDiagnostic()
    } catch (t: Throwable) {
        System.err.println("Diagnostic failed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runDiagnostic() {
    println("--- WindowsHiDpiDiagnostic boot ---")
    println(
        "host: os.name=\"${System.getProperty("os.name")}\", " +
            "os.version=\"${System.getProperty("os.version")}\""
    )
    printAllMonitors()

    val frameRef = AtomicReference<JFrame>()
    val state = DiagnosticState()
    SwingUtilities.invokeLater {
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)
                setContent { DiagnosticContent(state) }
            }
        val frame =
            JFrame("Spectre HiDPI diagnostic").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                layout = BorderLayout()
                add(composePanel, BorderLayout.CENTER)
                pack()
                setLocationRelativeTo(null)
                isVisible = true
            }
        state.frame = frame
        state.composePanel = composePanel
        frameRef.set(frame)
    }

    // Block the main thread until the diagnostic window closes. Without this, `main()` returns
    // immediately, the JVM tears down the EDT, and the diagnostic frame disappears before the
    // user can click anything.
    waitForFrameDisposed(frameRef)
    println("--- WindowsHiDpiDiagnostic done ---")
}

private fun waitForFrameDisposed(ref: AtomicReference<JFrame>) {
    while (true) {
        val frame = ref.get()
        if (frame != null && !frame.isDisplayable) return
        Thread.sleep(WAIT_POLL_MS)
    }
}

private fun printAllMonitors() {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val devices = ge.screenDevices
    println("monitors:")
    for ((idx, device) in devices.withIndex()) {
        val gc = device.defaultConfiguration
        val bounds = gc.bounds
        val xform = gc.defaultTransform
        val isDefault = device == ge.defaultScreenDevice
        println(
            "  [$idx] id=\"${device.iDstring}\" " +
                "default=$isDefault " +
                "bounds=(x=${bounds.x},y=${bounds.y},w=${bounds.width},h=${bounds.height}) " +
                "defaultTransform.scaleX=${xform.scaleX} " +
                "defaultTransform.scaleY=${xform.scaleY}"
        )
    }
}

private class DiagnosticState {
    var frame: JFrame? = null
    var composePanel: ComposePanel? = null
}

@Composable
private fun DiagnosticContent(state: DiagnosticState) {
    val density = LocalDensity.current
    var trackedBounds by remember { mutableStateOf(Rect.Zero) }
    var clickCount by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Spectre HiDPI diagnostic — click Reprint and paste stdout.")
        BasicText("LocalDensity.density = ${density.density}")
        BasicText("LocalDensity.fontScale = ${density.fontScale}")
        BasicText("trackedBounds (Compose px) = $trackedBounds")
        BasicText("clicks = $clickCount")
        // Clickable Box stands in for a Material button — :sample-desktop has Material3, but
        // the diagnostic stays foundation-only so it's portable to any module that pulls in
        // compose-foundation. `clickable` is applied *before* `padding` so the entire blue
        // background is the hit target — otherwise edge clicks land in the padding inset and
        // silently fail.
        Box(
            modifier =
                Modifier.clickable {
                        clickCount += 1
                        printSnapshot(state, density.density, density.fontScale, trackedBounds)
                    }
                    .background(Color(red = 0x33, green = 0x66, blue = 0xCC))
                    .padding(12.dp)
        ) {
            BasicText("Reprint")
        }
        Box(
            modifier =
                Modifier.background(Color(red = 0xCC, green = 0xEE, blue = 0xFF))
                    .fillMaxSize()
                    .onGloballyPositioned { coords -> trackedBounds = coords.boundsInWindow() }
        ) {
            BasicText("tracked box (boundsInWindow → printed)", modifier = Modifier.padding(8.dp))
        }
    }
}

private fun printSnapshot(
    state: DiagnosticState,
    density: Float,
    fontScale: Float,
    trackedBounds: Rect,
) {
    val frame = state.frame
    val panel = state.composePanel
    println()
    println("--- snapshot ---")
    if (frame == null || panel == null) {
        println("ERROR: frame or panel was null at snapshot time")
        return
    }
    printAllMonitors()
    val frameGc = (frame as Window).graphicsConfiguration
    val frameXform = frameGc?.defaultTransform
    val panelLoc =
        try {
            panel.locationOnScreen
        } catch (_: IllegalComponentStateException) {
            null
        }
    println(
        "frame.gc.bounds=(x=${frameGc?.bounds?.x},y=${frameGc?.bounds?.y}," +
            "w=${frameGc?.bounds?.width},h=${frameGc?.bounds?.height})"
    )
    println("frame.gc.defaultTransform.scaleX=${frameXform?.scaleX} scaleY=${frameXform?.scaleY}")
    println("panel.size=(w=${panel.width},h=${panel.height})")
    println(
        "panel.locationOnScreen=(x=${panelLoc?.x},y=${panelLoc?.y}) " +
            "(null means not currently displayable)"
    )
    println("compose.density=$density compose.fontScale=$fontScale")
    println(
        "trackedBounds(Compose px)=" +
            "(left=${trackedBounds.left},top=${trackedBounds.top}," +
            "right=${trackedBounds.right},bottom=${trackedBounds.bottom})"
    )
    if (frameXform != null && panelLoc != null) {
        val scaleX = frameXform.scaleX.toFloat()
        val scaleY = frameXform.scaleY.toFloat()
        val center =
            composeBoundsToAwtCenter(
                left = trackedBounds.left,
                top = trackedBounds.top,
                right = trackedBounds.right,
                bottom = trackedBounds.bottom,
                scaleX = scaleX,
                scaleY = scaleY,
                panelScreenX = panelLoc.x,
                panelScreenY = panelLoc.y,
            )
        val rect =
            composeBoundsToAwtRectangle(
                left = trackedBounds.left,
                top = trackedBounds.top,
                right = trackedBounds.right,
                bottom = trackedBounds.bottom,
                scaleX = scaleX,
                scaleY = scaleY,
                panelScreenX = panelLoc.x,
                panelScreenY = panelLoc.y,
            )
        println("HiDpiMapper.composeBoundsToAwtCenter=$center")
        println("HiDpiMapper.composeBoundsToAwtRectangle=$rect")
    } else {
        println("HiDpiMapper.* skipped (gc xform or panel location was null)")
    }
    println("--- /snapshot ---")
}

private const val PANEL_WIDTH = 600
private const val PANEL_HEIGHT = 360
private const val WAIT_POLL_MS = 200L

package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.HostPlatform
import java.awt.EventQueue
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Native capture identity for a host that has no title: a Swing `JWindow` or a plain AWT [Window].
 *
 * The native helpers find a titled window by its title. AWT has no title for these hosts, and
 * cannot set one, so a [TitleDiscriminator] cannot work. Instead the helpers select the window of
 * the owner process whose on-screen frame equals this one:
 * - macOS matches [logicalFrame] in points (the same space as AWT bounds).
 * - Windows and Linux X11 match [deviceFrame] in device pixels.
 *
 * [title] is always null and cannot be set. Callers must check for this type before they reach for
 * a title.
 */
internal class FramedWindow(
    val logicalFrame: Rectangle,
    val deviceFrame: Rectangle,
    override val awtWindow: Window? = null,
) : TitledWindow, TitleDiscriminationAware, AwtWindowBacked {

    override var title: String?
        get() = null
        set(_) {
            throw UnsupportedOperationException(
                "An untitled window host is selected by its frame and has no title to set"
            )
        }

    override val bounds: Rectangle
        get() = Rectangle(logicalFrame)

    override val requiresTitleDiscriminator: Boolean = false

    override fun toString(): String =
        "FramedWindow(logicalFrame=$logicalFrame, deviceFrame=$deviceFrame)"
}

/**
 * Snapshots this window's frame for a native capture. Reads AWT state on the EDT.
 *
 * The frame is fixed at this moment. If the window moves before the helper looks for it, the helper
 * finds no match and the capture fails instead of returning another window.
 *
 * With [clientAreaOnly], the frame excludes the window's insets (its window-manager decorations).
 * The Linux X11 helper matches client windows, so a decorated window needs this there.
 */
internal fun Window.asFramedWindow(
    isWindows: Boolean = HostPlatform.isWindows(),
    clientAreaOnly: Boolean = false,
): FramedWindow {
    val result = AtomicReference<FramedWindow>()
    val read = {
        val logical = Rectangle(bounds)
        if (clientAreaOnly) {
            val insets = insets
            logical.setBounds(
                logical.x + insets.left,
                logical.y + insets.top,
                logical.width - insets.left - insets.right,
                logical.height - insets.top - insets.bottom,
            )
        }
        val configuration = graphicsConfiguration
        val transform = configuration.defaultTransform
        val screen = configuration.bounds
        result.set(
            FramedWindow(
                logicalFrame = logical,
                deviceFrame =
                    deviceFrameFor(
                        logical = logical,
                        scaleX = transform.scaleX,
                        scaleY = transform.scaleY,
                        screenOrigin = Point(screen.x, screen.y),
                        scalesFromScreenOrigin = isWindows,
                    ),
                awtWindow = this,
            )
        )
    }
    if (EventQueue.isDispatchThread()) read() else EventQueue.invokeAndWait(read)
    return result.get()
}

/**
 * Converts AWT logical bounds to the device pixels a native helper sees.
 *
 * On Windows, AWT keeps each monitor's origin in device pixels and scales only the offset inside
 * the monitor, so [scalesFromScreenOrigin] must be true there. On Linux X11, AWT scales every
 * coordinate by the same factor. Each edge is rounded separately so the width and height match the
 * native window edges.
 */
internal fun deviceFrameFor(
    logical: Rectangle,
    scaleX: Double,
    scaleY: Double,
    screenOrigin: Point,
    scalesFromScreenOrigin: Boolean,
): Rectangle {
    val originX = if (scalesFromScreenOrigin) screenOrigin.x else 0
    val originY = if (scalesFromScreenOrigin) screenOrigin.y else 0
    fun scale(value: Int, origin: Int, factor: Double): Int =
        origin + ((value - origin) * factor).roundToInt()
    val left = scale(logical.x, originX, scaleX)
    val top = scale(logical.y, originY, scaleY)
    val right = scale(logical.x + logical.width, originX, scaleX)
    val bottom = scale(logical.y + logical.height, originY, scaleY)
    return Rectangle(left, top, right - left, bottom - top)
}

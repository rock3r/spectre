package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException

internal fun detectLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")

internal fun defaultRealOsRobotAdapter(
    isLinux: Boolean = detectLinux(),
    wayland: Boolean = isWaylandSession(),
    waylandAdapter: () -> RobotAdapter? = ::loadWaylandRobotAdapter,
    awtAdapter: () -> RobotAdapter = { AwtRobotAdapter() },
): RobotAdapter {
    if (isLinux && wayland) {
        return waylandAdapter() ?: MissingWaylandHelperAdapter
    }
    return awtAdapter()
}

internal const val MISSING_WAYLAND_HELPER_MESSAGE: String =
    "Linux Wayland real OS input and capture require spectre-wayland-helper " +
        "(spectre-recording-linux on the runtime classpath). java.awt.Robot must not " +
        "open its own ScreenCast/RemoteDesktop portal session."

internal object MissingWaylandHelperAdapter : RobotAdapter {
    override val autoDelayMs: Int = 0
    override val requiresOffEdt: Boolean = true
    override val deliversRealOsInput: Boolean = true
    override val shouldDrainAfterClipboardPaste: Boolean
        get() = true

    override fun mouseMove(x: Int, y: Int): Unit = failMissing()

    override fun requireInputSupported(): Unit = failMissing()

    override fun mousePress(buttons: Int): Unit = failMissing()

    override fun mouseRelease(buttons: Int): Unit = failMissing()

    override fun keyPress(keyCode: Int): Unit = failMissing()

    override fun keyRelease(keyCode: Int): Unit = failMissing()

    override fun mouseWheel(wheelClicks: Int): Unit = failMissing()

    override fun waitForIdle() = Unit

    override fun createScreenCapture(region: Rectangle): BufferedImage = failMissing()

    private fun failMissing(): Nothing = error(MISSING_WAYLAND_HELPER_MESSAGE)
}

private const val WAYLAND_OS_BRIDGE: String =
    "dev.sebastiano.spectre.recording.portal.WaylandOsBridge"

internal fun resolveWaylandRobotAdapter(
    recordingBridge: RobotAdapter?,
    liveSeat: RobotAdapter?,
): RobotAdapter? = recordingBridge ?: liveSeat

internal fun loadWaylandRobotAdapter(
    classLoader: ClassLoader = RobotDriver::class.java.classLoader
): RobotAdapter? =
    resolveWaylandRobotAdapter(
        recordingBridge = loadRecordingWaylandBridge(classLoader),
        liveSeat = WaylandSeatSocketAdapter.takeIfLive(),
    )

private fun loadRecordingWaylandBridge(classLoader: ClassLoader): RobotAdapter? {
    val bridge =
        try {
            Class.forName(WAYLAND_OS_BRIDGE, false, classLoader)
        } catch (_: ClassNotFoundException) {
            return null
        }
    return WaylandBridgeRobotAdapter(bridge)
}

private class WaylandBridgeRobotAdapter(private val bridge: Class<*>) : RobotAdapter {
    override val autoDelayMs: Int = 0
    override val requiresOffEdt: Boolean = true
    override val deliversRealOsInput: Boolean = true
    override val shouldDrainAfterClipboardPaste: Boolean
        get() = true

    override fun mouseMove(x: Int, y: Int) {
        invoke("mouseMove", x, y)
    }

    override fun mousePress(buttons: Int) {
        invoke("mousePress", buttons)
    }

    override fun mouseRelease(buttons: Int) {
        invoke("mouseRelease", buttons)
    }

    override fun keyPress(keyCode: Int) {
        invoke("keyPress", keyCode)
    }

    override fun keyRelease(keyCode: Int) {
        invoke("keyRelease", keyCode)
    }

    override fun mouseWheel(wheelClicks: Int) {
        invoke("mouseWheel", wheelClicks)
    }

    override fun waitForIdle() = Unit

    override fun createScreenCapture(region: Rectangle): BufferedImage {
        val method = bridge.getMethod("createScreenCapture", Rectangle::class.java)
        try {
            val captured = method.invoke(null, region) as BufferedImage
            return screenshotToLogicalSize(captured, region)
        } catch (e: InvocationTargetException) {
            throw unwrapBridge(e)
        }
    }

    override fun createDeviceScaleScreenCapture(region: Rectangle): BufferedImage {
        val method = bridge.getMethod("createScreenCapture", Rectangle::class.java)
        try {
            return method.invoke(null, region) as BufferedImage
        } catch (e: InvocationTargetException) {
            throw unwrapBridge(e)
        }
    }

    private fun invoke(name: String, arg: Int) {
        val method = bridge.getMethod(name, Integer.TYPE)
        try {
            method.invoke(null, arg)
        } catch (e: InvocationTargetException) {
            throw unwrapBridge(e)
        }
    }

    private fun invoke(name: String, first: Int, second: Int) {
        val method = bridge.getMethod(name, Integer.TYPE, Integer.TYPE)
        try {
            method.invoke(null, first, second)
        } catch (e: InvocationTargetException) {
            throw unwrapBridge(e)
        }
    }

    private fun unwrapBridge(e: InvocationTargetException): RuntimeException {
        val cause = e.cause ?: e
        return when (cause) {
            is RuntimeException -> cause
            else -> IllegalStateException("Wayland portal helper failed", cause)
        }
    }
}

internal fun screenshotToLogicalSize(image: BufferedImage, region: Rectangle): BufferedImage {
    if (image.width == region.width && image.height == region.height) return image
    val scaled = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    graphics.drawImage(image, 0, 0, region.width, region.height, null)
    graphics.dispose()
    return scaled
}

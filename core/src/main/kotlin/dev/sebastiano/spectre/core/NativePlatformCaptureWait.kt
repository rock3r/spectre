@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.lang.reflect.InvocationTargetException

/**
 * Starts an isolated wait-scoped GStreamer probe slot. Returns `0` when the recording bridge or
 * method is absent (older jars). Call [endNativePlatformCaptureWait] when the wait finishes.
 */
internal fun beginNativePlatformCaptureWait(classLoader: ClassLoader): Long {
    val bridge =
        try {
            Class.forName(NATIVE_WINDOW_CAPTURE_BRIDGE, false, classLoader)
        } catch (_: ClassNotFoundException) {
            return 0L
        }
    val begin =
        try {
            bridge.getMethod("beginPlatformCaptureWait")
        } catch (_: NoSuchMethodException) {
            return 0L
        }
    return try {
        begin.invoke(null) as? Long ?: 0L
    } catch (_: InvocationTargetException) {
        0L
    } catch (_: ReflectiveOperationException) {
        0L
    }
}

internal fun endNativePlatformCaptureWait(classLoader: ClassLoader, waitId: Long) {
    if (waitId == 0L) return
    val bridge =
        try {
            Class.forName(NATIVE_WINDOW_CAPTURE_BRIDGE, false, classLoader)
        } catch (_: ClassNotFoundException) {
            return
        }
    val end =
        try {
            bridge.getMethod("endPlatformCaptureWait", Long::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            return
        }
    try {
        end.invoke(null, waitId)
    } catch (_: InvocationTargetException) {
        return
    } catch (_: ReflectiveOperationException) {
        return
    }
}

internal class NativeCaptureWaitScope(
    private val classLoader: ClassLoader,
    private val allowsPlatformCapture: Boolean,
) : AutoCloseable {
    private val waitId = beginNativePlatformCaptureWait(classLoader)

    fun isAvailable(): Boolean =
        isNativeWindowCaptureAvailable(
            classLoader = classLoader,
            allowsPlatformCapture = allowsPlatformCapture,
            platformCaptureUsable = { isNativePlatformCaptureUsable(it, waitId) },
        )

    override fun close() {
        endNativePlatformCaptureWait(classLoader, waitId)
    }
}

internal suspend fun <T> withNativeCaptureWaitScope(
    classLoader: ClassLoader,
    allowsPlatformCapture: Boolean,
    block: suspend (NativeCaptureWaitScope) -> T,
): T {
    val scope = NativeCaptureWaitScope(classLoader, allowsPlatformCapture)
    try {
        return block(scope)
    } finally {
        scope.close()
    }
}

/**
 * Asks the optional recording bridge whether the platform helper can actually capture.
 *
 * Class presence is not enough on Linux: `spectre-recording` can load while `gst-launch-1.0` is
 * missing (#503). Older recording jars without the probe method keep the 0.6.0 "class present"
 * behaviour.
 */
internal fun isNativePlatformCaptureUsable(classLoader: ClassLoader, waitId: Long = 0L): Boolean {
    val bridge =
        try {
            Class.forName(NATIVE_WINDOW_CAPTURE_BRIDGE, false, classLoader)
        } catch (_: ClassNotFoundException) {
            return false
        }
    if (waitId != 0L) {
        val forWait =
            try {
                bridge.getMethod("isPlatformCaptureUsableForWait", Long::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                null
            }
        if (forWait != null) {
            return try {
                forWait.invoke(null, waitId) as? Boolean ?: false
            } catch (_: InvocationTargetException) {
                false
            } catch (_: ReflectiveOperationException) {
                false
            }
        }
    }
    val probe =
        try {
            bridge.getMethod("isPlatformCaptureUsable")
        } catch (_: NoSuchMethodException) {
            return true
        }
    return try {
        probe.invoke(null) as? Boolean ?: false
    } catch (_: InvocationTargetException) {
        false
    } catch (_: ReflectiveOperationException) {
        false
    }
}

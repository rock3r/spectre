package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Optional runtime bridge for core's window-scoped still capture route.
 *
 * This type intentionally lives in `:recording`: injected core loads it only when recording is
 * already available to the host, preserving the inject payload's dependency boundary.
 */
internal object NativeWindowCaptureBridge {
    private val screenshotter: AutoScreenshotter by lazy(::AutoScreenshotter)
    private val captureLocks = WeakHashMap<Window, ReentrantLock>()
    @Volatile private var cachedPlatformCaptureUsable: Boolean? = null
    private val waitScopedProbes = WaitScopedProbeTable()
    private val platformCaptureUsableLock = Any()

    /**
     * True when the host can actually run a native still (not merely load this class).
     *
     * Linux stills spawn `gst-launch-1.0` via the bundled helper. If that binary is missing,
     * visual- idle treats native capture as unavailable and falls back to Robot region sampling
     * (#503). An interrupted or timed-out probe is not globally cached: a later `waitForVisualIdle`
     * may retry. The inconclusive Robot decision is keyed to [waitId] so overlapping waits cannot
     * clear each other's state.
     */
    @JvmStatic
    @JvmName("isPlatformCaptureUsable")
    internal fun isPlatformCaptureUsable(): Boolean = isPlatformCaptureUsable(waitId = 0L)

    @JvmStatic
    @JvmName("isPlatformCaptureUsableForWait")
    internal fun isPlatformCaptureUsable(waitId: Long): Boolean {
        cachedPlatformCaptureUsable?.let {
            return it
        }
        return synchronized(platformCaptureUsableLock) {
            cachedPlatformCaptureUsable?.let {
                return@synchronized it
            }
            val remembered =
                waitScopedProbes.remember(waitId, cachedPlatformCaptureUsable) {
                    computePlatformCaptureUsable()
                }
            cachedPlatformCaptureUsable = remembered.cache
            remembered.usableNow
        }
    }

    /** Starts an isolated wait-scoped probe slot. A later overlapping wait gets a different id. */
    @JvmStatic
    @JvmName("beginPlatformCaptureWait")
    internal fun beginPlatformCaptureWait(): Long = waitScopedProbes.begin()

    @JvmStatic
    @JvmName("endPlatformCaptureWait")
    internal fun endPlatformCaptureWait(waitId: Long) {
        waitScopedProbes.end(waitId)
    }

    internal fun computePlatformCaptureUsable(
        isLinux: () -> Boolean = HostPlatform::isLinux,
        gstLaunchAvailable: () -> Boolean? = { LinuxCaptureDependencies.isGstLaunchAvailable() },
    ): Boolean? = if (isLinux()) gstLaunchAvailable() else true

    @JvmStatic
    @JvmName("captureWindow")
    internal fun captureWindow(window: Window): BufferedImage =
        withCaptureLock(window) {
            val titledWindow =
                when (window) {
                    is Frame -> window.asTitledWindow()
                    is Dialog -> window.asTitledWindow()
                    else ->
                        throw UnsupportedOperationException(
                            "Native window capture requires a Frame or Dialog host"
                        )
                }
            screenshotter.captureWindow(titledWindow)
        }

    internal fun <T> withCaptureLock(window: Window, action: () -> T): T {
        val lock = captureLockFor(window)
        if (EventQueue.isDispatchThread()) {
            check(lock.tryLock()) {
                "Native window capture is unavailable while another capture for this window is in progress"
            }
        } else {
            lock.lock()
        }
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    internal fun captureLockFor(window: Window): ReentrantLock =
        synchronized(captureLocks) { captureLocks.getOrPut(window, ::ReentrantLock) }
}

/**
 * [cache] is the JVM-lifetime result. [waitScoped] is the Robot decision for this wait when [cache]
 * is null so later frames do not start another probe; a later wait clears it and retries.
 */
internal data class CompletedProbe(
    val usableNow: Boolean,
    val cache: Boolean?,
    val waitScoped: Boolean?,
)

internal fun rememberCompletedProbe(
    cached: Boolean?,
    waitScoped: Boolean? = null,
    compute: () -> Boolean?,
): CompletedProbe {
    if (cached != null)
        return CompletedProbe(usableNow = cached, cache = cached, waitScoped = waitScoped)
    if (waitScoped != null) {
        return CompletedProbe(usableNow = waitScoped, cache = null, waitScoped = waitScoped)
    }
    val computed = compute()
    return CompletedProbe(
        usableNow = computed ?: false,
        cache = computed,
        waitScoped = if (computed == null) false else null,
    )
}

/** Per-wait inconclusive Robot decisions. Ending one wait must not drop another wait's slot. */
internal class WaitScopedProbeTable {
    private val nextId = AtomicLong()
    private val scoped = ConcurrentHashMap<Long, Boolean>()

    fun begin(): Long = nextId.incrementAndGet()

    fun end(waitId: Long) {
        if (waitId != 0L) scoped.remove(waitId)
    }

    fun remember(waitId: Long, cached: Boolean?, compute: () -> Boolean?): CompletedProbe {
        val waitScoped = if (waitId == 0L) null else scoped[waitId]
        val remembered = rememberCompletedProbe(cached, waitScoped, compute)
        if (waitId != 0L) {
            val scopedResult = remembered.waitScoped
            if (scopedResult == null) scoped.remove(waitId) else scoped[waitId] = scopedResult
        }
        return remembered
    }
}

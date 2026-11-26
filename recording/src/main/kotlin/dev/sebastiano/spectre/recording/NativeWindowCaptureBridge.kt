package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.asFramedWindow
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.geom.Area
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt

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
     * A missing GStreamer executable makes native capture unavailable. The visual-idle caller
     * decides whether its capture policy permits region sampling. An interrupted or timed-out probe
     * is scoped to [waitId] so a later wait can retry. Queued waits share its timeout.
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
        linuxHelperBundled: () -> Boolean = ::isLinuxNativeHelperBundled,
    ): Boolean? {
        if (!isLinux()) return true
        if (!linuxHelperBundled()) return false
        return gstLaunchAvailable()
    }

    @JvmStatic
    @JvmName("captureWindow")
    internal fun captureWindow(window: Window): BufferedImage =
        withCaptureLock(window) { screenshotter.captureWindow(captureTargetFor(window)) }

    /** Legacy explicit-region entry point retained for older core/recording pairings. */
    @JvmStatic
    @JvmName("captureScreenRegion")
    internal fun captureScreenRegion(region: Rectangle): BufferedImage =
        captureScreenRegionOrDesktop(region)

    /** Nullable full-desktop entry point loaded reflectively by current core. */
    @JvmStatic
    @JvmName("captureScreenRegionOrDesktop")
    internal fun captureScreenRegionOrDesktop(region: Rectangle?): BufferedImage {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice
        val devices =
            environment.screenDevices.sortedWith(
                compareBy(
                    { it !== primary },
                    { it.defaultConfiguration.bounds.x },
                    { it.defaultConfiguration.bounds.y },
                )
            )
        val screenshotter = ScreenCaptureKitScreenshotter()
        val displays =
            uniqueRegionDisplays(
                devices.map { device ->
                    val configuration = device.defaultConfiguration
                    RegionDisplay(
                        captureIndex = 0,
                        bounds = Rectangle(configuration.bounds),
                        scaleX = configuration.defaultTransform.scaleX,
                        scaleY = configuration.defaultTransform.scaleY,
                    )
                }
            )
        val capture: (Rectangle, Int) -> BufferedImage = { local, index ->
            screenshotter.captureRegion(local, index)
        }
        return composeScreenRegionOrDesktop(region, displays, capture)
    }

    /**
     * How the native helper finds [window]. A [Frame] or [Dialog] is found by its title. Any other
     * host (a Swing `JWindow`, a plain AWT [Window]) has no title, so it is found by its on-screen
     * frame instead (#605).
     *
     * A [Frame] or [Dialog] whose title another window also has is found by its frame too, where
     * [selectsSharedTitlesByFrame] (#611): the title alone would make the helper guess. The Wayland
     * portal cannot select by frame. The Linux X11 helper matches client windows, which exclude the
     * window-manager decorations, so there [selectsClientArea] narrows the frame to the window's
     * client area.
     */
    internal fun captureTargetFor(
        window: Window,
        selectsSharedTitlesByFrame: Boolean = !HostPlatform.isWayland(),
        selectsClientArea: Boolean = HostPlatform.isLinux(),
    ): TitledWindow {
        check(window.isShowing) { "Native window capture target is not showing" }
        val title =
            when (window) {
                is Frame -> window.title
                is Dialog -> window.title
                else -> return window.asFramedWindow()
            }
        if (selectsSharedTitlesByFrame && (title.isNullOrBlank() || isSharedTitle(window, title))) {
            return window.asFramedWindow(clientAreaOnly = selectsClientArea)
        }
        return when (window) {
            is Frame -> window.asTitledWindow()
            else -> (window as Dialog).asTitledWindow()
        }
    }

    internal fun isSharedTitle(
        window: Window,
        title: String?,
        matchesSubstring: Boolean = HostPlatform.isMacOs(),
    ): Boolean {
        if (title.isNullOrBlank()) return false
        return Window.getWindows().any { other ->
            val otherTitle = other.titleOrNull()
            other !== window &&
                other.isShowing &&
                (if (matchesSubstring) otherTitle?.contains(title) == true else otherTitle == title)
        }
    }

    private fun Window.titleOrNull(): String? =
        when (this) {
            is Frame -> title
            is Dialog -> title
            else -> null
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

internal data class RegionDisplay(
    val captureIndex: Int,
    val bounds: Rectangle,
    val scaleX: Double,
    val scaleY: Double,
)

/** Matches the native helper's mirrored-frame de-duplication and resulting display indices. */
internal fun uniqueRegionDisplays(displays: List<RegionDisplay>): List<RegionDisplay> =
    displays
        .distinctBy { it.bounds }
        .mapIndexed { index, display -> display.copy(captureIndex = index) }

/** Selects one output scale for both explicit regions and the full desktop by request centre. */
internal fun composeScreenRegionOrDesktop(
    region: Rectangle?,
    displays: List<RegionDisplay>,
    capture: (Rectangle, Int) -> BufferedImage,
): BufferedImage {
    require(displays.isNotEmpty()) { "macOS ScreenCaptureKit found no displays to capture" }
    val captureRegion = region ?: displays.map { it.bounds }.reduce(Rectangle::union)
    val center =
        java.awt.Point(
            captureRegion.x + captureRegion.width / 2,
            captureRegion.y + captureRegion.height / 2,
        )
    val target = displays.firstOrNull { it.bounds.contains(center) } ?: displays.first()
    return composeScreenRegion(
        captureRegion,
        displays,
        target.scaleX,
        target.scaleY,
        requireCompleteCoverage = region != null,
        capture = capture,
    )
}

/**
 * Captures every real display and uses transparent black for points in their bounding rectangle
 * that belong to no display. Padding is exclusive to the null/full-desktop contract: an explicit
 * rectangle with identical bounds still goes through strict coverage validation below.
 */
internal fun composeFullDesktop(
    displays: List<RegionDisplay>,
    outputScaleX: Double,
    outputScaleY: Double,
    capture: (Rectangle, Int) -> BufferedImage,
): BufferedImage {
    require(displays.isNotEmpty()) { "macOS ScreenCaptureKit found no displays to capture" }
    val bounds = displays.map { it.bounds }.reduce(Rectangle::union)
    return composeScreenRegion(
        bounds,
        displays,
        outputScaleX,
        outputScaleY,
        requireCompleteCoverage = false,
        capture = capture,
    )
}

/** Combines display-local SCK captures only after proving that they cover the whole request. */
internal fun composeScreenRegion(
    region: Rectangle,
    displays: List<RegionDisplay>,
    outputScaleX: Double,
    outputScaleY: Double,
    requireCompleteCoverage: Boolean = true,
    capture: (Rectangle, Int) -> BufferedImage,
): BufferedImage {
    require(region.width > 0 && region.height > 0) { "Capture region must be non-empty: $region" }
    val parts = displays.mapNotNull { display ->
        val intersection = region.intersection(display.bounds)
        if (intersection.isEmpty) null else display to intersection
    }
    val uncovered = Area(region)
    parts.forEach { (_, part) -> uncovered.subtract(Area(part)) }
    require(!requireCompleteCoverage || uncovered.isEmpty) {
        "macOS ScreenCaptureKit displays do not cover requested region $region; " +
            "uncovered rectangle ${uncovered.bounds}"
    }

    val output =
        BufferedImage(
            scaledDimension(region.width, outputScaleX),
            scaledDimension(region.height, outputScaleY),
            BufferedImage.TYPE_INT_ARGB,
        )
    val graphics = output.createGraphics()
    try {
        parts.forEach { (display, part) ->
            val local =
                Rectangle(
                    part.x - display.bounds.x,
                    part.y - display.bounds.y,
                    part.width,
                    part.height,
                )
            val image = capture(local, display.captureIndex)
            val expectedWidth = scaledDimension(part.width, display.scaleX)
            val expectedHeight = scaledDimension(part.height, display.scaleY)
            require(image.width == expectedWidth && image.height == expectedHeight) {
                "macOS ScreenCaptureKit returned ${image.width}x${image.height} pixels for " +
                    "display-local rectangle $local (desktop rectangle $part); expected " +
                    "${expectedWidth}x${expectedHeight}"
            }
            graphics.drawImage(
                image,
                scaledDimension(part.x - region.x, outputScaleX),
                scaledDimension(part.y - region.y, outputScaleY),
                scaledDimension(part.width, outputScaleX),
                scaledDimension(part.height, outputScaleY),
                null,
            )
        }
    } finally {
        graphics.dispose()
    }
    return output
}

/** SCK and AWT may differ by at most half a pixel because both round point dimensions. */
private fun scaledDimension(points: Int, scale: Double): Int =
    (points * scale).roundToInt().coerceAtLeast(if (points == 0) 0 else 1)

/**
 * [cache] is the JVM-lifetime result. [waitScoped] is the native availability result for this wait
 * when [cache] is null so later frames do not start another probe; a later wait clears it and
 * retries.
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

/**
 * Per-wait inconclusive native availability results. Ending one wait must not drop another wait's
 * slot.
 */
internal class WaitScopedProbeTable {
    private val nextId = AtomicLong()
    private val tableLock = Any()
    private val open = ConcurrentHashMap.newKeySet<Long>()
    private val scoped = ConcurrentHashMap<Long, Boolean>()

    fun begin(): Long {
        val waitId = nextId.incrementAndGet()
        synchronized(tableLock) { open.add(waitId) }
        return waitId
    }

    fun end(waitId: Long) {
        if (waitId == 0L) return
        synchronized(tableLock) {
            open.remove(waitId)
            scoped.remove(waitId)
        }
    }

    fun remember(waitId: Long, cached: Boolean?, compute: () -> Boolean?): CompletedProbe {
        val waitScoped = if (waitId == 0L) null else scoped[waitId]
        val remembered = rememberCompletedProbe(cached, waitScoped, compute)
        if (waitId == 0L) return remembered
        synchronized(tableLock) {
            if (!open.contains(waitId)) return@synchronized
            val scopedResult = remembered.waitScoped
            if (scopedResult == null) {
                scoped.remove(waitId)
            } else {
                scoped[waitId] = scopedResult
                if (waitScoped == null && remembered.cache == null) {
                    shareInconclusiveWithOpenWaits(waitId, scopedResult)
                }
            }
        }
        return remembered
    }

    private fun shareInconclusiveWithOpenWaits(sourceWaitId: Long, result: Boolean) {
        for (id in open) {
            if (id != sourceWaitId) scoped.putIfAbsent(id, result)
        }
    }
}

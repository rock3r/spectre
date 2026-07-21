@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle

/**
 * Native identity and geometry of one tracked Compose surface, for out-of-process recorders.
 *
 * Coordinate spaces (constraint #4):
 * - **Screen AWT pixels** — [windowBoundsOnScreen] and [surfaceBoundsOnScreen] use the same space
 *   as `Component.locationOnScreen` / `Robot` (physical AWT pixels after the platform transform).
 * - **Window-relative AWT pixels** — [surfaceBoundsInWindow] is the Compose surface origin/size
 *   relative to [windowBoundsOnScreen]'s top-left (still AWT pixels, not Compose logical units).
 * - **Scale** — [scaleX]/[scaleY] come from `GraphicsConfiguration.defaultTransform` (1.0 on
 *   non-HiDPI, 2.0 on a typical Retina display).
 *
 * When [cropRequired] is true, [nativeHandle] identifies the **host top-level** window (spike
 * constraint #5): capture that window and crop to [surfaceBoundsInWindow] (or the screen-space
 * surface rect, depending on the backend).
 */
@InternalSpectreApi
public data class WindowIdentitySnapshot(
    public val index: Int,
    public val surfaceId: String,
    public val title: String?,
    public val isPopup: Boolean,
    /**
     * Platform-native window id for capture targeting, or `null` if unrealized / unresolvable.
     *
     * - Windows: HWND as unsigned 64-bit bits in a signed [Long]
     * - macOS: `NSWindow*` pointer bits
     * - X11: Window XID
     */
    public val nativeHandle: Long?,
    /**
     * `true` when [nativeHandle] is the host top-level window and capture must crop to the Compose
     * surface. `false` when the handle already targets the surface-sized window (or no crop is
     * needed because surface fills the window).
     */
    public val cropRequired: Boolean,
    /** Outer top-level window bounds in screen AWT pixels. */
    public val windowBoundsOnScreen: Rectangle,
    /** Compose surface bounds in screen AWT pixels. */
    public val surfaceBoundsOnScreen: Rectangle,
    /**
     * Compose surface origin and size relative to [windowBoundsOnScreen]'s top-left, in AWT pixels.
     * The natural crop rectangle for window+crop backends.
     */
    public val surfaceBoundsInWindow: Rectangle,
    /** `GraphicsConfiguration.defaultTransform.scaleX`. */
    public val scaleX: Double,
    /** `GraphicsConfiguration.defaultTransform.scaleY`. */
    public val scaleY: Double,
)

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Insets
import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Recorder surface for the Linux Wayland window-source recording path (#85). Implementations take a
 * [TitledWindow] (so the recorder can query its `_GTK_FRAME_EXTENTS` and translate the supplied
 * screen-pixel bounds into a stream-relative crop) plus the region (the window's bounds at
 * recording start). Distinct from [dev.sebastiano.spectre.recording.Recorder] because it needs the
 * window, and from [dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder] because it
 * also needs the region (the macOS / Windows window-targeted backends derive bounds from the window
 * itself via OS APIs and don't take a region). The production implementation is
 * [WaylandPortalWindowRecorder].
 */
interface WaylandWindowSourceRecorder {
    fun start(
        window: TitledWindow,
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle
}

/**
 * Window-targeted variant of [WaylandPortalRecorder] for Linux Wayland sessions. Routed to by
 * [dev.sebastiano.spectre.recording.AutoRecorder] when the caller passes a non-null `TitledWindow`
 * on a Wayland host (#85).
 *
 * Internally just a [WaylandPortalRecorder] configured with `sourceTypes = [SourceType.WINDOW]`.
 * The portal dialog asks the user to pick a specific window (vs a monitor for the region path), and
 * the compositor returns a PipeWire stream that contains **only the picked window's pixels** —
 * desktop wallpaper, occluding apps, and other windows do not leak into the recording the way they
 * would under MONITOR + region capture.
 *
 * **Stream-relative crop discovery via `_GTK_FRAME_EXTENTS`.** Mutter renders the picked window
 * into the stream including its WM-imposed invisible-shadow extents (typically 25 px on all sides
 * for client-side-decorated GTK windows on GNOME), but AWT's `frame.getBounds()` reports only the
 * inner window without those extents. A naive crop of `(0, 0, bounds.width, bounds.height)`
 * therefore misses ~25 px of the rendered window on the right and bottom — visible as the
 * close-button icon getting clipped off the title bar. The fix is to query the X11
 * `_GTK_FRAME_EXTENTS(CARDINAL) = L, R, T, B` property on the JFrame's XWayland window (set by
 * Mutter for every WM-decorated client), then crop at `(L, T, bounds.width, bounds.height)` — gives
 * a pixel-aligned window capture with no shadow padding visible. **Throws `IllegalStateException`
 * rather than degrading silently** if the property can't be queried; see [start]'s `Throws` clause
 * for the conditions and recovery options.
 *
 * Trade-offs vs [WaylandPortalRecorder] (region capture):
 *
 * - **No more occluding-app leakage.** Anything that floats above the picked window during the
 *   recording — popovers, notifications, other apps — does not appear; the stream itself is scoped
 *   to the window's surface.
 * - **Fixed crop from start.** The `region` passed to [start] is the window's bounds at recording
 *   start. The crop stays at those pixel coordinates for the rest of the recording. If the user
 *   moves or resizes the window during the recording, the crop no longer aligns with the window's
 *   pixels — the recording shows whatever the compositor renders into the original rectangle
 *   (typically blank/black for the unrendered area of a window-source-type stream). For
 *   follow-on-move support we'd need to consume per-buffer `SPA_META_VideoCrop` via
 *   libpipewire-direct (the gst-launch + `videocrop` path can't do that — videocrop's `auto-crop`
 *   mode uses meta x/y as offsets but doesn't size the output from meta width/height); tracked
 *   separately as a follow-up.
 * - **Permission dialog asks for a window.** First call within a session pops the compositor's
 *   window-picker; the user picks the target window. Subsequent calls reuse the grant via the
 *   portal's `restore_token`, same as the region path.
 *
 * **Embedded `ComposePanel` surfaces still need [WaylandPortalRecorder]** — there's no top-level OS
 * window for the compositor to scope a window-source stream to.
 * [dev.sebastiano.spectre.recording.AutoRecorder] routes those automatically.
 */
internal class WaylandPortalWindowRecorder(
    private val delegate: dev.sebastiano.spectre.recording.Recorder =
        WaylandPortalRecorder(sourceTypes = listOf(SourceType.WINDOW)),
    private val frameExtentsLookup: (String) -> Insets? = ::queryGtkFrameExtentsViaXprop,
) : WaylandWindowSourceRecorder {

    /**
     * Start the window-targeted recording. The caller passes [window] and the [region] containing
     * the window's *screen-pixel bounds* (typically `frame.getBounds()` on the underlying AWT
     * [java.awt.Frame]). The recorder converts those to a stream-relative crop using the window's
     * [Insets] queried from `_GTK_FRAME_EXTENTS`, hands the stream-relative crop to the
     * [delegate]'s start, returns the resulting handle.
     *
     * Throws [IllegalStateException] when:
     * - The [window]'s [TitledWindow.title] is null or blank — we can't query `_GTK_FRAME_EXTENTS`
     *   without a window name to match against. Use [WaylandPortalRecorder] (region path) for
     *   un-titled embedded surfaces.
     * - [frameExtentsLookup] returns `null` — typically `xprop` isn't on `PATH`, the WM doesn't
     *   publish `_GTK_FRAME_EXTENTS` for the window (older WMs / non-GTK CSD), or no window
     *   matching the title was found. Without the extents we'd produce a silently-mis-cropped mp4
     *   (close-button clipped off the right of the title bar on the Mutter setup we verified
     *   against), so fail loudly instead of degrading. The caller can either install `xprop` (`apt
     *   install x11-utils` on Debian/Ubuntu) or fall back to [WaylandPortalRecorder].
     */
    override fun start(
        window: TitledWindow,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val title = window.title
        check(!title.isNullOrBlank()) {
            "WaylandPortalWindowRecorder requires a non-blank TitledWindow.title to query the " +
                "WM frame extents via xprop; got title=$title. Use WaylandPortalRecorder for " +
                "untitled embedded surfaces."
        }
        val extents =
            frameExtentsLookup(title)
                ?: error(
                    "Could not determine WM frame extents for window '$title' on this Wayland " +
                        "session. The window-targeted recording path needs `_GTK_FRAME_EXTENTS` " +
                        "via the system `xprop` binary to compute the stream-relative crop; " +
                        "without it, the recording would be misaligned (close button clipped, " +
                        "shadow leaking in). Install xprop (`apt install x11-utils`) or fall " +
                        "back to WaylandPortalRecorder for region capture."
                )
        val streamRelativeCrop = Rectangle(extents.left, extents.top, region.width, region.height)
        return delegate.start(streamRelativeCrop, output, options)
    }
}

/**
 * Query `_GTK_FRAME_EXTENTS(CARDINAL) = L, R, T, B` on the X11 window matching [title] via the
 * system `xprop` binary. Returns the extents as a Java [Insets] (`top`, `left`, `bottom`, `right`)
 * when the property is present and parseable; returns `null` if `xprop` isn't on `PATH`, the
 * property is missing, the window can't be found, the process times out, or the output doesn't
 * parse. The caller is expected to surface the null as a hard error rather than silently defaulting
 * to zero extents — without the right values the resulting mp4 is misaligned (close-button clipped)
 * on the Mutter setup we verified against.
 *
 * GTK-decorated windows under Mutter set this property to the invisible-shadow margin around the
 * visible window (typically `25, 25, 25, 25`); the values are added to the compositor's stream
 * coordinates so that a window with screen-bounds (305, 280, 480, 240) renders into the
 * window-source PipeWire stream at (25, 25, 480, 240) — i.e. the title bar's top-left starts 25px
 * in from the stream origin, leaving the shadow margin around it. Cropping the stream at the
 * extents gives us the visible window without the shadow padding.
 */
private fun queryGtkFrameExtentsViaXprop(title: String): Insets? {
    val process =
        try {
            ProcessBuilder("xprop", "-name", title, "_GTK_FRAME_EXTENTS")
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return null
        }
    val finished = process.waitFor(XPROP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return null
    }
    val output =
        try {
            process.inputStream.bufferedReader().readText()
        } catch (_: IOException) {
            return null
        }
    // xprop emits one of:
    //   "_GTK_FRAME_EXTENTS(CARDINAL) = 25, 25, 25, 25"  (property set)
    //   "_GTK_FRAME_EXTENTS:  not found."                  (property absent — older WMs / SSD)
    //   "xprop: ... not found"                             (no window with that title)
    val match = GTK_FRAME_EXTENTS_REGEX.find(output) ?: return null
    // Skip 4-tuple destructuring (detekt limit); index by named-ish offsets. The regex
    // captures L, R, T, B in capture groups 1..4 in xprop's wire-format ordering.
    val groups = match.groupValues
    val left = groups[GTK_EXTENT_GROUP_LEFT].toInt()
    val right = groups[GTK_EXTENT_GROUP_RIGHT].toInt()
    val top = groups[GTK_EXTENT_GROUP_TOP].toInt()
    val bottom = groups[GTK_EXTENT_GROUP_BOTTOM].toInt()
    return Insets(top, left, bottom, right)
}

private val GTK_FRAME_EXTENTS_REGEX =
    Regex("""_GTK_FRAME_EXTENTS\(CARDINAL\)\s*=\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)""")

// xprop emits _GTK_FRAME_EXTENTS values in left, right, top, bottom order.
private const val GTK_EXTENT_GROUP_LEFT = 1
private const val GTK_EXTENT_GROUP_RIGHT = 2
private const val GTK_EXTENT_GROUP_TOP = 3
private const val GTK_EXTENT_GROUP_BOTTOM = 4
private const val XPROP_TIMEOUT_SECONDS = 2L

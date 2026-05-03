package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.nio.file.Path

/**
 * Window-targeted [WindowRecorder] for Linux Wayland sessions, sibling of [WaylandPortalRecorder].
 * Uses the xdg-desktop-portal `ScreenCast.SelectSources` `Window` source type (#85): the user picks
 * a specific window at the permission dialog and the compositor hands back a PipeWire stream that
 * follows that window across the screen for its lifetime.
 *
 * Compared to [WaylandPortalRecorder]:
 *
 * - **Source type:** `Window` instead of `Monitor`. The portal's "share your screen" dialog asks
 *   the user to pick a window rather than a monitor. The portal advertises window mode under
 *   `org.freedesktop.portal.ScreenCast.AvailableSourceTypes`; on supporting compositors (mutter
 *   GNOME 42+, KDE Plasma 5.27+, wlroots) bit 2 is set.
 * - **No region cropping.** The granted PipeWire stream IS the picked window — its dimensions match
 *   the window's pixel size, its position tracks the window. Any post-portal `videocrop` would
 *   fight the compositor's auto-follow, so the helper skips the crop element entirely when the JVM
 *   passes `region = null`.
 * - **Window movement is followed automatically.** The mp4 keeps capturing the window's pixels as
 *   the user drags it across the screen, matching [ScreenCaptureKitRecorder] on macOS and
 *   [FfmpegWindowRecorder] on Windows.
 * - **Occlusion doesn't bake in.** Unlike region capture, anything floating above the window is
 *   composited over the window's own rendering — depending on the compositor, occluding windows may
 *   or may not appear. mutter renders the window's surface directly, so they don't.
 * - **Embedded `ComposePanel` surfaces still need [WaylandPortalRecorder]** — there's no top-level
 *   OS window for the compositor to track. [dev.sebastiano.spectre.recording.AutoRecorder] routes
 *   those automatically.
 *
 * The [TitledWindow] argument is informational on this backend: the portal dialog's window picker
 * is the source of truth, and there is no Wayland API to pre-select a window programmatically
 * (synthetic XTest events on XWayland would only see XWayland clients, missing native Wayland
 * windows). The recorder accepts the parameter for [WindowRecorder] interface parity with the
 * macOS/Windows backends but does not pass it to the helper.
 */
internal class WaylandPortalWindowRecorder(
    private val delegate: WaylandPortalRecorder = WaylandPortalRecorder()
) : WindowRecorder {

    override fun start(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        delegate.startInternal(
            sourceTypes = listOf(SourceType.WINDOW),
            // No region — the granted stream IS the picked window. Helper skips videocrop when
            // region is null so window movement / resize are tracked by the compositor instead.
            region = null,
            output = output,
            options = options,
        )
}

# Recording

The `:recording` module captures video of a running Compose Desktop UI. It
is optional — depend on it only if a test or sample needs an MP4. **Audio
is not supported.**

## Entry point — `AutoRecorder`

`AutoRecorder` exposes explicit window and region capture entry points:

| Platform | `startWindow` | `startRegion` |
|---|---|---|
| macOS | ScreenCaptureKit (native) | ffmpeg region capture |
| Windows | ffmpeg `gdigrab title=` | ffmpeg `gdigrab` |
| Linux X11 / XWayland | unsupported | ffmpeg `x11grab` |
| Linux Wayland (GNOME/Mutter) | xdg-desktop-portal window source | xdg-desktop-portal monitor source |

```kotlin
val recorder = AutoRecorder()
val handle = recorder.startWindow(
    window = composeWindow.asTitledWindow(),
    output = Path.of("build/recordings/my-test.mp4"),
    options = RecordingOptions(),
)
try {
    // drive the UI here
} finally {
    handle.stop()
}
```

Always wrap the recorded section in `try { … } finally { handle.stop() }` so
the file is finalised even on failure.

## Window vs region targeting

Two trade-offs, pick deliberately:

- **Region (`startRegion`)**: fixed screen `Rectangle`. Does **not** follow
  the window if it moves or resizes; pixels outside the rectangle (popups,
  dialogs that escape the bounds) are lost. Use when you control window
  placement and want a fixed crop.
- **Window-targeted**: follows the window. Does **not** capture popups that
  live in their own AWT window outside the target. Use for tests where the
  window may move but stays the focus.

Embedded `ComposePanel` instances have no top-level window title, so use
`startRegion(...)` with an explicit rectangle. `startWindow(...)` fails loudly when a
true window-targeted backend is unavailable.

## Linux Wayland caveats

Window-targeted Wayland recording throws `IllegalStateException` if:

- `xprop` is not on PATH.
- The window has no title.
- The compositor doesn't publish `_GTK_FRAME_EXTENTS` (verified only on
  GNOME/Mutter; KDE, sway, wlroots are best-effort).

If a test must run on those compositors, use `startRegion(...)` with a fixed
`Rectangle`.

## HiDPI and coordinates

`Rectangle` arguments are in **screen pixels** (post-HiDPI scaling), as is
`AutomatorNode.boundsOnScreen`. `AutomatorNode.boundsInWindow` is in **dp**.
If you compute a recording region from `boundsInWindow`, apply the display
density yourself — otherwise the region will be off on a Retina display.

## Frame drops

Recordings are best-effort. Heavy CPU contention during a test can drop
frames; the resulting MP4 stays playable but may stutter. The full
per-platform behaviour table is in `docs/RECORDING-LIMITATIONS.md` in the
Spectre repo.

## Don't record blindly in CI

Recording adds 5–30% CPU overhead and produces large artifacts. Gate it
behind an env var or only enable it on failure — not for every test in the
suite.

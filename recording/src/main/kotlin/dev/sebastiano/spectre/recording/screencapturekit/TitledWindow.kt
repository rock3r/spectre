package dev.sebastiano.spectre.recording.screencapturekit

/**
 * Minimal view onto a window object that exposes a mutable title. Lives as an interface so
 * [TitleDiscriminator] can be tested without an AWT/Compose window — the production binding to
 * `androidx.compose.ui.awt.ComposeWindow` (which extends `java.awt.Frame` and inherits `getTitle` /
 * `setTitle`) is a one-line adapter at the `ScreenCaptureKitRecorder` boundary.
 */
internal interface TitledWindow {
    var title: String?
}

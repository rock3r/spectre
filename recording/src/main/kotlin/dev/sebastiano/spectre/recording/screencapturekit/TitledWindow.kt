package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Frame

/**
 * Minimal view onto a window object that exposes a mutable title. Lives as an interface so
 * [TitleDiscriminator] can be tested without an AWT/Compose window — the production binding for
 * `androidx.compose.ui.awt.ComposeWindow` (which extends `java.awt.Frame` and inherits `getTitle` /
 * `setTitle`) is the [asTitledWindow] adapter below.
 */
interface TitledWindow {
    var title: String?
}

/**
 * Adapt an AWT [Frame] (or any subclass — `ComposeWindow`, `JFrame`, `JDialog` parent frame) to the
 * [TitledWindow] surface [ScreenCaptureKitRecorder] expects. The adapter delegates `getTitle` /
 * `setTitle` directly; null titles set through this adapter are written as the empty string,
 * mirroring AWT's own behaviour for `Frame.setTitle(null)`.
 */
fun Frame.asTitledWindow(): TitledWindow =
    object : TitledWindow {
        override var title: String?
            get() = this@asTitledWindow.title
            set(value) {
                this@asTitledWindow.title = value ?: ""
            }
    }

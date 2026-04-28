package dev.sebastiano.spectre.recording.screencapturekit

/**
 * In-memory stand-in for a Compose `Window` / AWT `Frame` — only models the title getter/setter.
 */
internal class FakeTitledWindow(initialTitle: String?) : TitledWindow {
    override var title: String? = initialTitle
}

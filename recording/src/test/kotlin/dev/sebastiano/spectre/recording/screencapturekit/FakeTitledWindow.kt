package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Rectangle

/** In-memory stand-in for a Compose `Window` / AWT `Frame`. */
internal class FakeTitledWindow(initialTitle: String?) : TitledWindow {
    override var title: String? = initialTitle
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

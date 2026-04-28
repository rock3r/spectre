package dev.sebastiano.spectre.recording.screencapturekit

/**
 * Thrown by [HelperBinaryExtractor] when the `spectre-screencapture` helper isn't bundled in the
 * recording module's resources. This is the cross-platform-jar case: a jar built on Linux / Windows
 * lacks the macOS-only helper, so calling `ScreenCaptureKitRecorder` on macOS with that jar fails.
 *
 * Distinct from plain `IllegalStateException` so [dev.sebastiano.spectre.recording.AutoRecorder]
 * can catch ONLY this and fall back to the region-capture recorder. Other startup failures (TCC
 * denied, window not found, helper crashed during init) stay as `IllegalStateException` and
 * propagate — those are caller-actionable and shouldn't be silently masked.
 */
class HelperNotBundledException internal constructor(message: String) :
    IllegalStateException(message)

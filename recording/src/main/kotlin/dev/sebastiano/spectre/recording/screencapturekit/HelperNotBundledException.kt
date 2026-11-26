package dev.sebastiano.spectre.recording.screencapturekit

/**
 * Thrown by [HelperBinaryExtractor] when the `spectre-screencapture` helper isn't bundled in the
 * recording module's resources. This is the cross-platform-jar case: a jar built on Linux / Windows
 * lacks the macOS-only helper, so calling `ScreenCaptureKitRecorder` on macOS with that jar fails.
 *
 * Distinct from plain `IllegalStateException` so callers can identify the missing platform
 * artefact. [dev.sebastiano.spectre.recording.AutoRecorder] preserves the cause and fails closed;
 * callers that accept a different capture surface must explicitly request region capture.
 */
public class HelperNotBundledException internal constructor(message: String) :
    IllegalStateException(message)

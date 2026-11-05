package dev.sebastiano.spectre.recording

/**
 * Marks public API in `dev.sebastiano.spectre.recording.*` as experimental.
 *
 * Per [Spectre's stability policy](https://github.com/rock3r/spectre/blob/main/docs/STABILITY.md),
 * experimental APIs may change or be removed in any release. Callers must explicitly opt in via
 * `@OptIn(ExperimentalSpectreRecordingApi::class)`.
 *
 * Today it covers [AutoScreenshotter.captureWindowByFrame], which the Spectre daemon uses to
 * capture an untitled window (a Swing `JWindow` popup) of an attached application.
 */
@RequiresOptIn(
    message =
        "This Spectre recording API is experimental and may change in any release. " +
            "Opt in with @OptIn(ExperimentalSpectreRecordingApi::class).",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalSpectreRecordingApi

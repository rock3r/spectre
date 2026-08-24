package dev.sebastiano.spectre.input

/**
 * Marks the cooperative desktop-input coordination surface as experimental.
 *
 * The API is intentionally opt-in while its cross-platform identity, recovery, and lifecycle
 * contracts settle. It may change or be removed in any release, including patch releases.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "Spectre desktop input coordination is experimental and may change in any release. " +
            "Opt in with @OptIn(ExperimentalSpectreInputCoordinationApi::class).",
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalSpectreInputCoordinationApi

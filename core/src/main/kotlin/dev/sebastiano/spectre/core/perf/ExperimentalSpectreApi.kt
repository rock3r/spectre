package dev.sebastiano.spectre.core.perf

/**
 * Marks a user-facing Spectre API as experimental: it is intentionally exposed for users to try,
 * but its shape may change without a deprecation cycle between Spectre releases. Distinct from
 * `@InternalSpectreApi`, which marks declarations that are only intended for in-repo escape
 * hatches.
 *
 * Using an experimental declaration requires explicit opt-in:
 * ```
 * @OptIn(ExperimentalSpectreApi::class)
 * fun myTest() { … }
 * ```
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "This Spectre API is experimental and may change between releases. " +
            "Opt in with @OptIn(ExperimentalSpectreApi::class) to use it.",
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalSpectreApi

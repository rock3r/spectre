package dev.sebastiano.spectre.core

/**
 * Marks a declaration as an internal escape hatch — reachable from Kotlin/JVM but not part of
 * Spectre's published user-facing API and not covered by the stability policy. Intended for in-repo
 * fixtures and the experimental HTTP transport that legitimately need access to collaborators users
 * should never reach into.
 *
 * Using a marked declaration requires opt-in:
 * ```
 * @OptIn(InternalSpectreApi::class)
 * fun myFixture() { … }
 * ```
 *
 * Anything annotated with `@InternalSpectreApi` may change or disappear between Spectre releases
 * without notice. Prefer the public API when one exists.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This declaration is part of Spectre's internal escape hatch and is not covered by the " +
            "stability policy. Use a public Spectre API where possible; opt in with " +
            "@OptIn(InternalSpectreApi::class) only if you know why you need this.",
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalSpectreApi

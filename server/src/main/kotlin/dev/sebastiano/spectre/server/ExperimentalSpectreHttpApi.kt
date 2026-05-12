package dev.sebastiano.spectre.server

/**
 * Marks a declaration as part of the **experimental HTTP transport** in Spectre's `server` module.
 *
 * The HTTP transport is intentionally pre-1.0 and is expected to change as authentication, TLS,
 * narrower per-window capture APIs, and the design work tracked under #96 land. Declarations
 * carrying this annotation are **public** but are explicitly **not covered** by Spectre's binary
 * compatibility guarantees — they may change or be removed in any release, including patch
 * releases.
 *
 * Opt in to use the experimental transport:
 * ```
 * @file:OptIn(ExperimentalSpectreHttpApi::class)
 * ```
 *
 * Or, at a specific call site:
 * ```
 * @OptIn(ExperimentalSpectreHttpApi::class)
 * fun mountTransport(app: Application) { app.installSpectreRoutes(automator) }
 * ```
 *
 * See [the stability policy](https://spectre.sebastiano.dev/STABILITY/) for the full picture of
 * stable / experimental / internal API tiers, and
 * [the security notes](https://spectre.sebastiano.dev/SECURITY/) for the transport's trust
 * boundaries.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "The Spectre HTTP transport is experimental and is not covered by the stability " +
            "policy — its public API may change or be removed in any release. Opt in with " +
            "@OptIn(ExperimentalSpectreHttpApi::class) to acknowledge.",
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalSpectreHttpApi

package dev.sebastiano.spectre.agent

/**
 * Marks attach-side public API in `dev.sebastiano.spectre.agent.*` as experimental.
 *
 * Per [Spectre's stability policy](https://github.com/rock3r/spectre/blob/main/docs/STABILITY.md),
 * experimental APIs may change or be removed in any release. Callers must explicitly opt in via
 * `@OptIn(ExperimentalSpectreAgentApi::class)`.
 *
 * The agent attach surface (`AgentAttach`, `AttachedAutomator`, `AttachOptions`,
 * `SpectreProcesses`) carries this annotation in v1 until the UX has stabilised — see issue
 * [#153](https://github.com/rock3r/spectre/issues/153).
 */
@RequiresOptIn(
    message =
        "The Spectre agent attach API is experimental and may change in any release. " +
            "Opt in with @OptIn(ExperimentalSpectreAgentApi::class).",
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
public annotation class ExperimentalSpectreAgentApi

package dev.sebastiano.spectre.testing

import java.nio.file.Path

/**
 * Configuration for recording video during a Spectre-driven JUnit test (#206).
 *
 * Independent of [FailureArtifactsConfig] (stills stay default-on; video defaults to
 * [FailureVideoPolicy.Off]). Paths share the same reports tree layout as stills via
 * [FailureArtifactPaths.methodDirectory] parameters ([reportsRoot], [attemptIndex],
 * [invocationId]).
 *
 * Wire next to stills config on [ComposeAutomatorExtension] / [ComposeAutomatorRule].
 */
public data class FailureVideoConfig(
    public val policy: FailureVideoPolicy = FailureVideoPolicy.Off,
    public val reportsRoot: Path = FailureArtifactPaths.defaultReportsRoot(),
    public val attemptIndex: Int? = null,
    public val invocationId: String? = null,
) {
    init {
        require(attemptIndex == null || attemptIndex >= 1) {
            "attemptIndex must be null or >= 1 (1-based), was $attemptIndex"
        }
    }
}

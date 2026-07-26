package dev.sebastiano.spectre.testing

import java.nio.file.Path

/**
 * Configuration for writing atomic capture artifacts when a Spectre-driven JUnit test fails.
 *
 * Default is **enabled**. Opt out with `FailureArtifactsConfig(enabled = false)` when wiring the
 * JUnit extension/rule (see epic #205).
 *
 * Artifacts land under [reportsRoot] (default `build/reports/spectre` under the test JVM's
 * `user.dir`) so Gradle `clean` and a single CI `upload-artifact` glob own their lifecycle —
 * deliberately not the `$TMPDIR` capture root used by the CLI/agent.
 *
 * [attemptIndex] is **1-based**. When greater than 1, artifacts nest under an `attempt-<n>`
 * directory so retries do not overwrite prior attempt captures and do not collide with a test
 * literally named `…-attempt-N` (see #110 / future `runSpectreTest`).
 *
 * [invocationId] distinguishes parallel or repeated invocations of the same class/method that share
 * a null attempt index (JUnit 5 unique id / display name). When non-null/blank it becomes an extra
 * path segment under the method directory.
 */
public data class FailureArtifactsConfig(
    public val enabled: Boolean = true,
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

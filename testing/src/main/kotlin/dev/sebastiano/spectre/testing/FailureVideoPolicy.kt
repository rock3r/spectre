package dev.sebastiano.spectre.testing

/**
 * Recording policy for failure-video on Spectre-driven JUnit tests (#206).
 *
 * The policy decides whether to retain a recording after the test finishes.
 *
 * - [Off] — default; no recorder overhead
 * - [OnFailureKeep] — delete the finalized file on pass; keep on fail
 * - [Always] — keep the finalized video on pass and fail (not on assumption/abort skips)
 */
public enum class FailureVideoPolicy {
    /** No recording. Default for CI cost. */
    Off,

    /**
     * After the recorder is stopped and the file is finalized, keep the video only when the test
     * failed (not on pass or non-failure abort).
     */
    OnFailureKeep,

    /**
     * Keep the finalized video on pass and fail. Non-failure aborts (assumptions) still discard the
     * video so skip suites do not leave misleading “failure” artifacts.
     */
    Always,
}

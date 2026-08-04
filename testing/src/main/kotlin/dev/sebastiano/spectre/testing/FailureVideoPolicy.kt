package dev.sebastiano.spectre.testing

/**
 * Recording policy for failure-video on Spectre-driven JUnit tests (#206).
 *
 * You cannot record retroactively, so video-of-a-failure means recording the whole test and
 * deciding at the end whether to keep the finalized file.
 *
 * - [Off] — default; no recorder overhead
 * - [OnFailureKeep] — record the whole test; delete the finalized file on pass; keep on fail
 * - [Always] — keep the finalized video on pass and fail (not on assumption/abort skips)
 */
public enum class FailureVideoPolicy {
    /** No recording. Default for CI cost. */
    Off,

    /**
     * Record for the duration of the test invocation. After the recorder is stopped and the file is
     * finalized, keep the video only when the test failed (not on pass or non-failure abort).
     */
    OnFailureKeep,

    /**
     * Record for the duration of the test invocation and keep the finalized video on pass and fail.
     * Non-failure aborts (assumptions) still discard the video so skip suites do not leave
     * misleading “failure” artifacts.
     */
    Always,
}

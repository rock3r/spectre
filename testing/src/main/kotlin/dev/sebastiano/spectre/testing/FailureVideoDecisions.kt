package dev.sebastiano.spectre.testing

/** Outcome used to decide keep vs delete after a failure-video session is finalized. */
internal enum class FailureVideoOutcome {
    Passed,
    Failed,
    Aborted,
}

/**
 * Pure policy table for #206. Separated from OS recorder I/O so unit tests drive the real decision
 * functions without headed capture.
 */
internal object FailureVideoDecisions {

    fun shouldStart(policy: FailureVideoPolicy): Boolean = policy != FailureVideoPolicy.Off

    fun shouldKeep(policy: FailureVideoPolicy, outcome: FailureVideoOutcome): Boolean =
        when (policy) {
            FailureVideoPolicy.Off -> false
            FailureVideoPolicy.OnFailureKeep -> outcome == FailureVideoOutcome.Failed
            FailureVideoPolicy.Always ->
                outcome == FailureVideoOutcome.Passed || outcome == FailureVideoOutcome.Failed
        }

    /**
     * Maps a JUnit throwable (or null for a clean pass) to a [FailureVideoOutcome], using the same
     * non-failure-abort rules as [FailureArtifactHooks.isNonFailureAbort].
     */
    fun outcomeFromThrowable(throwable: Throwable?): FailureVideoOutcome =
        when {
            throwable == null -> FailureVideoOutcome.Passed
            FailureArtifactHooks.isNonFailureAbort(throwable) -> FailureVideoOutcome.Aborted
            else -> FailureVideoOutcome.Failed
        }
}

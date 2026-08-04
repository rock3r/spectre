package dev.sebastiano.spectre.testing

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure decision table for #206 failure-video policy. No recorder I/O — only whether to start and
 * whether to keep the finalized file after an outcome.
 */
class FailureVideoPolicyTest {

    @Test
    fun `default policy is Off`() {
        assertEquals(FailureVideoPolicy.Off, FailureVideoConfig().policy)
    }

    @Test
    fun `shouldStart is false only for Off`() {
        assertFalse(FailureVideoDecisions.shouldStart(FailureVideoPolicy.Off))
        assertTrue(FailureVideoDecisions.shouldStart(FailureVideoPolicy.OnFailureKeep))
        assertTrue(FailureVideoDecisions.shouldStart(FailureVideoPolicy.Always))
    }

    @Test
    fun `Off never keeps`() {
        for (outcome in FailureVideoOutcome.entries) {
            assertFalse(FailureVideoDecisions.shouldKeep(FailureVideoPolicy.Off, outcome))
        }
    }

    @Test
    fun `OnFailureKeep keeps only real failures`() {
        assertFalse(
            FailureVideoDecisions.shouldKeep(
                FailureVideoPolicy.OnFailureKeep,
                FailureVideoOutcome.Passed,
            )
        )
        assertTrue(
            FailureVideoDecisions.shouldKeep(
                FailureVideoPolicy.OnFailureKeep,
                FailureVideoOutcome.Failed,
            )
        )
        assertFalse(
            FailureVideoDecisions.shouldKeep(
                FailureVideoPolicy.OnFailureKeep,
                FailureVideoOutcome.Aborted,
            )
        )
    }

    @Test
    fun `Always keeps pass and fail but not abort`() {
        assertTrue(
            FailureVideoDecisions.shouldKeep(FailureVideoPolicy.Always, FailureVideoOutcome.Passed)
        )
        assertTrue(
            FailureVideoDecisions.shouldKeep(FailureVideoPolicy.Always, FailureVideoOutcome.Failed)
        )
        assertFalse(
            FailureVideoDecisions.shouldKeep(FailureVideoPolicy.Always, FailureVideoOutcome.Aborted)
        )
    }

    @Test
    fun `worseOutcome prefers Failed over Aborted over Passed`() {
        assertEquals(
            FailureVideoOutcome.Failed,
            FailureVideoDecisions.worseOutcome(
                FailureVideoOutcome.Failed,
                FailureVideoOutcome.Aborted,
            ),
        )
        assertEquals(
            FailureVideoOutcome.Failed,
            FailureVideoDecisions.worseOutcome(
                FailureVideoOutcome.Aborted,
                FailureVideoOutcome.Failed,
            ),
        )
        assertEquals(
            FailureVideoOutcome.Aborted,
            FailureVideoDecisions.worseOutcome(
                FailureVideoOutcome.Passed,
                FailureVideoOutcome.Aborted,
            ),
        )
        assertEquals(
            FailureVideoOutcome.Passed,
            FailureVideoDecisions.worseOutcome(
                FailureVideoOutcome.Passed,
                FailureVideoOutcome.Passed,
            ),
        )
    }

    @Test
    fun `outcomeFromThrowable maps abort types like stills hooks`() {
        assertEquals(FailureVideoOutcome.Passed, FailureVideoDecisions.outcomeFromThrowable(null))
        assertEquals(
            FailureVideoOutcome.Failed,
            FailureVideoDecisions.outcomeFromThrowable(AssertionError("boom")),
        )
        assertEquals(
            FailureVideoOutcome.Aborted,
            FailureVideoDecisions.outcomeFromThrowable(
                org.opentest4j.TestAbortedException("assumption")
            ),
        )
        assertEquals(
            FailureVideoOutcome.Aborted,
            FailureVideoDecisions.outcomeFromThrowable(
                org.junit.AssumptionViolatedException("skip")
            ),
        )
    }
}

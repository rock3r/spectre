package dev.sebastiano.spectre.testing.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The #438 absence-wait scenarios are gated on their own capability, not on
 * [AutomatorContractDriver.supportsWaitTaxonomy].
 *
 * That flag's published contract promised only `waitForNodeFailureCategory`. Folding the absence
 * wait into it would ask every driver that already sets it true — including drivers outside this
 * repo — for a `waitUntilGone` they never claimed, and fail the corpus on the interface's default
 * `error(...)`. Upgrading `:testing` must stay a no-op for them, so this pins the gate rather than
 * trusting the two flags to stay independent by accident.
 */
class AbsenceWaitCapabilityGateTest {

    @Test
    fun `a driver claiming only wait taxonomy is never asked for an absence wait`() {
        val driver = WaitTaxonomyOnlyDriver()

        val result = AutomatorContractCorpus.run(driver = driver, realKeyboardEnabled = false)

        assertEquals(
            0,
            driver.absenceWaitCalls,
            "a driver that never claimed supportsAbsenceWait must not have waitUntilGone called",
        )
        assertTrue(
            result.results.none { it.id.startsWith("wait-until-gone") },
            "absence scenarios must not be recorded for a driver that did not opt in: " +
                result.results.filter { it.id.startsWith("wait-until-gone") },
        )
        // The old contract still holds: its own wait scenario runs, and the whole corpus passes.
        assertTrue(
            result.results.any { it.id == "wait-for-node-timeout-taxonomy" },
            "supportsWaitTaxonomy must still drive its own scenario",
        )
        result.requireAllPassed()
    }

    @Test
    fun `opting in adds the absence scenario and actually calls the driver`() {
        // Mirror image, so the gate cannot pass by disabling the scenario for everyone.
        val driver = WaitTaxonomyOnlyDriver(absenceWait = true)

        val result = AutomatorContractCorpus.run(driver = driver, realKeyboardEnabled = false)

        assertEquals(1, driver.absenceWaitCalls)
        assertTrue(
            result.results.any { it.id == "wait-until-gone-absent-selector" },
            "opted-in driver must run the absence scenario",
        )
        result.requireAllPassed()
    }

    /** Headless driver claiming the pre-#438 wait contract only, unless [absenceWait] is set. */
    private class WaitTaxonomyOnlyDriver(private val absenceWait: Boolean = false) :
        AutomatorContractDriver {
        var absenceWaitCalls: Int = 0

        override val transport: AutomatorTransport = AutomatorTransport.InProcess
        override val expectsFixtureSemantics: Boolean = false
        override val supportsWaitTaxonomy: Boolean = true
        override val supportsAbsenceWait: Boolean
            get() = absenceWait

        override fun windows(): List<ContractWindow> = emptyList()

        override fun allNodes(): List<ContractNode> = emptyList()

        override fun findByTestTag(tag: String): List<ContractNode> = emptyList()

        override fun findByText(text: String, exact: Boolean): List<ContractNode> = emptyList()

        override fun findByContentDescription(description: String): List<ContractNode> = emptyList()

        override fun findByRole(role: String): List<ContractNode> = emptyList()

        override fun click(nodeKey: String): Unit = error("no node $nodeKey")

        override fun typeText(text: String) = Unit

        override fun waitForNodeFailureCategory(
            tag: String?,
            text: String?,
            timeoutMs: Long,
        ): String = "timeout"

        override fun waitUntilGone(tag: String?, text: String?, timeoutMs: Long) {
            absenceWaitCalls++
        }

        override fun close() = Unit
    }
}

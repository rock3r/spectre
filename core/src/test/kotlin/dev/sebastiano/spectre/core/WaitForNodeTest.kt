package dev.sebastiano.spectre.core

import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Unit-level tests for [ComposeAutomator.waitForNode]'s public-boundary contracts: argument
 * validation and EDT rejection. The matching behaviour (tag-only, text-only, combined tag+text AND,
 * timeout) is exercised end-to-end against a live Compose surface in the sample-desktop validation
 * suite (`WaitForNodeMatchingValidationTest`); the contracts that don't need real Compose live
 * here.
 */
class WaitForNodeTest {

    @Test
    fun `waitForNode requires tag or text`() = runBlocking {
        val automator = headlessAutomator()
        val error =
            assertFailsWith<IllegalArgumentException> {
                automator.waitForNode(tag = null, text = null)
            }
        assertEquals("Either tag or text must be specified", error.message)
    }

    @Test
    fun `waitForNode reports the bad-argument error when called from the EDT with null inputs`() {
        // Argument validation must run BEFORE the EDT check. An EDT caller passing
        // (null, null) is doing two things wrong; surfacing the input error first keeps
        // the diagnostic actionable instead of redirecting users to the EDT story.
        assumeLiveAwtAvailable()
        val automator = headlessAutomator()
        val errorRef = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runBlocking { errorRef.set(runCatching { automator.waitForNode() }.exceptionOrNull()) }
        }
        val error = errorRef.get()
        assertTrue(
            error is IllegalArgumentException,
            "expected IllegalArgumentException, got $error",
        )
        assertEquals("Either tag or text must be specified", error.message)
    }

    @Test
    fun `waitForNode rejects EDT callers with valid arguments`() {
        assumeLiveAwtAvailable()
        val automator = headlessAutomator()
        val errorRef = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runBlocking {
                errorRef.set(
                    runCatching { automator.waitForNode(tag = "irrelevant") }.exceptionOrNull()
                )
            }
        }
        val error = errorRef.get()
        assertTrue(error is IllegalStateException, "expected IllegalStateException, got $error")
        assertTrue(
            error.message?.contains(
                "waitForNode must not be called from the AWT event dispatch thread"
            ) == true,
            "expected curated EDT message, got: ${error.message}",
        )
    }

    private fun headlessAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}

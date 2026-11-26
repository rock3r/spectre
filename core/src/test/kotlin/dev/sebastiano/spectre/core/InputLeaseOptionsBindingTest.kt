@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.CoordinatedInputLease
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.input.LeaseToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class InputLeaseOptionsBindingTest {
    @Test
    fun `operation option binding rejects overlap and stale close cannot clear successor`() =
        runTest {
            val coordinator = OptionsRecordingCoordinator()
            val driver =
                RobotDriver(
                    LeaseTestRobotAdapter(),
                    inputLeasePolicy = InputLeasePolicy.Required,
                    inputLeaseCoordinator = coordinator,
                    inputCapabilities =
                        InputCapabilities(realOsInput = true, sharedSystemClipboard = false),
                )
            val first = InputLeaseOptions(1.seconds, "FirstTest#method")
            val second = InputLeaseOptions(2.seconds, "SecondTest#method")

            val firstBinding = driver.bindInputLeaseOptions(first)
            val failure =
                assertFailsWith<IllegalStateException> { driver.bindInputLeaseOptions(second) }
            assertTrue(failure.message.orEmpty().contains("already bound"))

            firstBinding.close()
            val secondBinding = driver.bindInputLeaseOptions(second)
            firstBinding.close()
            driver.click(10, 20)
            secondBinding.close()

            assertEquals(listOf(second), coordinator.options)
        }

    @Test
    fun `bound operation defaults carry timeout and owner through multiple operations`() = runTest {
        val coordinator = OptionsRecordingCoordinator()
        val driver =
            RobotDriver(
                LeaseTestRobotAdapter(),
                inputLeasePolicy = InputLeasePolicy.Required,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = true, sharedSystemClipboard = false),
            )
        val options = InputLeaseOptions(7.seconds, "ExampleTest#clicks")
        val binding = driver.bindInputLeaseOptions(options)

        driver.click(10, 20)
        driver.typeText("a")
        binding.close()

        assertEquals(listOf(options, options), coordinator.options)
    }
}

private class OptionsRecordingCoordinator : InputLeaseCoordinator {
    val options = mutableListOf<InputLeaseOptions>()

    override suspend fun acquire(
        options: InputLeaseOptions,
        currentOperation: String,
        immediate: Boolean,
    ): CoordinatedInputLease {
        this.options += options
        return object : CoordinatedInputLease {
            override val token = LeaseToken("epoch", "lease", DesktopResourceKey("test/desktop"), 1)

            override fun isValid(): Boolean = true

            override fun checkpoint() = Unit

            override fun close() = Unit
        }
    }

    override fun close() = Unit
}

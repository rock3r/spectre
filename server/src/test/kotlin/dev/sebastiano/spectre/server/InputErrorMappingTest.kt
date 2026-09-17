@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

/**
 * Pins input-failure taxonomy for [respondInputVoid]: Robot adapters throw [IllegalStateException]
 * or [UnsupportedOperationException] (headless / TCC / missing I/O). Both must map to
 * `inputRejected` rather than an unclassified 500.
 */
class InputErrorMappingTest {

    @Test
    fun `unsupported input adapter maps to inputRejected`() {
        assertEquals(
            SpectreErrorCategory.InputRejected,
            mapInputFailure(
                UnsupportedOperationException(
                    "RobotDriver.headless() does not support clearAndTypeText"
                )
            ),
        )
    }

    @Test
    fun `illegal-state input maps to inputRejected`() {
        assertEquals(
            SpectreErrorCategory.InputRejected,
            mapInputFailure(IllegalStateException("input refused")),
        )
    }

    @Test
    fun `cancellation is rethrown`() {
        assertFailsWith<CancellationException> {
            mapInputFailure(CancellationException("cancelled"))
        }
    }
}

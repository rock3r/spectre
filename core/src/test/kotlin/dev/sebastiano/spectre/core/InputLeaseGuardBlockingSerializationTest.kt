@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.CoordinatedInputLease
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class InputLeaseGuardBlockingSerializationTest {

    @Test
    fun `sibling blocking operations inside an exclusive scope are serialized`() {
        val coordinator = SingleLeaseCoordinator()
        val guard =
            InputLeaseGuard(
                policy = InputLeasePolicy.Required,
                capabilities = InputCapabilities(realOsInput = true, sharedSystemClipboard = true),
                coordinator = coordinator,
            )
        val executor = Executors.newFixedThreadPool(3)
        executor.asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                guard.withOperation("exclusiveInput", CoordinatedResource.DESKTOP_ANY) {
                    coroutineScope {
                        val firstEntered = CountDownLatch(1)
                        val releaseFirst = CountDownLatch(1)
                        val secondReady = CountDownLatch(1)
                        val secondEntered = CountDownLatch(1)
                        val first = async {
                            guard.withBlockingOperation("first", CoordinatedResource.REAL_INPUT) {
                                guard.withBlockingOperation(
                                    "firstNested",
                                    CoordinatedResource.REAL_INPUT,
                                ) {
                                    firstEntered.countDown()
                                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                                }
                            }
                        }
                        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                        val second = async {
                            secondReady.countDown()
                            guard.withBlockingOperation("second", CoordinatedResource.REAL_INPUT) {
                                secondEntered.countDown()
                            }
                        }

                        assertTrue(secondReady.await(5, TimeUnit.SECONDS))
                        try {
                            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS))
                        } finally {
                            releaseFirst.countDown()
                        }
                        first.await()
                        second.await()
                    }
                }
            }
        }

        assertEquals(1, coordinator.acquisitions)
        assertEquals(1, coordinator.releases)
    }

    private class SingleLeaseCoordinator : InputLeaseCoordinator {
        var acquisitions: Int = 0
        var releases: Int = 0

        override suspend fun acquire(
            options: InputLeaseOptions,
            currentOperation: String,
            immediate: Boolean,
        ): CoordinatedInputLease {
            acquisitions += 1
            return object : CoordinatedInputLease {
                override val token =
                    LeaseToken(
                        coordinatorEpoch = "epoch",
                        leaseId = "lease",
                        resourceKey = DesktopResourceKey("test/desktop"),
                        fence = 1,
                    )

                override fun isValid(): Boolean = true

                override fun checkpoint(): Unit = Unit

                override fun close() {
                    releases += 1
                }
            }
        }
    }
}

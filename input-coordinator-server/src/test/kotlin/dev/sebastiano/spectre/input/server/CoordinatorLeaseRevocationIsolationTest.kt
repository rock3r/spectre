@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinatorLeaseRevocationIsolationTest {

    @Test
    fun `revocation cannot target a lease held on another desktop resource`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.openSession("holder")
            val held = service.acquire(acquire("desktop-b", "holder")).get()

            val revoke =
                service.revoke(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.REVOKE,
                        resourceKey = "desktop-a",
                        leaseId = held.leaseId,
                        requesterLabel = "operator",
                        reason = "observed elsewhere",
                    )
                )

            assertFalse(revoke.ok)
            assertEquals("STALE_LEASE", revoke.errorCode)
            assertTrue(service.heartbeat(tokenMessage(held, "holder")).ok)
        } finally {
            service.close()
        }
    }

    private fun acquire(resourceKey: String, clientId: String): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.ACQUIRE,
            requestId = "acquire-$resourceKey",
            clientId = clientId,
            resourceKey = resourceKey,
            processId = 1,
            timeoutMillis = 1_000,
            currentOperation = "click",
        )

    private fun tokenMessage(
        grant: CoordinatorWireMessage,
        clientId: String,
    ): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.HEARTBEAT,
            clientId = clientId,
            resourceKey = "desktop-b",
            coordinatorEpoch = grant.coordinatorEpoch,
            leaseId = grant.leaseId,
            fence = grant.fence,
        )
}

@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinatorLeaseServiceTest {

    @Test
    fun `cancelling an unknown request does not disconnect an active lease`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            val grant =
                service
                    .acquire(
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.ACQUIRE,
                            requestId = "held",
                            clientId = "client",
                            resourceKey = "test/desktop",
                            processId = 1,
                            timeoutMillis = 1_000,
                            currentOperation = "click",
                        )
                    )
                    .get()

            service.cancel(
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.CANCEL,
                    requestId = "already-completed",
                    clientId = "client",
                    resourceKey = "test/desktop",
                )
            )

            val heartbeat =
                service.heartbeat(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.HEARTBEAT,
                        clientId = "client",
                        resourceKey = "test/desktop",
                        coordinatorEpoch = grant.coordinatorEpoch,
                        leaseId = grant.leaseId,
                        fence = grant.fence,
                    )
                )
            assertTrue(heartbeat.ok)
        } finally {
            service.close()
        }
    }

    @Test
    fun `disconnect completes queued acquisitions for that client`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.acquire(acquire("holder", "holder-client")).get(1, TimeUnit.SECONDS)
            val queued = service.acquire(acquire("waiting", "waiting-client"))

            service.disconnect("waiting-client")
            val response = queued.get(1, TimeUnit.SECONDS)

            assertFalse(response.ok)
            assertEquals("CLIENT_DISCONNECTED", response.errorCode)
        } finally {
            service.close()
        }
    }

    private fun acquire(requestId: String, clientId: String): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.ACQUIRE,
            requestId = requestId,
            clientId = clientId,
            resourceKey = "test/desktop",
            processId = 1,
            timeoutMillis = 10_000,
            currentOperation = "click",
        )
}

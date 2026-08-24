@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinatorLeaseReleaseIdempotencyTest {

    @Test
    fun `duplicate release succeeds without consuming a reentrant hold twice`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.openSession("holder-client")
            service.openSession("successor-client")
            val outer = service.acquire(acquire("outer", "holder-client")).get()
            val inner = service.acquire(acquire("inner", "holder-client")).get()
            val successor = service.acquire(acquire("successor", "successor-client"))
            val innerRelease = tokenMessage(inner, clientId = "holder-client", requestId = "inner")

            assertTrue(service.release(innerRelease).ok)
            assertTrue(service.release(innerRelease).ok)
            assertFalse(successor.isDone, "duplicate release consumed the outer hold")

            assertTrue(
                service
                    .release(tokenMessage(outer, clientId = "holder-client", requestId = "outer"))
                    .ok
            )
            assertTrue(successor.get(1, TimeUnit.SECONDS).ok)
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

    private fun tokenMessage(
        grant: CoordinatorWireMessage,
        clientId: String,
        requestId: String,
    ): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.RELEASE,
            requestId = requestId,
            clientId = clientId,
            resourceKey = "test/desktop",
            coordinatorEpoch = grant.coordinatorEpoch,
            leaseId = grant.leaseId,
            fence = grant.fence,
        )
}

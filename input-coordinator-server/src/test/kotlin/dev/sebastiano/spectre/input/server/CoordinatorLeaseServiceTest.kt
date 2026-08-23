@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `acquire arriving after session disconnect is rejected`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.disconnect("closed-client")

            val response = service.acquire(acquire("late", "closed-client")).get()

            assertFalse(response.ok)
            assertEquals("CLIENT_DISCONNECTED", response.errorCode)
        } finally {
            service.close()
        }
    }

    @Test
    fun `cancel arriving before acquire rejects the delayed request`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.cancel(
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.CANCEL,
                    requestId = "delayed",
                    clientId = "client",
                    resourceKey = "test/desktop",
                )
            )

            val response = service.acquire(acquire("delayed", "client")).get()

            assertFalse(response.ok)
            assertEquals("ACQUIRE_CANCELLED", response.errorCode)
        } finally {
            service.close()
        }
    }

    @Test
    fun `cancel arriving after an unobserved grant releases that exact request`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.acquire(acquire("unobserved", "interrupted-client")).get()

            service.cancel(
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.CANCEL,
                    requestId = "unobserved",
                    clientId = "interrupted-client",
                    resourceKey = "test/desktop",
                )
            )

            val successor = service.acquire(acquire("successor", "next-client")).get()
            assertTrue(successor.ok)
        } finally {
            service.close()
        }
    }

    @Test
    fun `acknowledged revocation clears its recovery record before advancing FIFO`() {
        val directory = Files.createTempDirectory("spc-service-ledger-")
        val path = directory.resolve("recovery.properties")
        val ledger = RecoveryLedger(path, Duration.ofSeconds(5))
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = ledger,
            )
        try {
            val first = service.acquire(acquire("first", "first-client")).get()
            val queued = service.acquire(acquire("second", "second-client"))
            assertTrue(
                service
                    .revoke(
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.REVOKE,
                            resourceKey = "test/desktop",
                            leaseId = first.leaseId,
                            requesterLabel = "operator",
                            reason = "owner should stop",
                        )
                    )
                    .ok
            )

            assertTrue(service.release(tokenMessage(first, "first-client")).ok)
            val second = queued.get(1, TimeUnit.SECONDS)
            assertTrue(service.release(tokenMessage(second, "second-client")).ok)

            assertNull(RecoveryLedger(path, Duration.ofSeconds(5)).load())
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
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
    ): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.RELEASE,
            clientId = clientId,
            resourceKey = "test/desktop",
            coordinatorEpoch = grant.coordinatorEpoch,
            leaseId = grant.leaseId,
            fence = grant.fence,
        )
}

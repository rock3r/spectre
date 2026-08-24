@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinatorLeaseServiceTest {

    @Test
    fun `acquire requires a session opened in the current coordinator epoch`() {
        val service =
            CoordinatorLeaseService(
                epoch = "replacement-epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            val staleEpochResponse = service.acquire(acquire("stale", "old-client")).get()

            assertFalse(staleEpochResponse.ok)
            assertEquals("CLIENT_DISCONNECTED", staleEpochResponse.errorCode)

            service.openSession("old-client")
            assertTrue(service.acquire(acquire("current", "old-client")).get().ok)
        } finally {
            service.close()
        }
    }

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
            service.openSession("client")
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
            service.openSession("holder-client")
            service.openSession("waiting-client")
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
    fun `disconnect fences holder until cleanup release can clear recovery state`() {
        val directory = Files.createTempDirectory("spc-service-disconnect-ledger-")
        val path = directory.resolve("recovery.properties")
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = RecoveryLedger(path, Duration.ofSeconds(5)),
            )
        try {
            service.openSession("holder-client")
            val holder = service.acquire(acquire("holder", "holder-client")).get()
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            service.disconnect("holder-client")

            val status =
                assertNotNull(
                    service
                        .status(
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.STATUS,
                                resourceKey = "test/desktop",
                            )
                        )
                        .status
                )
            assertEquals("revoking", assertNotNull(status.holder).state)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            assertTrue(service.release(tokenMessage(holder, "holder-client")).ok)
            service.openSession("successor-client")
            val successor = service.acquire(acquire("successor", "successor-client")).get()
            assertTrue(successor.ok)
            assertTrue(service.release(tokenMessage(successor, "successor-client")).ok)
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `restart quarantine stays closed and waiter expiry survives ledger IO failure`() {
        val directory = Files.createTempDirectory("spc-service-recovery-expiry-")
        val path = directory.resolve("recovery.properties")
        val ledger = RecoveryLedger(path, Duration.ofMillis(-1))
        ledger.record(
            LeaseGrant(
                requestId = "predecessor-request",
                owner = LeaseOwner("predecessor-client", 1),
                token =
                    LeaseToken(
                        coordinatorEpoch = "predecessor-epoch",
                        leaseId = "predecessor-lease",
                        resourceKey = DesktopResourceKey("test/desktop"),
                        fence = 1,
                    ),
            )
        )
        val restartedLedger = RecoveryLedger(path, Duration.ofSeconds(5))
        val recoveryRecord = assertNotNull(restartedLedger.load())
        Files.delete(path)
        Files.createDirectory(path)
        Files.writeString(path.resolve("blocker"), "prevent ledger deletion")
        val service =
            CoordinatorLeaseService(
                epoch = "replacement-epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ZERO,
                recoveryRecord = recoveryRecord,
                recoveryLedger = restartedLedger,
            )
        try {
            service.openSession("waiting-client")
            val waiting =
                service.acquire(acquire("waiting", "waiting-client").copy(timeoutMillis = 50))

            val response = waiting.get(1, TimeUnit.SECONDS)

            assertFalse(response.ok)
            assertEquals("ACQUIRE_TIMEOUT", response.errorCode)
            val status =
                assertNotNull(
                    service
                        .status(
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.STATUS,
                                resourceKey = "test/desktop",
                            )
                        )
                        .status
                )
            assertEquals("predecessor-lease", assertNotNull(status.quarantine).predecessorLeaseId)
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
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
            service.openSession("closed-client")
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
            service.openSession("client")
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
            service.openSession("interrupted-client")
            service.openSession("next-client")
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
    fun `failed compensating release abandons a known unobserved grant`() {
        val directory = Files.createTempDirectory("spc-service-unobserved-release-")
        val path = directory.resolve("recovery.properties")
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = RecoveryLedger(path, Duration.ofSeconds(5)),
            )
        try {
            service.openSession("interrupted-client")
            service.openSession("successor-client")
            service.acquire(acquire("unobserved", "interrupted-client")).get()
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            val cancelled =
                service.cancel(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.CANCEL,
                        requestId = "unobserved",
                        clientId = "interrupted-client",
                        resourceKey = "test/desktop",
                    )
                )

            assertTrue(cancelled.ok)
            val status =
                assertNotNull(
                    service
                        .status(
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.STATUS,
                                resourceKey = "test/desktop",
                            )
                        )
                        .status
                )
            assertNull(status.holder)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            val successor = service.acquire(acquire("successor", "successor-client")).get()
            assertTrue(successor.ok)
            val recovered = assertNotNull(RecoveryLedger(path, Duration.ofSeconds(5)).load())
            assertFalse(recovered.blocksAllResources)
            assertEquals(successor.leaseId, recovered.leaseId)
            assertTrue(service.release(tokenMessage(successor, "successor-client")).ok)
            assertNull(RecoveryLedger(path, Duration.ofSeconds(5)).load())
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `observed reentrant release preserves an unobserved request for cancellation`() {
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
            )
        try {
            service.openSession("interrupted-client")
            service.openSession("successor-client")
            service.acquire(acquire("unobserved", "interrupted-client")).get()
            val observed = service.acquire(acquire("observed", "interrupted-client")).get()
            val successor = service.acquire(acquire("successor", "successor-client"))

            assertTrue(
                service
                    .release(
                        tokenMessage(
                            observed,
                            clientId = "interrupted-client",
                            requestId = "observed",
                        )
                    )
                    .ok
            )
            service.cancel(
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.CANCEL,
                    requestId = "unobserved",
                    clientId = "interrupted-client",
                    resourceKey = "test/desktop",
                )
            )

            assertTrue(successor.get(1, TimeUnit.SECONDS).ok)
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
            service.openSession("first-client")
            service.openSession("second-client")
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

    @Test
    fun `failed recovery clear retains the fenced holder and queued handoff`() {
        val directory = Files.createTempDirectory("spc-service-ledger-revoke-clear-")
        val path = directory.resolve("recovery.properties")
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = RecoveryLedger(path, Duration.ofSeconds(5)),
            )
        try {
            service.openSession("first-client")
            service.openSession("second-client")
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
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            val failedRelease = service.release(tokenMessage(first, "first-client"))

            assertFalse(failedRelease.ok)
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failedRelease.errorCode)
            val status =
                assertNotNull(
                    service
                        .status(
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.STATUS,
                                resourceKey = "test/desktop",
                            )
                        )
                        .status
                )
            assertEquals("first-client", assertNotNull(status.holder).clientId)
            assertEquals(1, status.waiters.size)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            assertTrue(service.release(tokenMessage(first, "first-client")).ok)
            val second = queued.get(1, TimeUnit.SECONDS)
            assertTrue(service.release(tokenMessage(second, "second-client")).ok)
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `failed FIFO grant persistence rolls back the hidden holder`() {
        val directory = Files.createTempDirectory("spc-service-ledger-failure-")
        val path = directory.resolve("recovery.properties")
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = RecoveryLedger(path, Duration.ofSeconds(5)),
            )
        try {
            service.openSession("holder-client")
            service.openSession("waiting-client")
            service.openSession("successor-client")
            val holder = service.acquire(acquire("holder", "holder-client")).get()
            val waiting = service.acquire(acquire("waiting", "waiting-client"))
            Files.delete(path)
            Files.delete(directory)

            assertTrue(service.release(tokenMessage(holder, "holder-client")).ok)
            val failedHandoff = waiting.get(1, TimeUnit.SECONDS)

            assertFalse(failedHandoff.ok)
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failedHandoff.errorCode)

            Files.createDirectory(directory)
            assertTrue(service.acquire(acquire("successor", "successor-client")).get().ok)
            val recovered = assertNotNull(RecoveryLedger(path, Duration.ofSeconds(5)).load())
            assertFalse(recovered.blocksAllResources)
            assertEquals("successor-client", recovered.owner.clientId)
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `failed recovery clear retains the holder and queued handoff`() {
        val directory = Files.createTempDirectory("spc-service-ledger-clear-")
        val path = directory.resolve("recovery.properties")
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ofSeconds(1),
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = RecoveryLedger(path, Duration.ofSeconds(5)),
            )
        try {
            service.openSession("holder-client")
            service.openSession("waiting-client")
            val holder = service.acquire(acquire("holder", "holder-client")).get()
            val waiting = service.acquire(acquire("waiting", "waiting-client"))
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            val failedRelease = service.release(tokenMessage(holder, "holder-client"))

            assertFalse(failedRelease.ok)
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failedRelease.errorCode)
            val status =
                assertNotNull(
                    service
                        .status(
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.STATUS,
                                resourceKey = "test/desktop",
                            )
                        )
                        .status
                )
            assertEquals("holder-client", assertNotNull(status.holder).clientId)
            assertEquals(1, status.waiters.size)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            assertTrue(service.release(tokenMessage(holder, "holder-client")).ok)
            val handedOff = waiting.get(1, TimeUnit.SECONDS)
            assertTrue(handedOff.ok)
            assertTrue(service.release(tokenMessage(handedOff, "waiting-client")).ok)
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
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
        requestId: String? = null,
    ): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.RELEASE,
            requestId = requestId ?: requireNotNull(grant.requestId),
            clientId = clientId,
            resourceKey = "test/desktop",
            coordinatorEpoch = grant.coordinatorEpoch,
            leaseId = grant.leaseId,
            fence = grant.fence,
        )
}

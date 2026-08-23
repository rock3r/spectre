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
import kotlin.test.assertTrue

class CoordinatorForcedRecoveryPersistenceTest {

    @Test
    fun `failed cancellation release disconnects the affected session`() {
        val directory = Files.createTempDirectory("spc-service-cancel-clear-")
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
            service.acquire(acquire("unobserved", "interrupted-client")).get()
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            service.cancel(
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.CANCEL,
                    requestId = "unobserved",
                    clientId = "interrupted-client",
                    resourceKey = "test/desktop",
                )
            )

            assertEquals(null, status(service).holder)
            val rejected =
                service.acquire(acquire("late", "interrupted-client")).get(1, TimeUnit.SECONDS)
            assertFalse(rejected.ok)
            assertEquals("CLIENT_DISCONNECTED", rejected.errorCode)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            service.openSession("successor-client")
            val successor = service.acquire(acquire("successor", "successor-client")).get()
            assertTrue(service.release(tokenMessage(successor, "successor-client")).ok)
        } finally {
            cleanUp(service, path, directory)
        }
    }

    @Test
    fun `failed forced revoke clear retains the fenced holder and queued handoff`() {
        val directory = Files.createTempDirectory("spc-service-ledger-force-clear-")
        val path = directory.resolve("recovery.properties")
        val service =
            CoordinatorLeaseService(
                epoch = "epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ZERO,
                recoveryGrace = Duration.ofSeconds(1),
                recoveryLedger = RecoveryLedger(path, Duration.ofSeconds(5)),
            )
        try {
            service.openSession("first-client")
            service.openSession("second-client")
            val first = service.acquire(acquire("first", "first-client")).get()
            val queued = service.acquire(acquire("second", "second-client"))
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            val failedForce =
                service.revoke(revokeMessage(requireNotNull(first.leaseId), force = true))

            assertFalse(failedForce.ok)
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failedForce.errorCode)
            val status = status(service)
            assertEquals("first-client", assertNotNull(status.holder).clientId)
            assertEquals(1, status.waiters.size)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            assertTrue(service.revoke(revokeMessage(requireNotNull(first.leaseId), true)).ok)
            val second = queued.get(1, TimeUnit.SECONDS)
            assertTrue(service.release(tokenMessage(second, "second-client")).ok)
        } finally {
            cleanUp(service, path, directory)
        }
    }

    @Test
    fun `failed forced restart recovery clear retains quarantine and queued handoff`() {
        val directory = Files.createTempDirectory("spc-service-recovery-force-clear-")
        val path = directory.resolve("recovery.properties")
        val ledger = RecoveryLedger(path, Duration.ofSeconds(5))
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
        val service =
            CoordinatorLeaseService(
                epoch = "replacement-epoch",
                heartbeatTimeout = Duration.ofSeconds(5),
                revokeGrace = Duration.ZERO,
                recoveryGrace = Duration.ZERO,
                recoveryRecord = recoveryRecord,
                recoveryLedger = restartedLedger,
            )
        try {
            service.openSession("waiting-client")
            val queued = service.acquire(acquire("waiting", "waiting-client"))
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")

            val failedForce = service.revoke(revokeMessage("predecessor-lease", force = true))

            assertFalse(failedForce.ok)
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failedForce.errorCode)
            val status = status(service)
            assertEquals("predecessor-lease", assertNotNull(status.quarantine).predecessorLeaseId)
            assertEquals(1, status.waiters.size)

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            assertTrue(service.revoke(revokeMessage("predecessor-lease", true)).ok)
            val successor = queued.get(1, TimeUnit.SECONDS)
            assertTrue(service.release(tokenMessage(successor, "waiting-client")).ok)
        } finally {
            cleanUp(service, path, directory)
        }
    }

    private fun status(service: CoordinatorLeaseService) =
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

    private fun revokeMessage(leaseId: String, force: Boolean): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.REVOKE,
            resourceKey = "test/desktop",
            leaseId = leaseId,
            requesterLabel = "operator",
            reason = "known stuck",
            force = force,
        )

    private fun cleanUp(
        service: CoordinatorLeaseService,
        path: java.nio.file.Path,
        directory: java.nio.file.Path,
    ) {
        service.close()
        Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
        if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }
}

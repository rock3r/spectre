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

class CoordinatorRecoveryExpiryTest {
    @Test
    fun `restart quarantine stays closed and waiter expiry survives ledger IO failure`() {
        var nowMillis = 0L
        lateinit var runMaintenance: () -> Unit
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
                clock = MonotonicClock { nowMillis },
                scheduleMaintenance = { maintenance ->
                    runMaintenance = maintenance
                    AutoCloseable {}
                },
            )
        try {
            service.openSession("waiting-client")
            val waiting =
                service.acquire(acquire("waiting", "waiting-client").copy(timeoutMillis = 50))

            val failedRecovery =
                service.revoke(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.REVOKE,
                        resourceKey = "test/desktop",
                        leaseId = "predecessor-lease",
                        requesterLabel = "operator",
                        reason = "recover abandoned predecessor",
                        force = true,
                    )
                )
            assertFalse(failedRecovery.ok)
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failedRecovery.errorCode)

            nowMillis = 50
            runMaintenance()
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
            assertNull(status.holder)
            assertEquals("predecessor-lease", assertNotNull(status.quarantine).predecessorLeaseId)
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
}

@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinatorRecoveryRetryTest {
    @Test
    fun `close retries deletion of an abandoned unobserved recovery record`() {
        val directory = Files.createTempDirectory("spc-service-unobserved-close-")
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
            service.openSession("interrupted-client")
            val unobserved = service.acquire(acquire()).get()
            Files.delete(path)
            Files.createDirectory(path)
            Files.writeString(path.resolve("blocker"), "prevent ledger deletion")
            assertTrue(
                service
                    .cancel(
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.CANCEL,
                            requestId = "unobserved",
                            clientId = "interrupted-client",
                            resourceKey = "test/desktop",
                        )
                    )
                    .ok
            )

            Files.delete(path.resolve("blocker"))
            Files.delete(path)
            RecoveryLedger(path, Duration.ofSeconds(5))
                .record(
                    LeaseGrant(
                        requestId = "unobserved",
                        owner = LeaseOwner("interrupted-client", 1, "interrupted-client"),
                        token =
                            LeaseToken(
                                coordinatorEpoch = requireNotNull(unobserved.coordinatorEpoch),
                                leaseId = requireNotNull(unobserved.leaseId),
                                resourceKey = DesktopResourceKey("test/desktop"),
                                fence = requireNotNull(unobserved.fence),
                            ),
                    )
                )

            service.close()

            assertNull(RecoveryLedger(path, Duration.ofSeconds(5)).load())
        } finally {
            service.close()
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            if (Files.isDirectory(path)) Files.deleteIfExists(path.resolve("blocker"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun acquire(): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.ACQUIRE,
            requestId = "unobserved",
            clientId = "interrupted-client",
            resourceKey = "test/desktop",
            processId = 1,
            timeoutMillis = 10_000,
            currentOperation = "click",
        )
}

@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryLedgerTest {

    @Test
    fun `multiple active resources recover through a conservative global quarantine`() {
        val directory = Files.createTempDirectory("spc-ledger-")
        val path = directory.resolve("recovery.properties")
        try {
            val ledger = RecoveryLedger(path, Duration.ofMinutes(1))
            ledger.record(grant("lease-a", "desktop-a", "client-a"))
            ledger.record(grant("lease-b", "desktop-b", "client-b"))

            val recovered = assertNotNull(RecoveryLedger(path, Duration.ofMinutes(1)).load())

            assertTrue(recovered.blocksAllResources)
            assertTrue(recovered.leaseId.startsWith("multiple-"))
        } finally {
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `clearing one of two active resources persists the exact survivor`() {
        val directory = Files.createTempDirectory("spc-ledger-")
        val path = directory.resolve("recovery.properties")
        try {
            val ledger = RecoveryLedger(path, Duration.ofMinutes(1))
            ledger.record(grant("lease-a", "desktop-a", "client-a"))
            ledger.record(grant("lease-b", "desktop-b", "client-b"))

            ledger.clear("lease-b")
            val recovered = assertNotNull(RecoveryLedger(path, Duration.ofMinutes(1)).load())

            assertFalse(recovered.blocksAllResources)
            assertEquals("lease-a", recovered.leaseId)
            assertEquals(DesktopResourceKey("desktop-a"), recovered.resourceKey)
        } finally {
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `expiry pruning never removes a live runtime record`() {
        val directory = Files.createTempDirectory("spc-ledger-")
        val path = directory.resolve("recovery.properties")
        try {
            val ledger = RecoveryLedger(path, Duration.ofMillis(-1))
            val activeGrant = grant("lease-live", "desktop-live", "client-live")
            ledger.record(activeGrant)

            ledger.clearExpiredRecovery(Duration.ZERO)
            ledger.heartbeat(activeGrant.token)

            val recovered = RecoveryLedger(path, Duration.ofMinutes(1)).load()
            assertEquals("lease-live", assertNotNull(recovered).leaseId)
        } finally {
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `expiry pruning removes an eligible record loaded into restart quarantine`() {
        val directory = Files.createTempDirectory("spc-ledger-")
        val path = directory.resolve("recovery.properties")
        try {
            RecoveryLedger(path, Duration.ofMillis(-1))
                .record(grant("lease-recovery", "desktop-recovery", "client-recovery"))
            val restarted = RecoveryLedger(path, Duration.ofMinutes(1))
            assertNotNull(restarted.load())

            restarted.clearExpiredRecovery(Duration.ZERO)

            assertNull(RecoveryLedger(path, Duration.ofMinutes(1)).load())
        } finally {
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun grant(leaseId: String, resourceKey: String, clientId: String): LeaseGrant =
        LeaseGrant(
            requestId = "request-$leaseId",
            owner = LeaseOwner(clientId, processId = 1, label = clientId),
            token =
                LeaseToken(
                    coordinatorEpoch = "epoch",
                    leaseId = leaseId,
                    resourceKey = DesktopResourceKey(resourceKey),
                    fence = 1,
                ),
        )
}

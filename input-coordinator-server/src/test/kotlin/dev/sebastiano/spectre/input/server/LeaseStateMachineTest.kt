@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseErrorCode
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LeaseStateMachineTest {

    private val clock = FakeMonotonicClock()
    private val resource = DesktopResourceKey("user-1/desktop-1")
    private val ownerA = LeaseOwner(clientId = "client-a", processId = 10, label = "first")
    private val ownerB = LeaseOwner(clientId = "client-b", processId = 20, label = "second")
    private val ownerC = LeaseOwner(clientId = "client-c", processId = 30, label = "third")

    @Test
    fun `waiters are granted in FIFO order and cancellation preserves the remaining order`() {
        val machine = machine()
        val first = machine.acquire(request("a", ownerA)).grantedToken()
        assertEquals(1, machine.acquire(request("b", ownerB)).queuedPosition())
        assertEquals(2, machine.acquire(request("c", ownerC)).queuedPosition())

        assertTrue(machine.cancelWaiter("b"))
        val release = assertIs<ReleaseResult.Released>(machine.release(first))

        assertEquals(ownerC, release.nextGrant?.owner)
        assertEquals("c", release.nextGrant?.requestId)
    }

    @Test
    fun `same owner acquisition is reentrant and mismatched release is rejected`() {
        val machine = machine()
        val first = machine.acquire(request("a", ownerA)).grantedToken()
        val nested = machine.acquire(request("nested", ownerA)).grantedToken()
        assertEquals(first, nested)

        val mismatched = machine.release(first.copy(leaseId = "not-the-holder"))
        assertEquals(LeaseErrorCode.STALE_LEASE, mismatched.rejectedCode())

        assertIs<ReleaseResult.StillHeld>(machine.release(first))
        assertIs<ReleaseResult.Released>(machine.release(first))
    }

    @Test
    fun `timed out waiter reports holder metadata and its queue position`() {
        val machine = machine()
        val holder = machine.acquire(request("a", ownerA)).grantedToken()
        machine.acquire(request("b", ownerB, timeoutMillis = 50))
        clock.advanceBy(51)

        val timeout = assertNotNull(machine.expire().single().timeout)
        val timeoutHolder = assertNotNull(timeout.holder)

        assertEquals("b", timeout.requestId)
        assertEquals(1, timeout.queuePosition)
        assertEquals(holder.leaseId, timeoutHolder.token.leaseId)
        assertEquals(ownerA, timeoutHolder.owner)
        assertEquals(51, timeoutHolder.acquisitionAgeMillis)
        assertEquals(51, timeoutHolder.heartbeatAgeMillis)
    }

    @Test
    fun `release does not silently discard an expired waiter before timeout completion`() {
        val machine = machine()
        val holder = machine.acquire(request("a", ownerA)).grantedToken()
        machine.acquire(request("b", ownerB, timeoutMillis = 50))
        machine.acquire(request("c", ownerC, timeoutMillis = 500))
        clock.advanceBy(51)

        val released = assertIs<ReleaseResult.Released>(machine.release(holder))
        assertEquals(null, released.nextGrant)
        val events = machine.expire()

        assertTrue(events.any { it.timeout?.requestId == "b" }, "events=$events")
        assertTrue(events.any { it.grant?.owner == ownerC }, "events=$events")
    }

    @Test
    fun `heartbeat expiry fences holder but disconnect confirms release and grants next`() {
        val machine = machine(heartbeatTimeoutMillis = 100)
        val first = machine.acquire(request("a", ownerA)).grantedToken()
        machine.acquire(request("b", ownerB))
        clock.advanceBy(101)

        machine.expire()

        assertEquals(LeaseErrorCode.FENCED, machine.validate(first).rejectedCode())
        assertEquals(LeaseStatus.REVOKING, machine.status(resource).holder?.status)
        val disconnect = machine.disconnect(ownerA.clientId)
        assertEquals(ownerB, disconnect.grants.single().owner)
    }

    @Test
    fun `new coordinator epoch invalidates old tokens`() {
        val oldMachine = machine(epoch = "epoch-1")
        val oldToken = oldMachine.acquire(request("a", ownerA)).grantedToken()
        val newMachine = machine(epoch = "epoch-2")

        assertEquals(LeaseErrorCode.STALE_EPOCH, newMachine.validate(oldToken).rejectedCode())
    }

    @Test
    fun `restart recovery remains quarantined until exact-id unsafe recovery`() {
        val record =
            RecoveryRecord(
                resourceKey = resource,
                predecessorEpoch = "epoch-1",
                leaseId = "old-lease",
                owner = ownerA,
                heartbeatExpiryMillis = 100,
            )
        val machine = machine(recoveryRecord = record, recoveryGraceMillis = 25)

        assertEquals(1, machine.acquire(request("b", ownerB)).queuedPosition())
        assertEquals("old-lease", machine.status(resource).quarantine?.predecessorLeaseId)
        clock.advanceBy(1_000_000)
        assertTrue(machine.expire().none { it.grant != null })
        assertEquals("old-lease", machine.status(resource).quarantine?.predecessorLeaseId)

        val forcedMachine = machine(recoveryRecord = record)
        forcedMachine.acquire(request("c", ownerC))
        val stale = forcedMachine.forceRecover("another-lease", "operator", "known dead")
        assertEquals(LeaseErrorCode.STALE_LEASE, stale.rejectedCode())
        val forced =
            assertIs<RecoveryResult.Recovered>(
                forcedMachine.forceRecover("old-lease", "operator", "known dead")
            )
        assertTrue(forced.unsafeTakeover)
        assertEquals(ownerC, forced.nextGrants.singleOrNull()?.owner)
    }

    @Test
    fun `compare-and-revoke rejects stale ids then acknowledgement advances FIFO`() {
        val machine = machine()
        val token = machine.acquire(request("a", ownerA)).grantedToken()
        machine.acquire(request("b", ownerB))

        val stale =
            machine.revoke("old", requesterLabel = "operator", reason = "stuck", force = false)
        assertEquals(LeaseErrorCode.STALE_LEASE, stale.rejectedCode())
        val requested =
            assertIs<RevokeResult.Requested>(
                machine.revoke(
                    token.leaseId,
                    requesterLabel = "operator",
                    reason = "stuck",
                    force = false,
                )
            )
        assertFalse(requested.unsafeTakeover)
        assertEquals(LeaseErrorCode.FENCED, machine.validate(token).rejectedCode())

        val acknowledged = assertIs<RevokeResult.Acknowledged>(machine.acknowledgeRevocation(token))
        assertEquals(ownerB, acknowledged.nextGrant?.owner)
        assertTrue(machine.auditLog().single().acknowledged)
        assertFalse(machine.auditLog().single().unsafeTakeover)
    }

    @Test
    fun `revocation acknowledgement waits for every reentrant hold`() {
        val machine = machine()
        val token = machine.acquire(request("a-1", ownerA)).grantedToken()
        machine.acquire(request("a-2", ownerA))
        machine.acquire(request("b", ownerB))
        machine.revoke(token.leaseId, requesterLabel = "operator", reason = "stuck", force = false)

        val first = assertIs<RevokeResult.Acknowledged>(machine.acknowledgeRevocation(token))
        assertEquals(null, first.nextGrant)
        assertEquals(ownerA, machine.status(token.resourceKey).holder?.owner)

        val final = assertIs<RevokeResult.Acknowledged>(machine.acknowledgeRevocation(token))
        assertEquals(ownerB, final.nextGrant?.owner)
        assertEquals(1, machine.auditLog().size)
    }

    @Test
    fun `explicit force takeover requires grace and records unsafe outcome`() {
        val machine = machine(revokeGraceMillis = 20)
        val token = machine.acquire(request("a", ownerA)).grantedToken()
        machine.acquire(request("b", ownerB))
        machine.revoke(token.leaseId, requesterLabel = "operator", reason = "wedged", force = false)

        val tooEarly =
            machine.revoke(
                token.leaseId,
                requesterLabel = "operator",
                reason = "wedged",
                force = true,
            )
        assertEquals(LeaseErrorCode.REVOKE_GRACE_ACTIVE, tooEarly.rejectedCode())
        clock.advanceBy(20)
        val forced =
            assertIs<RevokeResult.Forced>(
                machine.revoke(
                    token.leaseId,
                    requesterLabel = "operator",
                    reason = "wedged",
                    force = true,
                )
            )

        assertTrue(forced.unsafeTakeover)
        assertEquals(ownerB, forced.nextGrant?.owner)
        assertFalse(machine.auditLog().single().acknowledged)
        assertTrue(machine.auditLog().single().unsafeTakeover)
    }

    @Test
    fun `fence generation prevents an older token from starting work`() {
        val machine = machine()
        val token = machine.acquire(request("a", ownerA)).grantedToken()
        machine.revoke(token.leaseId, requesterLabel = "operator", reason = "stop", force = false)

        assertEquals(LeaseErrorCode.FENCED, machine.validate(token).rejectedCode())
        assertTrue(machine.status(resource).holder!!.fence > token.fence)
    }

    @Test
    fun `bounded waiter queue rejects overflow with stable taxonomy`() {
        val machine = machine(maxWaiters = 1)
        machine.acquire(request("a", ownerA))
        machine.acquire(request("b", ownerB))

        val overflow = machine.acquire(request("c", ownerC))

        assertEquals(LeaseErrorCode.QUEUE_FULL, overflow.rejectedCode())
        assertEquals(1, machine.status(resource).waiters.size)
    }

    @Test
    fun `disconnect removes queued requests without disturbing holder`() {
        val machine = machine()
        val token = machine.acquire(request("a", ownerA)).grantedToken()
        machine.acquire(request("b", ownerB))

        val disconnect = machine.disconnect(ownerB.clientId)
        assertTrue(disconnect.grants.isEmpty())
        assertEquals(listOf("b"), disconnect.cancelledRequestIds)
        assertEquals(token, machine.status(resource).holder?.token)
        assertTrue(machine.status(resource).waiters.isEmpty())
    }

    private fun machine(
        epoch: String = "epoch",
        maxWaiters: Int = 8,
        heartbeatTimeoutMillis: Long = 1_000,
        revokeGraceMillis: Long = 10,
        recoveryGraceMillis: Long = 10,
        recoveryRecord: RecoveryRecord? = null,
    ): LeaseStateMachine =
        LeaseStateMachine(
            clock = clock,
            epoch = epoch,
            maxWaitersPerResource = maxWaiters,
            heartbeatTimeoutMillis = heartbeatTimeoutMillis,
            revokeGraceMillis = revokeGraceMillis,
            recoveryGraceMillis = recoveryGraceMillis,
            recoveryRecord = recoveryRecord,
            leaseIdGenerator = SequentialLeaseIdGenerator(),
        )

    private fun request(
        requestId: String,
        owner: LeaseOwner,
        timeoutMillis: Long = 10_000,
    ): AcquireRequest =
        AcquireRequest(
            requestId = requestId,
            resourceKey = resource,
            owner = owner,
            timeoutMillis = timeoutMillis,
            currentOperation = "test input",
        )
}

private fun AcquireResult.grantedToken(): LeaseToken =
    assertIs<AcquireResult.Granted>(this).grant.token

private fun AcquireResult.queuedPosition(): Int = assertIs<AcquireResult.Queued>(this).position

private fun AcquireResult.rejectedCode(): LeaseErrorCode =
    assertIs<AcquireResult.Rejected>(this).code

private fun ReleaseResult.rejectedCode(): LeaseErrorCode =
    assertIs<ReleaseResult.Rejected>(this).code

private fun ValidationResult.rejectedCode(): LeaseErrorCode =
    assertIs<ValidationResult.Rejected>(this).code

private fun RecoveryResult.rejectedCode(): LeaseErrorCode =
    assertIs<RecoveryResult.Rejected>(this).code

private fun RevokeResult.rejectedCode(): LeaseErrorCode = assertIs<RevokeResult.Rejected>(this).code

private class FakeMonotonicClock : MonotonicClock {
    private var currentMillis: Long = 0

    override fun nowMillis(): Long = currentMillis

    fun advanceBy(millis: Long) {
        currentMillis += millis
    }
}

private class SequentialLeaseIdGenerator : LeaseIdGenerator {
    private var next: Int = 1

    override fun nextId(): String = "lease-${next++}"
}

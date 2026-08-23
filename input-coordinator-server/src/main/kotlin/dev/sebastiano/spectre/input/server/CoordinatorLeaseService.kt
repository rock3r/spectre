@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorWireHolder
import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import dev.sebastiano.spectre.input.CoordinatorWireQuarantine
import dev.sebastiano.spectre.input.CoordinatorWireStatus
import dev.sebastiano.spectre.input.CoordinatorWireWaiter
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class CoordinatorLeaseService(
    val epoch: String,
    heartbeatTimeout: Duration,
    private val revokeGrace: Duration,
    private val recoveryGrace: Duration,
    recoveryRecord: RecoveryRecord? = null,
    private val recoveryLedger: RecoveryLedger? = null,
) : AutoCloseable {
    private val machine =
        LeaseStateMachine(
            clock = MonotonicClock { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
            epoch = epoch,
            maxWaitersPerResource = MAX_WAITERS_PER_RESOURCE,
            heartbeatTimeoutMillis = heartbeatTimeout.toMillis(),
            revokeGraceMillis = revokeGrace.toMillis(),
            recoveryGraceMillis = recoveryGrace.toMillis(),
            recoveryRecord = recoveryRecord,
            leaseIdGenerator = LeaseIdGenerator { UUID.randomUUID().toString() },
        )
    private val pendingAcquires = mutableMapOf<String, CompletableFuture<CoordinatorWireMessage>>()
    private val requestTracker = AcquisitionRequestTracker()
    private val wireMapper = CoordinatorWireMapper()
    private val expiryExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "spectre-input-expiry").apply { isDaemon = true }
    }

    init {
        expiryExecutor.scheduleWithFixedDelay(
            ::expire,
            EXPIRY_INTERVAL_MILLIS,
            EXPIRY_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    fun acquire(message: CoordinatorWireMessage): CompletableFuture<CoordinatorWireMessage> {
        val request = wireMapper.toAcquireRequest(message)
        if (!requestTracker.isConnected(request.owner.clientId)) {
            return CompletableFuture.completedFuture(
                error("CLIENT_DISCONNECTED", "Client session disconnected")
            )
        }
        if (requestTracker.consumeCancellation(request.owner.clientId, request.requestId)) {
            return CompletableFuture.completedFuture(error("ACQUIRE_CANCELLED", "Cancelled"))
        }
        return when (val result = machine.acquire(request)) {
            is AcquireResult.Granted -> {
                CompletableFuture.completedFuture(
                    recordGrantOrRollback(result.grant) ?: wireMapper.grant(result.grant)
                )
            }
            is AcquireResult.Queued ->
                if (message.waitForLease) {
                    CompletableFuture<CoordinatorWireMessage>().also { future ->
                        pendingAcquires[result.requestId] = future
                    }
                } else {
                    machine.cancelWaiter(result.requestId)
                    CompletableFuture.completedFuture(
                        error("LEASE_CONTENDED", "Desktop input lease is currently held")
                    )
                }
            is AcquireResult.Rejected ->
                CompletableFuture.completedFuture(
                    error(result.code.name, "Lease acquisition rejected")
                )
        }
    }

    @Synchronized
    fun release(message: CoordinatorWireMessage): CoordinatorWireMessage {
        val token = wireMapper.toToken(message)
        val response = releaseToken(token)
        if (response.ok) requestTracker.forgetOneGrant(token, requireNotNull(message.clientId))
        return response
    }

    private fun releaseToken(token: LeaseToken): CoordinatorWireMessage {
        val result =
            try {
                machine.release(token) { recoveryLedger?.clear(token.leaseId) }
            } catch (_: IOException) {
                return error("RECOVERY_PERSISTENCE_FAILED", "Could not persist lease release")
            }
        return when (result) {
            is ReleaseResult.Released -> {
                result.nextGrant?.let(::completeGrant)
                success()
            }
            is ReleaseResult.StillHeld -> success()
            is ReleaseResult.Rejected -> {
                if (result.code.name == "FENCED") {
                    when (val acknowledged = machine.acknowledgeRevocation(token)) {
                        is RevokeResult.Acknowledged -> {
                            if (acknowledged.remainingDepth == 0) {
                                recoveryLedger?.clear(token.leaseId)
                                requestTracker.forgetLease(token.leaseId)
                                acknowledged.nextGrant?.let(::completeGrant)
                            }
                            success()
                        }
                        is RevokeResult.Rejected ->
                            error(acknowledged.code.name, "Lease release was rejected")
                        else -> error(result.code.name, "Lease release was rejected")
                    }
                } else {
                    error(result.code.name, "Lease release was rejected")
                }
            }
        }
    }

    @Synchronized
    fun cancel(message: CoordinatorWireMessage): CoordinatorWireMessage {
        val requestId = requireNotNull(message.requestId)
        val clientId = requireNotNull(message.clientId)
        val cancellation = CancelledAcquire(clientId, requestId)
        val waiterRemoved = machine.cancelWaiter(requestId)
        val pending = pendingAcquires.remove(requestId)
        pending?.complete(error("ACQUIRE_CANCELLED", "Cancelled"))
        if (!waiterRemoved && pending == null) {
            val ambiguousGrant = requestTracker.takeGrant(clientId, requestId)
            if (ambiguousGrant == null) {
                // The operation connections are independent, so CANCEL can beat ACQUIRE. Retain a
                // bounded tombstone so the delayed request cannot recreate the cancelled grant.
                requestTracker.rememberCancellation(cancellation)
            } else {
                // ACQUIRE won but its response was lost. Release only that exact hold; unrelated
                // requests sharing the client session remain live.
                releaseToken(ambiguousGrant)
            }
        }
        return success()
    }

    @Synchronized
    fun heartbeat(message: CoordinatorWireMessage): CoordinatorWireMessage =
        when (val result = machine.heartbeat(wireMapper.toToken(message))) {
            ValidationResult.Valid -> {
                recoveryLedger?.heartbeat(wireMapper.toToken(message))
                success()
            }
            is ValidationResult.Rejected -> error(result.code.name, "Lease heartbeat was rejected")
        }

    @Synchronized
    fun openSession(clientId: String) {
        requestTracker.markConnected(clientId)
    }

    @Synchronized
    fun disconnect(clientId: String) {
        requestTracker.markDisconnected(clientId)
        recoveryLedger?.clearClient(clientId)
        requestTracker.forgetClient(clientId)
        val result = machine.disconnect(clientId)
        result.cancelledRequestIds.forEach { requestId ->
            pendingAcquires
                .remove(requestId)
                ?.complete(error("CLIENT_DISCONNECTED", "Client session disconnected"))
        }
        result.grants.forEach(::completeGrant)
    }

    @Synchronized
    fun status(message: CoordinatorWireMessage): CoordinatorWireMessage {
        val resourceKey = DesktopResourceKey(requireNotNull(message.resourceKey))
        val snapshot = machine.status(resourceKey)
        return success(
            status =
                CoordinatorWireStatus(
                    resourceKey = resourceKey.value,
                    holder = snapshot.holder?.let(wireMapper::holder),
                    waiters = snapshot.waiters.map(wireMapper::waiter),
                    quarantine = snapshot.quarantine?.let(wireMapper::quarantine),
                )
        )
    }

    @Synchronized
    fun revoke(message: CoordinatorWireMessage): CoordinatorWireMessage {
        val leaseId = requireNotNull(message.leaseId)
        val requesterLabel = requireNotNull(message.requesterLabel)
        val reason = requireNotNull(message.reason)
        val quarantine =
            machine.status(DesktopResourceKey(requireNotNull(message.resourceKey))).quarantine
        if (quarantine?.predecessorLeaseId == leaseId && message.force) {
            return when (val result = machine.forceRecover(leaseId, requesterLabel, reason)) {
                is RecoveryResult.Recovered -> {
                    recoveryLedger?.clear(leaseId)
                    requestTracker.forgetLease(leaseId)
                    result.nextGrants.forEach(::completeGrant)
                    success(unsafeTakeover = result.unsafeTakeover)
                }
                is RecoveryResult.Rejected -> error(result.code.name, "Recovery was rejected")
            }
        }
        return when (val result = machine.revoke(leaseId, requesterLabel, reason, message.force)) {
            is RevokeResult.Requested -> success(unsafeTakeover = result.unsafeTakeover)
            is RevokeResult.Forced -> {
                recoveryLedger?.clear(leaseId)
                requestTracker.forgetLease(leaseId)
                result.nextGrant?.let(::completeGrant)
                success(unsafeTakeover = result.unsafeTakeover)
            }
            is RevokeResult.Acknowledged -> {
                if (result.remainingDepth == 0) {
                    recoveryLedger?.clear(leaseId)
                    requestTracker.forgetLease(leaseId)
                    result.nextGrant?.let(::completeGrant)
                }
                success()
            }
            is RevokeResult.Rejected ->
                error(
                    result.code.name,
                    if (result.code.name == "REVOKE_GRACE_ACTIVE") {
                        "Revoke grace is still active for ${revokeGrace.toMillis()} ms"
                    } else {
                        "Lease revoke was rejected"
                    },
                )
        }
    }

    override fun close() {
        expiryExecutor.shutdownNow()
        synchronized(this) {
            pendingAcquires.values.forEach { future ->
                future.complete(error("COORDINATOR_CLOSED", "Input coordinator stopped"))
            }
            pendingAcquires.clear()
        }
    }

    @Synchronized
    private fun expire() {
        recoveryLedger?.clearExpiredRecovery(recoveryGrace)
        machine.expire().forEach { event ->
            event.grant?.let(::completeGrant)
            event.timeout?.let { timeout ->
                pendingAcquires
                    .remove(timeout.requestId)
                    ?.complete(
                        error(
                            code = "ACQUIRE_TIMEOUT",
                            message =
                                "Timed out for ${timeout.resourceKey.value} at queue position " +
                                    "${timeout.queuePosition}; ${timeout.diagnosticContext()}",
                        )
                    )
            }
        }
    }

    private fun completeGrant(grant: LeaseGrant) {
        val response = recordGrantOrRollback(grant) ?: wireMapper.grant(grant)
        pendingAcquires.remove(grant.requestId)?.complete(response)
    }

    private fun recordGrantOrRollback(grant: LeaseGrant): CoordinatorWireMessage? {
        try {
            recoveryLedger?.record(grant)
        } catch (_: IOException) {
            val rollback = machine.release(grant.token, advanceQueue = false)
            check(rollback !is ReleaseResult.Rejected) {
                "Could not roll back an unpersisted input lease grant"
            }
            return error(
                "RECOVERY_PERSISTENCE_FAILED",
                "Could not persist the input lease recovery record",
            )
        }
        requestTracker.rememberGrant(grant)
        return null
    }

    private fun success(
        coordinatorEpoch: String? = null,
        leaseId: String? = null,
        fence: Long? = null,
        unsafeTakeover: Boolean = false,
        status: CoordinatorWireStatus? = null,
    ): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.RESPONSE,
            ok = true,
            coordinatorEpoch = coordinatorEpoch,
            leaseId = leaseId,
            fence = fence,
            unsafeTakeover = unsafeTakeover,
            status = status,
        )

    private fun error(code: String, message: String): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.RESPONSE,
            ok = false,
            errorCode = code,
            message = message,
        )

    private companion object {
        const val MAX_WAITERS_PER_RESOURCE: Int = 128
        const val EXPIRY_INTERVAL_MILLIS: Long = 10
    }
}

private class AcquisitionRequestTracker {
    private val cancelledBeforeAcquire = LinkedHashSet<CancelledAcquire>()
    private val connectedClients = mutableSetOf<String>()
    private val grantsByRequest = mutableMapOf<CancelledAcquire, LeaseToken>()

    fun isConnected(clientId: String): Boolean = clientId in connectedClients

    fun markConnected(clientId: String) {
        connectedClients += clientId
    }

    fun markDisconnected(clientId: String) {
        connectedClients -= clientId
    }

    fun consumeCancellation(clientId: String, requestId: String): Boolean =
        cancelledBeforeAcquire.remove(CancelledAcquire(clientId, requestId))

    fun rememberCancellation(cancellation: CancelledAcquire) {
        if (cancelledBeforeAcquire.size >= MAX_CANCELLATION_TOMBSTONES) {
            val oldest = cancelledBeforeAcquire.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        cancelledBeforeAcquire += cancellation
    }

    fun rememberGrant(grant: LeaseGrant) {
        grantsByRequest[CancelledAcquire(grant.owner.clientId, grant.requestId)] = grant.token
    }

    fun takeGrant(clientId: String, requestId: String): LeaseToken? =
        grantsByRequest.remove(CancelledAcquire(clientId, requestId))

    fun forgetOneGrant(token: LeaseToken, clientId: String) {
        val key =
            grantsByRequest.entries
                .firstOrNull { (request, grantedToken) ->
                    request.clientId == clientId && grantedToken == token
                }
                ?.key
        if (key != null) grantsByRequest.remove(key)
    }

    fun forgetClient(clientId: String) {
        grantsByRequest.keys.removeAll { it.clientId == clientId }
    }

    fun forgetLease(leaseId: String) {
        grantsByRequest.entries.removeAll { it.value.leaseId == leaseId }
    }

    private companion object {
        const val MAX_CANCELLATION_TOMBSTONES: Int = 1_024
    }
}

private data class CancelledAcquire(val clientId: String, val requestId: String)

private fun AcquisitionTimeout.diagnosticContext(): String =
    holder?.let { snapshot ->
        "holder ${snapshot.token.leaseId} owner=${snapshot.owner.label} " +
            "pid=${snapshot.owner.processId} age=${snapshot.acquisitionAgeMillis}ms " +
            "heartbeatAge=${snapshot.heartbeatAgeMillis}ms " +
            "operation=${snapshot.currentOperation}"
    }
        ?: quarantine?.let { snapshot ->
            "recovery quarantine predecessor=${snapshot.predecessorLeaseId} " +
                "owner=${snapshot.owner.label} pid=${snapshot.owner.processId} " +
                "releaseEligibleAt=${snapshot.releaseEligibleAtMillis}"
        }
        ?: "holder unavailable"

private class CoordinatorWireMapper {
    fun toAcquireRequest(message: CoordinatorWireMessage): AcquireRequest =
        AcquireRequest(
            requestId = requireNotNull(message.requestId),
            resourceKey = DesktopResourceKey(requireNotNull(message.resourceKey)),
            owner =
                LeaseOwner(
                    clientId = requireNotNull(message.clientId),
                    processId = requireNotNull(message.processId),
                    label = message.ownerLabel,
                ),
            timeoutMillis = requireNotNull(message.timeoutMillis),
            currentOperation = message.currentOperation,
        )

    fun toToken(message: CoordinatorWireMessage): LeaseToken =
        LeaseToken(
            coordinatorEpoch = requireNotNull(message.coordinatorEpoch),
            leaseId = requireNotNull(message.leaseId),
            resourceKey = DesktopResourceKey(requireNotNull(message.resourceKey)),
            fence = requireNotNull(message.fence),
        )

    fun grant(grant: LeaseGrant): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = CoordinatorWireKind.RESPONSE,
            coordinatorEpoch = grant.token.coordinatorEpoch,
            leaseId = grant.token.leaseId,
            fence = grant.token.fence,
        )

    fun holder(snapshot: HolderSnapshot): CoordinatorWireHolder =
        CoordinatorWireHolder(
            leaseId = snapshot.token.leaseId,
            clientId = snapshot.owner.clientId,
            processId = snapshot.owner.processId,
            ownerLabel = snapshot.owner.label,
            state = snapshot.status.name.lowercase(),
            fence = snapshot.fence,
            acquisitionAgeMillis = snapshot.acquisitionAgeMillis,
            heartbeatAgeMillis = snapshot.heartbeatAgeMillis,
            currentOperation = snapshot.currentOperation,
        )

    fun waiter(snapshot: WaiterSnapshot): CoordinatorWireWaiter =
        CoordinatorWireWaiter(
            requestId = snapshot.requestId,
            clientId = snapshot.owner.clientId,
            processId = snapshot.owner.processId,
            ownerLabel = snapshot.owner.label,
            position = snapshot.position,
        )

    fun quarantine(snapshot: QuarantineSnapshot): CoordinatorWireQuarantine =
        CoordinatorWireQuarantine(
            predecessorLeaseId = snapshot.predecessorLeaseId,
            predecessorEpoch = snapshot.predecessorEpoch,
            clientId = snapshot.owner.clientId,
            processId = snapshot.owner.processId,
            ownerLabel = snapshot.owner.label,
            releaseEligibleAtMillis = snapshot.releaseEligibleAtMillis,
        )
}

@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseErrorCode
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken

internal fun interface MonotonicClock {
    fun nowMillis(): Long
}

internal fun interface LeaseIdGenerator {
    fun nextId(): String
}

internal data class AcquireRequest(
    val requestId: String,
    val resourceKey: DesktopResourceKey,
    val owner: LeaseOwner,
    val timeoutMillis: Long,
    val currentOperation: String?,
)

internal sealed interface AcquireResult {
    data class Granted(val grant: LeaseGrant) : AcquireResult

    data class Queued(val requestId: String, val position: Int) : AcquireResult

    data class Rejected(val code: LeaseErrorCode) : AcquireResult
}

internal data class LeaseGrant(val requestId: String, val owner: LeaseOwner, val token: LeaseToken)

internal sealed interface ReleaseResult {
    data class Released(val nextGrant: LeaseGrant?) : ReleaseResult

    data class StillHeld(val remainingDepth: Int) : ReleaseResult

    data class Rejected(val code: LeaseErrorCode) : ReleaseResult
}

internal sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Rejected(val code: LeaseErrorCode) : ValidationResult
}

internal enum class LeaseStatus {
    HELD,
    REVOKING,
}

internal data class HolderSnapshot(
    val token: LeaseToken,
    val owner: LeaseOwner,
    val status: LeaseStatus,
    val fence: Long,
    val acquisitionAgeMillis: Long,
    val heartbeatAgeMillis: Long,
    val currentOperation: String?,
)

internal data class WaiterSnapshot(val requestId: String, val owner: LeaseOwner, val position: Int)

internal data class QuarantineSnapshot(
    val predecessorLeaseId: String,
    val predecessorEpoch: String,
    val owner: LeaseOwner,
    val releaseEligibleAtMillis: Long,
)

internal data class ResourceSnapshot(
    val holder: HolderSnapshot?,
    val waiters: List<WaiterSnapshot>,
    val quarantine: QuarantineSnapshot?,
)

internal data class AcquisitionTimeout(
    val requestId: String,
    val resourceKey: DesktopResourceKey,
    val queuePosition: Int,
    val holder: HolderSnapshot?,
    val quarantine: QuarantineSnapshot?,
)

internal data class ExpiryEvent(
    val timeout: AcquisitionTimeout? = null,
    val grant: LeaseGrant? = null,
) {
    val owner: LeaseOwner?
        get() = grant?.owner
}

internal data class DisconnectResult(
    val grants: List<LeaseGrant>,
    val cancelledRequestIds: List<String>,
)

internal data class RecoveryRecord(
    val resourceKey: DesktopResourceKey,
    val predecessorEpoch: String,
    val leaseId: String,
    val owner: LeaseOwner,
    val heartbeatExpiryMillis: Long,
    val blocksAllResources: Boolean = false,
)

internal sealed interface RecoveryResult {
    data class Recovered(val unsafeTakeover: Boolean, val nextGrants: List<LeaseGrant>) :
        RecoveryResult

    data class Rejected(val code: LeaseErrorCode) : RecoveryResult
}

internal sealed interface RevokeResult {
    data class Requested(val unsafeTakeover: Boolean = false) : RevokeResult

    data class Acknowledged(val nextGrant: LeaseGrant?, val remainingDepth: Int = 0) : RevokeResult

    data class Forced(val unsafeTakeover: Boolean = true, val nextGrant: LeaseGrant?) : RevokeResult

    data class Rejected(val code: LeaseErrorCode) : RevokeResult
}

internal data class RevokeAuditRecord(
    val timestampMillis: Long,
    val leaseId: String,
    val previousOwner: LeaseOwner,
    val requesterLabel: String,
    val reason: String,
    val acknowledged: Boolean,
    val unsafeTakeover: Boolean,
)

internal class LeaseStateMachine(
    private val clock: MonotonicClock,
    private val epoch: String,
    private val maxWaitersPerResource: Int,
    private val heartbeatTimeoutMillis: Long,
    private val revokeGraceMillis: Long,
    private val recoveryGraceMillis: Long,
    recoveryRecord: RecoveryRecord?,
    private val leaseIdGenerator: LeaseIdGenerator,
) {
    private val resources = mutableMapOf<DesktopResourceKey, ResourceState>()
    private var globalQuarantine: RecoveryQuarantine? = null
    private val auditRecords = mutableListOf<RevokeAuditRecord>()
    private val transitions = LeaseTransitions(clock, epoch, leaseIdGenerator)

    init {
        require(epoch.isNotBlank()) { "Coordinator epoch must not be blank" }
        require(maxWaitersPerResource >= 0) { "Maximum waiters must not be negative" }
        require(heartbeatTimeoutMillis > 0) { "Heartbeat timeout must be positive" }
        require(revokeGraceMillis >= 0) { "Revoke grace must not be negative" }
        require(recoveryGraceMillis >= 0) { "Recovery grace must not be negative" }
        recoveryRecord?.let { record ->
            val quarantine =
                RecoveryQuarantine(
                    record = record,
                    releaseEligibleAtMillis =
                        saturatingAdd(record.heartbeatExpiryMillis, recoveryGraceMillis),
                )
            if (record.blocksAllResources) {
                globalQuarantine = quarantine
            } else {
                resources[record.resourceKey] = ResourceState(quarantine = quarantine)
            }
        }
    }

    fun acquire(request: AcquireRequest): AcquireResult {
        require(request.requestId.isNotBlank()) { "Request ID must not be blank" }
        require(request.timeoutMillis >= 0) { "Acquire timeout must not be negative" }
        val state = resources.getOrPut(request.resourceKey) { ResourceState() }
        val holder = state.holder
        if (holder != null && holder.owner.clientId == request.owner.clientId) {
            if (holder.status != LeaseStatus.HELD) {
                return AcquireResult.Rejected(LeaseErrorCode.FENCED)
            }
            holder.depth += 1
            return AcquireResult.Granted(LeaseGrant(request.requestId, request.owner, holder.token))
        }
        if (
            holder == null &&
                state.quarantine == null &&
                globalQuarantine == null &&
                state.waiters.isEmpty()
        ) {
            return AcquireResult.Granted(transitions.grant(state, request))
        }
        if (state.waiters.size >= maxWaitersPerResource) {
            return AcquireResult.Rejected(LeaseErrorCode.QUEUE_FULL)
        }
        state.waiters +=
            Waiter(
                request = request,
                enqueuedAtMillis = clock.nowMillis(),
                deadlineMillis = saturatingAdd(clock.nowMillis(), request.timeoutMillis),
            )
        return AcquireResult.Queued(request.requestId, state.waiters.size)
    }

    fun cancelWaiter(requestId: String): Boolean {
        resources.values.forEach { state ->
            val removed = state.waiters.removeAll { it.request.requestId == requestId }
            if (removed) return true
        }
        return false
    }

    fun release(
        token: LeaseToken,
        advanceQueue: Boolean = true,
        beforeFinalRelease: () -> Unit = {},
    ): ReleaseResult {
        val state =
            resources[token.resourceKey]
                ?: return ReleaseResult.Rejected(LeaseErrorCode.STALE_LEASE)
        val holder = state.holder ?: return ReleaseResult.Rejected(LeaseErrorCode.STALE_LEASE)
        transitions.validationError(token, holder)?.let {
            return ReleaseResult.Rejected(it)
        }
        if (holder.depth > 1) {
            holder.depth -= 1
            return ReleaseResult.StillHeld(holder.depth)
        }
        beforeFinalRelease()
        state.holder = null
        return ReleaseResult.Released(if (advanceQueue) transitions.grantNext(state) else null)
    }

    fun heartbeat(token: LeaseToken): ValidationResult {
        if (token.coordinatorEpoch != epoch) {
            return ValidationResult.Rejected(LeaseErrorCode.STALE_EPOCH)
        }
        val holder =
            resources[token.resourceKey]?.holder
                ?: return ValidationResult.Rejected(LeaseErrorCode.STALE_LEASE)
        transitions.validationError(token, holder)?.let {
            return ValidationResult.Rejected(it)
        }
        holder.lastHeartbeatMillis = clock.nowMillis()
        return ValidationResult.Valid
    }

    fun validate(token: LeaseToken): ValidationResult {
        if (token.coordinatorEpoch != epoch) {
            return ValidationResult.Rejected(LeaseErrorCode.STALE_EPOCH)
        }
        val holder =
            resources[token.resourceKey]?.holder
                ?: return ValidationResult.Rejected(LeaseErrorCode.STALE_LEASE)
        val error = transitions.validationError(token, holder)
        return if (error == null) ValidationResult.Valid else ValidationResult.Rejected(error)
    }

    fun disconnect(clientId: String): DisconnectResult {
        val grants = mutableListOf<LeaseGrant>()
        val cancelledRequestIds = mutableListOf<String>()
        resources.values.forEach { state ->
            val disconnectedWaiters = state.waiters.filter { it.request.owner.clientId == clientId }
            cancelledRequestIds += disconnectedWaiters.map { it.request.requestId }
            state.waiters.removeAll(disconnectedWaiters.toSet())
            if (state.holder?.owner?.clientId == clientId) {
                state.holder = null
                transitions.grantNext(state)?.let(grants::add)
            }
        }
        return DisconnectResult(grants, cancelledRequestIds)
    }

    fun expire(): List<ExpiryEvent> {
        val now = clock.nowMillis()
        val events = mutableListOf<ExpiryEvent>()
        resources.forEach { (resourceKey, state) ->
            expireWaiters(resourceKey, state, now, events, globalQuarantine)
            val holder = state.holder
            if (
                holder != null &&
                    holder.status == LeaseStatus.HELD &&
                    now - holder.lastHeartbeatMillis > heartbeatTimeoutMillis
            ) {
                transitions.beginRevocation(
                    state,
                    holder,
                    requesterLabel = "coordinator",
                    reason = "heartbeat expired",
                )
            }
            if (state.holder == null && state.quarantine == null && globalQuarantine == null) {
                transitions.grantNext(state)?.let { events += ExpiryEvent(grant = it) }
            }
        }
        return events
    }

    fun status(resourceKey: DesktopResourceKey): ResourceSnapshot {
        val state = resources[resourceKey] ?: ResourceState()
        val now = clock.nowMillis()
        val holder = state.holder
        return ResourceSnapshot(
            holder = holder?.toSnapshot(now),
            waiters =
                state.waiters.mapIndexed { index, waiter ->
                    WaiterSnapshot(
                        requestId = waiter.request.requestId,
                        owner = waiter.request.owner,
                        position = index + 1,
                    )
                },
            quarantine = (state.quarantine ?: globalQuarantine)?.toSnapshot(),
        )
    }

    fun forceRecover(
        observedLeaseId: String,
        requesterLabel: String,
        reason: String,
        beforeRecovery: () -> Unit = {},
    ): RecoveryResult {
        globalQuarantine?.let { quarantine ->
            if (quarantine.record.leaseId != observedLeaseId) {
                return RecoveryResult.Rejected(LeaseErrorCode.STALE_LEASE)
            }
            beforeRecovery()
            globalQuarantine = null
            auditRecords.recordRecoveryTakeover(
                clock.nowMillis(),
                quarantine,
                observedLeaseId,
                requesterLabel,
                reason,
            )
            return RecoveryResult.Recovered(
                unsafeTakeover = true,
                nextGrants = resources.values.mapNotNull(transitions::grantNext),
            )
        }
        val (entry, quarantine) =
            resources.values.firstNotNullOfOrNull { state ->
                state.quarantine?.let { quarantine -> state to quarantine }
            } ?: return RecoveryResult.Rejected(LeaseErrorCode.STALE_LEASE)
        if (quarantine.record.leaseId != observedLeaseId) {
            return RecoveryResult.Rejected(LeaseErrorCode.STALE_LEASE)
        }
        beforeRecovery()
        entry.quarantine = null
        auditRecords.recordRecoveryTakeover(
            clock.nowMillis(),
            quarantine,
            observedLeaseId,
            requesterLabel,
            reason,
        )
        return RecoveryResult.Recovered(
            unsafeTakeover = true,
            nextGrants = listOfNotNull(transitions.grantNext(entry)),
        )
    }

    fun revoke(
        observedLeaseId: String,
        requesterLabel: String,
        reason: String,
        force: Boolean,
        beforeForcedRevocation: () -> Unit = {},
    ): RevokeResult {
        val (state, holder) =
            resources.values.firstNotNullOfOrNull { state ->
                state.holder
                    ?.takeIf { holder -> holder.token.leaseId == observedLeaseId }
                    ?.let { holder -> state to holder }
            } ?: return RevokeResult.Rejected(LeaseErrorCode.STALE_LEASE)
        if (holder.status == LeaseStatus.HELD) {
            transitions.beginRevocation(state, holder, requesterLabel, reason)
            if (force && revokeGraceMillis == 0L) {
                beforeForcedRevocation()
                return forceRevocation(state, holder)
            }
            return if (force) {
                RevokeResult.Rejected(LeaseErrorCode.REVOKE_GRACE_ACTIVE)
            } else {
                RevokeResult.Requested()
            }
        }
        val requestedAt = checkNotNull(holder.revocation).requestedAtMillis
        if (!force) return RevokeResult.Requested()
        if (clock.nowMillis() - requestedAt < revokeGraceMillis) {
            return RevokeResult.Rejected(LeaseErrorCode.REVOKE_GRACE_ACTIVE)
        }
        beforeForcedRevocation()
        return forceRevocation(state, holder)
    }

    fun acknowledgeRevocation(
        token: LeaseToken,
        beforeFinalAcknowledgement: () -> Unit = {},
    ): RevokeResult {
        if (token.coordinatorEpoch != epoch) {
            return RevokeResult.Rejected(LeaseErrorCode.STALE_EPOCH)
        }
        val state =
            resources[token.resourceKey] ?: return RevokeResult.Rejected(LeaseErrorCode.STALE_LEASE)
        val holder = state.holder ?: return RevokeResult.Rejected(LeaseErrorCode.STALE_LEASE)
        if (holder.token.leaseId != token.leaseId) {
            return RevokeResult.Rejected(LeaseErrorCode.STALE_LEASE)
        }
        val revocation =
            holder.revocation ?: return RevokeResult.Rejected(LeaseErrorCode.STALE_LEASE)
        if (holder.depth > 1) {
            holder.depth -= 1
            return RevokeResult.Acknowledged(nextGrant = null, remainingDepth = holder.depth)
        }
        beforeFinalAcknowledgement()
        recordRevocation(holder, revocation, acknowledged = true, unsafeTakeover = false)
        state.holder = null
        return RevokeResult.Acknowledged(transitions.grantNext(state))
    }

    fun auditLog(): List<RevokeAuditRecord> = auditRecords.toList()

    private fun expireWaiters(
        resourceKey: DesktopResourceKey,
        state: ResourceState,
        now: Long,
        events: MutableList<ExpiryEvent>,
        globalQuarantine: RecoveryQuarantine?,
    ) {
        val iterator = state.waiters.listIterator()
        var position = 1
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (now >= waiter.deadlineMillis) {
                val holder = state.holder
                val quarantine = state.quarantine ?: globalQuarantine
                events +=
                    ExpiryEvent(
                        timeout =
                            AcquisitionTimeout(
                                requestId = waiter.request.requestId,
                                resourceKey = resourceKey,
                                queuePosition = position,
                                holder = holder?.toSnapshot(now),
                                quarantine = quarantine?.toSnapshot(),
                            )
                    )
                iterator.remove()
            } else {
                position += 1
            }
        }
    }

    private fun forceRevocation(state: ResourceState, holder: Holder): RevokeResult.Forced {
        val revocation = checkNotNull(holder.revocation)
        recordRevocation(holder, revocation, acknowledged = false, unsafeTakeover = true)
        state.holder = null
        return RevokeResult.Forced(nextGrant = transitions.grantNext(state))
    }

    private fun recordRevocation(
        holder: Holder,
        revocation: Revocation,
        acknowledged: Boolean,
        unsafeTakeover: Boolean,
    ) {
        auditRecords +=
            RevokeAuditRecord(
                timestampMillis = clock.nowMillis(),
                leaseId = holder.token.leaseId,
                previousOwner = holder.owner,
                requesterLabel = revocation.requesterLabel,
                reason = revocation.reason,
                acknowledged = acknowledged,
                unsafeTakeover = unsafeTakeover,
            )
    }

    private companion object {
        fun saturatingAdd(value: Long, increment: Long): Long =
            if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment
    }
}

private class LeaseTransitions(
    private val clock: MonotonicClock,
    private val epoch: String,
    private val leaseIdGenerator: LeaseIdGenerator,
) {
    fun grant(state: ResourceState, request: AcquireRequest): LeaseGrant {
        state.fenceGeneration += 1
        val token =
            LeaseToken(
                coordinatorEpoch = epoch,
                leaseId = leaseIdGenerator.nextId(),
                resourceKey = request.resourceKey,
                fence = state.fenceGeneration,
            )
        val now = clock.nowMillis()
        state.holder =
            Holder(
                token = token,
                owner = request.owner,
                acquiredAtMillis = now,
                lastHeartbeatMillis = now,
                currentOperation = request.currentOperation,
            )
        return LeaseGrant(request.requestId, request.owner, token)
    }

    fun grantNext(state: ResourceState): LeaseGrant? {
        if (state.holder != null || state.quarantine != null) return null
        val waiter = state.waiters.firstOrNull() ?: return null
        if (clock.nowMillis() >= waiter.deadlineMillis) return null
        state.waiters.removeFirst()
        return grant(state, waiter.request)
    }

    fun beginRevocation(
        state: ResourceState,
        holder: Holder,
        requesterLabel: String,
        reason: String,
    ) {
        state.fenceGeneration += 1
        holder.status = LeaseStatus.REVOKING
        holder.fence = state.fenceGeneration
        holder.revocation = Revocation(clock.nowMillis(), requesterLabel, reason)
    }

    fun validationError(token: LeaseToken, holder: Holder): LeaseErrorCode? =
        when {
            token.coordinatorEpoch != epoch -> LeaseErrorCode.STALE_EPOCH
            token.leaseId != holder.token.leaseId -> LeaseErrorCode.STALE_LEASE
            token.fence != holder.fence || holder.status != LeaseStatus.HELD ->
                LeaseErrorCode.FENCED
            else -> null
        }
}

private data class ResourceState(
    var holder: Holder? = null,
    val waiters: MutableList<Waiter> = mutableListOf(),
    var fenceGeneration: Long = 0,
    var quarantine: RecoveryQuarantine? = null,
)

private data class Holder(
    val token: LeaseToken,
    val owner: LeaseOwner,
    val acquiredAtMillis: Long,
    var lastHeartbeatMillis: Long,
    val currentOperation: String?,
    var depth: Int = 1,
    var status: LeaseStatus = LeaseStatus.HELD,
    var fence: Long = token.fence,
    var revocation: Revocation? = null,
) {
    fun toSnapshot(now: Long): HolderSnapshot =
        HolderSnapshot(
            token = token,
            owner = owner,
            status = status,
            fence = fence,
            acquisitionAgeMillis = now - acquiredAtMillis,
            heartbeatAgeMillis = now - lastHeartbeatMillis,
            currentOperation = currentOperation,
        )
}

private data class Waiter(
    val request: AcquireRequest,
    val enqueuedAtMillis: Long,
    val deadlineMillis: Long,
)

private data class Revocation(
    val requestedAtMillis: Long,
    val requesterLabel: String,
    val reason: String,
)

private data class RecoveryQuarantine(val record: RecoveryRecord, val releaseEligibleAtMillis: Long)

private fun RecoveryQuarantine.toSnapshot(): QuarantineSnapshot =
    QuarantineSnapshot(
        predecessorLeaseId = record.leaseId,
        predecessorEpoch = record.predecessorEpoch,
        owner = record.owner,
        releaseEligibleAtMillis = releaseEligibleAtMillis,
    )

private fun MutableList<RevokeAuditRecord>.recordRecoveryTakeover(
    timestampMillis: Long,
    quarantine: RecoveryQuarantine,
    observedLeaseId: String,
    requesterLabel: String,
    reason: String,
) {
    this +=
        RevokeAuditRecord(
            timestampMillis = timestampMillis,
            leaseId = observedLeaseId,
            previousOwner = quarantine.record.owner,
            requesterLabel = requesterLabel,
            reason = reason,
            acknowledged = false,
            unsafeTakeover = true,
        )
}

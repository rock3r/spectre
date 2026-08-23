@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Public, redacted holder diagnostics returned by coordinator inspection. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorHolderStatus(
    public val leaseId: String,
    public val owner: LeaseOwner,
    public val state: String,
    public val fence: Long,
    public val acquisitionAgeMillis: Long,
    public val heartbeatAgeMillis: Long,
    public val currentOperation: String?,
)

/** Public, redacted queued-owner diagnostics returned by coordinator inspection. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorWaiterStatus(
    public val requestId: String,
    public val owner: LeaseOwner,
    public val position: Int,
)

/** Public recovery quarantine details returned by coordinator inspection. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorQuarantineStatus(
    public val predecessorLeaseId: String,
    public val predecessorEpoch: String,
    public val owner: LeaseOwner,
    public val releaseEligibleAtMillis: Long,
)

/** Current redacted coordination state for one desktop key. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorStatus(
    public val resourceKey: DesktopResourceKey,
    public val holder: CoordinatorHolderStatus?,
    public val waiters: List<CoordinatorWaiterStatus>,
    public val quarantine: CoordinatorQuarantineStatus?,
)

/** Result of an observe/control request that deliberately never launches a coordinator. */
@ExperimentalSpectreInputCoordinationApi
public sealed interface CoordinatorControlResult {
    public data object NoActiveCoordinator : CoordinatorControlResult

    public data class Active(public val status: CoordinatorStatus) : CoordinatorControlResult
}

/** Result of an exact-ID revoke request. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorRevokeResult(public val unsafeTakeover: Boolean)

/** Stable coordinator acquisition or fencing failure. */
@ExperimentalSpectreInputCoordinationApi
public class InputCoordinatorException(public val errorCode: String, message: String) :
    IllegalStateException(message)

/** One epoch- and generation-fenced desktop input lease. */
@ExperimentalSpectreInputCoordinationApi
public interface CoordinatedInputLease : AutoCloseable {
    public val token: LeaseToken

    /** Returns false after release, coordinator disconnect, epoch change, or fencing. */
    public fun isValid(): Boolean

    /** Fails closed when the lease can no longer start coordinated work. */
    public fun checkpoint()

    override fun close()
}

/** Client session for one desktop resource and one local owner identity. */
@ExperimentalSpectreInputCoordinationApi
public class LocalInputCoordinatorClient
private constructor(
    private val endpoint: CoordinatorEndpoint,
    private val resourceKey: DesktopResourceKey,
    private val clientId: String,
    private val owner: LeaseOwner,
    private val sessionChannel: SocketChannel,
    public val coordinatorEpoch: String,
    private val codec: CoordinatorWireCodec,
    private val heartbeatExecutor: ScheduledExecutorService,
) : AutoCloseable {
    private val connected = AtomicBoolean(true)
    private val closed = AtomicBoolean()
    private val leaseRegistrations = ConcurrentHashMap<String, LeaseRegistration>()

    init {
        Thread.ofVirtual().name("spectre-input-session-watch").start {
            try {
                codec.readOrNull(sessionChannel)
            } catch (_: IOException) {
                // Connection loss is represented by the shared connected fence below.
            } finally {
                close()
            }
        }
        heartbeatExecutor.scheduleWithFixedDelay(
            ::heartbeatAll,
            HEARTBEAT_INTERVAL_MILLIS,
            HEARTBEAT_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Acquires or queues for this client's desktop resource for at most [timeout]. */
    public fun acquire(timeout: Duration, currentOperation: String? = null): CoordinatedInputLease {
        return acquire(timeout, currentOperation, waitForLease = true)
    }

    /** Acquires only when immediately available, without joining the FIFO waiter queue. */
    public fun tryAcquire(currentOperation: String? = null): CoordinatedInputLease {
        return acquire(MINIMUM_ACQUIRE_TIMEOUT, currentOperation, waitForLease = false)
    }

    private fun acquire(
        timeout: Duration,
        currentOperation: String?,
        waitForLease: Boolean,
    ): CoordinatedInputLease {
        require(!timeout.isNegative && !timeout.isZero) { "Acquire timeout must be positive" }
        ensureConnected()
        val requestId = UUID.randomUUID().toString()
        val response =
            try {
                send(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.ACQUIRE,
                        requestId = requestId,
                        clientId = clientId,
                        resourceKey = resourceKey.value,
                        processId = owner.processId,
                        ownerLabel = owner.label,
                        timeoutMillis = timeout.toMillis(),
                        waitForLease = waitForLease,
                        currentOperation = currentOperation,
                    )
                )
            } catch (failure: IOException) {
                cancelAcquire(requestId)
                throw failure
            }
        response.requireSuccess()
        val token =
            LeaseToken(
                coordinatorEpoch = requireNotNull(response.coordinatorEpoch),
                leaseId = requireNotNull(response.leaseId),
                resourceKey = resourceKey,
                fence = requireNotNull(response.fence),
            )
        val registration =
            requireNotNull(
                leaseRegistrations.compute(token.leaseId) { _, existing ->
                    if (existing == null) {
                        LeaseRegistration(token)
                    } else {
                        check(existing.token == token) {
                            "Coordinator reused lease ID ${token.leaseId} for a different token"
                        }
                        existing.retain()
                        existing
                    }
                }
            )
        return LocalLease(registration)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected.set(false)
        heartbeatExecutor.shutdownNow()
        runCatching { sessionChannel.close() }
        leaseRegistrations.values.forEach(LeaseRegistration::invalidate)
        leaseRegistrations.clear()
    }

    private fun heartbeatAll() {
        if (!connected.get()) return
        leaseRegistrations.values.filter(LeaseRegistration::isValid).forEach { registration ->
            runCatching {
                    send(tokenMessage(CoordinatorWireKind.HEARTBEAT, registration.token))
                        .requireSuccess()
                }
                .onFailure {
                    registration.invalidate()
                    if (
                        it !is InputCoordinatorException ||
                            it.errorCode == LeaseErrorCode.STALE_EPOCH.name
                    ) {
                        close()
                    }
                }
        }
    }

    private fun cancelAcquire(requestId: String) {
        val wasInterrupted = Thread.interrupted()
        try {
            runCatching {
                send(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.CANCEL,
                        requestId = requestId,
                        clientId = clientId,
                        resourceKey = resourceKey.value,
                    )
                )
            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun send(message: CoordinatorWireMessage): CoordinatorWireMessage =
        sendCoordinatorMessage(endpoint, codec, message, responseTimeout(message))

    private fun responseTimeout(message: CoordinatorWireMessage): Duration =
        when {
            message.kind != CoordinatorWireKind.ACQUIRE -> REQUEST_RESPONSE_TIMEOUT
            !message.waitForLease -> IMMEDIATE_RESPONSE_TIMEOUT
            else ->
                Duration.ofMillis(
                    requireNotNull(message.timeoutMillis) + ACQUIRE_RESPONSE_GRACE_MILLIS
                )
        }

    private fun tokenMessage(kind: CoordinatorWireKind, token: LeaseToken): CoordinatorWireMessage =
        CoordinatorWireMessage(
            kind = kind,
            clientId = clientId,
            resourceKey = token.resourceKey.value,
            coordinatorEpoch = token.coordinatorEpoch,
            leaseId = token.leaseId,
            fence = token.fence,
        )

    private fun ensureConnected() {
        if (!connected.get()) {
            throw InputCoordinatorException(
                errorCode = LeaseErrorCode.STALE_EPOCH.name,
                message =
                    "Input coordinator session is disconnected; acquire from the current epoch",
            )
        }
    }

    private inner class LocalLease(private val registration: LeaseRegistration) :
        CoordinatedInputLease {
        private val closed = AtomicBoolean()

        override val token: LeaseToken
            get() = registration.token

        override fun isValid(): Boolean =
            !closed.get() &&
                registration.isValid() &&
                connected.get() &&
                token.coordinatorEpoch == coordinatorEpoch

        override fun checkpoint() {
            if (isValid()) {
                val response =
                    try {
                        send(tokenMessage(CoordinatorWireKind.HEARTBEAT, token))
                    } catch (failure: IOException) {
                        registration.invalidate()
                        this@LocalInputCoordinatorClient.close()
                        throw failure
                    }
                if (response.ok) return
                registration.invalidate()
            }
            if (!isValid()) {
                throw InputCoordinatorException(
                    errorCode = LeaseErrorCode.FENCED.name,
                    message =
                        "Input lease ${token.leaseId} is released, fenced, or from a stale epoch",
                )
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            leaseRegistrations.computeIfPresent(token.leaseId) { _, existing ->
                if (existing !== registration || existing.release() > 0) existing else null
            }
            if (connected.get()) {
                runCatching { send(tokenMessage(CoordinatorWireKind.RELEASE, token)) }
                    .onFailure { failure ->
                        if (failure is IOException) this@LocalInputCoordinatorClient.close()
                    }
            }
        }
    }

    private class LeaseRegistration(val token: LeaseToken) {
        private val valid = AtomicBoolean(true)
        private val references = AtomicInteger(1)

        fun isValid(): Boolean = valid.get()

        fun retain() {
            references.incrementAndGet()
        }

        fun release(): Int = references.decrementAndGet()

        fun invalidate() {
            valid.set(false)
        }
    }

    public companion object {
        /** Connects to an already-running compatible coordinator and opens an owner session. */
        @Throws(IOException::class)
        public fun connect(
            endpoint: CoordinatorEndpoint,
            resourceKey: DesktopResourceKey,
            ownerLabel: String? = null,
            codec: CoordinatorWireCodec = CoordinatorWireCodec(),
        ): LocalInputCoordinatorClient {
            val clientId = UUID.randomUUID().toString()
            val processId = ProcessHandle.current().pid()
            val session = SocketChannel.open(StandardProtocolFamily.UNIX)
            var sessionTransferred = false
            try {
                val response =
                    runCoordinatorIo(SESSION_OPEN_TIMEOUT) {
                        session.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
                        codec.write(
                            session,
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.SESSION_OPEN,
                                clientId = clientId,
                                processId = processId,
                                ownerLabel = ownerLabel,
                            ),
                        )
                        codec.read(session)
                    }
                response.requireSuccess()
                val client =
                    LocalInputCoordinatorClient(
                        endpoint = endpoint,
                        resourceKey = resourceKey,
                        clientId = clientId,
                        owner = LeaseOwner(clientId, processId, ownerLabel),
                        sessionChannel = session,
                        coordinatorEpoch = requireNotNull(response.coordinatorEpoch),
                        codec = codec,
                        heartbeatExecutor = daemonScheduler(),
                    )
                sessionTransferred = true
                return client
            } finally {
                if (!sessionTransferred) session.close()
            }
        }

        private fun daemonScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "spectre-input-heartbeat").apply { isDaemon = true }
            }

        private const val HEARTBEAT_INTERVAL_MILLIS: Long = 1_000
        private const val ACQUIRE_RESPONSE_GRACE_MILLIS: Long = 1_000
        private val MINIMUM_ACQUIRE_TIMEOUT: Duration = Duration.ofMillis(1)
        private val IMMEDIATE_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(1)
        private val REQUEST_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val SESSION_OPEN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

/** Observe/revoke client that never launches a missing coordinator. */
@ExperimentalSpectreInputCoordinationApi
public class LocalInputCoordinatorControl(
    private val endpoint: CoordinatorEndpoint,
    private val codec: CoordinatorWireCodec = CoordinatorWireCodec(),
) {
    /** Returns cleanly when no coordinator is accepting connections. */
    public fun status(resourceKey: DesktopResourceKey): CoordinatorControlResult {
        if (!java.nio.file.Files.exists(endpoint.socketPath)) {
            return CoordinatorControlResult.NoActiveCoordinator
        }
        val response =
            try {
                send(
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.STATUS,
                        resourceKey = resourceKey.value,
                    )
                )
            } catch (_: IOException) {
                return CoordinatorControlResult.NoActiveCoordinator
            }
        response.requireSuccess()
        return CoordinatorControlResult.Active(requireNotNull(response.status).toDomain())
    }

    /** Revokes only the exact observed lease ID; [force] is an explicitly unsafe takeover. */
    public fun revoke(
        resourceKey: DesktopResourceKey,
        observedLeaseId: String,
        requesterLabel: String,
        reason: String,
        force: Boolean = false,
    ): CoordinatorRevokeResult {
        require(observedLeaseId.isNotBlank()) { "Observed lease ID must not be blank" }
        require(requesterLabel.isNotBlank()) { "Requester label must not be blank" }
        require(reason.isNotBlank()) { "Revoke reason must not be blank" }
        val response =
            send(
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.REVOKE,
                    resourceKey = resourceKey.value,
                    leaseId = observedLeaseId,
                    requesterLabel = requesterLabel,
                    reason = reason,
                    force = force,
                )
            )
        response.requireSuccess()
        return CoordinatorRevokeResult(unsafeTakeover = response.unsafeTakeover)
    }

    private fun send(message: CoordinatorWireMessage): CoordinatorWireMessage =
        sendCoordinatorMessage(endpoint, codec, message, CONTROL_RESPONSE_TIMEOUT)

    private companion object {
        val CONTROL_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

internal fun sendCoordinatorMessage(
    endpoint: CoordinatorEndpoint,
    codec: CoordinatorWireCodec,
    message: CoordinatorWireMessage,
    timeout: Duration,
): CoordinatorWireMessage =
    runCoordinatorIo(timeout) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
            codec.write(channel, message)
            codec.read(channel)
        }
    }

private fun <T> runCoordinatorIo(timeout: Duration, block: () -> T): T {
    require(!timeout.isNegative && !timeout.isZero) { "Coordinator I/O timeout must be positive" }
    val task = FutureTask<T> { block() }
    Thread.ofVirtual().name("spectre-input-request").start(task)
    try {
        return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        task.cancel(true)
        throw SocketTimeoutException(
            "Input coordinator did not respond within ${timeout.toMillis()} ms"
        )
    } catch (_: InterruptedException) {
        task.cancel(true)
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Input coordinator request was interrupted")
    } catch (failure: ExecutionException) {
        val cause = failure.cause ?: failure
        when (cause) {
            is IOException -> throw cause
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw IOException("Input coordinator request failed", cause)
        }
    }
}

private fun CoordinatorWireMessage.requireSuccess() {
    if (!ok) {
        throw InputCoordinatorException(
            errorCode = errorCode ?: "UNKNOWN",
            message = message ?: "Input coordinator request failed",
        )
    }
}

private fun CoordinatorWireStatus.toDomain(): CoordinatorStatus =
    CoordinatorStatus(
        resourceKey = DesktopResourceKey(resourceKey),
        holder =
            holder?.let {
                CoordinatorHolderStatus(
                    leaseId = it.leaseId,
                    owner = LeaseOwner(it.clientId, it.processId, it.ownerLabel),
                    state = it.state,
                    fence = it.fence,
                    acquisitionAgeMillis = it.acquisitionAgeMillis,
                    heartbeatAgeMillis = it.heartbeatAgeMillis,
                    currentOperation = it.currentOperation,
                )
            },
        waiters =
            waiters.map {
                CoordinatorWaiterStatus(
                    requestId = it.requestId,
                    owner = LeaseOwner(it.clientId, it.processId, it.ownerLabel),
                    position = it.position,
                )
            },
        quarantine =
            quarantine?.let {
                CoordinatorQuarantineStatus(
                    predecessorLeaseId = it.predecessorLeaseId,
                    predecessorEpoch = it.predecessorEpoch,
                    owner = LeaseOwner(it.clientId, it.processId, it.ownerLabel),
                    releaseEligibleAtMillis = it.releaseEligibleAtMillis,
                )
            },
    )

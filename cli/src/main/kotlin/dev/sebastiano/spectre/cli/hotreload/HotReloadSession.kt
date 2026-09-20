package dev.sebastiano.spectre.cli.hotreload

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.reload.core.getOrThrow
import org.jetbrains.compose.reload.core.isFailure
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationHandle
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.asChannel
import org.jetbrains.compose.reload.orchestration.connectOrchestrationClient

/**
 * Daemon-facing Hot Reload capability for one attach session (#211).
 *
 * Production implementation is [HotReloadSession]; tests inject fakes via the registry factory.
 */
public interface HotReloadCapability : AutoCloseable {
    /**
     * Blocks until the full reload settle chain completes or [timeoutMs] elapses.
     *
     * When HR is not connected, returns [ReloadSettleOutcome.Unavailable] immediately.
     */
    public fun waitForReloadSettled(
        timeoutMs: Long = HotReloadSession.DEFAULT_SETTLE_TIMEOUT_MS
    ): ReloadSettleOutcome

    /**
     * Registers a listener invoked after a successful reload settles (matching `UIRendered` after a
     * successful `ReloadClassesResult`). Used for tree/node-key invalidation (#212).
     *
     * Replaces any previous listener. Passing `null` clears the listener.
     */
    public fun setReloadSettledListener(listener: (() -> Unit)?)
}

/**
 * Per-attach-session Hot Reload orchestration client (#211).
 *
 * Connects as [OrchestrationClientRole.Tooling], reconnects when the app restarts (same pattern as
 * HR's own MCP), and exposes [waitForReloadSettled] for the full settle chain.
 *
 * Lifetime is owned by the daemon session: [close] on detach.
 *
 * Lifecycle messages are read by a **single** [asChannel] consumer per connected handle and fanned
 * out to the invalidation observer and any concurrent [waitForReloadSettled] waiters (#212 Codex).
 */
public class HotReloadSession
internal constructor(
    private val portProvider: () -> Int?,
    private val connect: suspend (port: Int) -> OrchestrationHandle? = ::defaultConnect,
    private val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
    // Injected so detekt InjectDispatcher accepts the default (see RobotDriver pattern).
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HotReloadCapability {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val handleRef = AtomicReference<OrchestrationHandle?>(null)
    private val closed = AtomicBoolean(false)
    private val reloadSettledListener = AtomicReference<(() -> Unit)?>(null)
    private var reconnectJob: Job? = null
    /**
     * Active settle-wait subscribers for the current handle. Written only from the pump coroutine
     * and subscribe/unsubscribe paths; CopyOnWrite so the pump can fan out without holding a lock.
     */
    private val lifecycleSubscribers = CopyOnWriteArrayList<SendChannel<ReloadLifecycleEvent>>()
    private val invalidationBookkeeping = ReloadInvalidationBookkeeping()

    /** True when a live orchestration handle is connected. */
    public val isConnected: Boolean
        get() = handleRef.get() != null

    /** Starts the reconnect loop. Safe to call once after construction. */
    public fun start() {
        if (closed.get() || reconnectJob != null) return
        reconnectJob = scope.launch { runReconnectLoop() }
    }

    override fun setReloadSettledListener(listener: (() -> Unit)?) {
        reloadSettledListener.set(listener)
    }

    private suspend fun runReconnectLoop() {
        while (scope.isActive && !closed.get()) {
            val handle = connectOnce()
            if (handle == null) {
                delay(reconnectDelayMs)
            } else {
                awaitConnectedHandle(handle)
                if (!closed.get()) delay(reconnectDelayMs)
            }
        }
    }

    private suspend fun awaitConnectedHandle(handle: OrchestrationHandle) {
        handleRef.set(handle)
        // One reader for the handle; invalidation + waiters share fanned-out lifecycle events.
        val pump = scope.launch { pumpLifecycle(handle) }
        try {
            // Suspend until the handle finishes (app exit / disconnect).
            handle.await()
        } catch (_: Exception) {
            // fall through to reconnect
        } finally {
            pump.cancel()
            // Mid-session handle drop: drop in-flight pending only. Keep lastInvalidatedRequestId
            // so a reconnect that sees the same result/UIRendered pair does not re-fire settle.
            invalidationBookkeeping.clearPending()
            clearLifecycleSubscribers()
            handleRef.compareAndSet(handle, null)
            runCatching { handle.close() }
        }
    }

    /**
     * Single [asChannel] consumer: drives invalidation bookkeeping and fans every lifecycle event
     * to concurrent [waitForReloadSettled] subscribers so they cannot steal messages from each
     * other.
     */
    private suspend fun pumpLifecycle(handle: OrchestrationHandle) {
        val channel = handle.asChannel()
        try {
            while (scope.isActive && !closed.get()) {
                val message = channel.receiveCatching().getOrNull()
                if (message == null) {
                    return
                }
                val event = message.toLifecycleEvent()
                if (event != null) {
                    if (invalidationBookkeeping.onEvent(event)) {
                        reloadSettledListener.get()?.invoke()
                    }
                    fanOut(event)
                }
            }
        } finally {
            channel.cancel()
        }
    }

    private fun fanOut(event: ReloadLifecycleEvent) {
        for (subscriber in lifecycleSubscribers) {
            // DROP when a waiter is not draining fast enough; settle timeout still covers misses.
            subscriber.trySend(event)
        }
    }

    /**
     * Registers a buffered channel that receives every lifecycle event for the current connection.
     * Caller must [Channel.cancel] when done; [invokeOnClose] removes the subscriber.
     */
    private fun subscribeLifecycle(): Channel<ReloadLifecycleEvent> {
        val channel = Channel<ReloadLifecycleEvent>(Channel.BUFFERED)
        lifecycleSubscribers.add(channel)
        channel.invokeOnClose { lifecycleSubscribers.remove(channel) }
        return channel
    }

    private fun clearLifecycleSubscribers() {
        val snapshot = lifecycleSubscribers.toList()
        lifecycleSubscribers.clear()
        for (subscriber in snapshot) {
            subscriber.close()
        }
    }

    private suspend fun connectOnce(): OrchestrationHandle? {
        val port = portProvider() ?: return null
        return try {
            connect(port)
        } catch (_: Exception) {
            null
        }
    }

    override fun waitForReloadSettled(timeoutMs: Long): ReloadSettleOutcome {
        if (closed.get()) return ReloadSettleOutcome.Cancelled
        // If reconnect was never started, fail closed immediately (tests / non-HR injection).
        if (reconnectJob == null && handleRef.get() == null) {
            return ReloadSettleOutcome.Unavailable
        }
        // Arm fan-out *before* awaitConnection so a reload that races connect → settle cannot
        // fan out to an empty subscriber list and leave this waiter stuck in AwaitRequest.
        val events = subscribeLifecycle()
        try {
            val startedNs = System.nanoTime()
            val handle =
                awaitConnection(timeoutMs)
                    ?: return when {
                        closed.get() -> ReloadSettleOutcome.Cancelled
                        // Reconnect was running but never produced a handle within the budget →
                        // settle-timeout, not "HR unavailable" (session is still reload-aware).
                        else -> ReloadSettleOutcome.TimedOut
                    }
            val elapsedMs = (System.nanoTime() - startedNs) / NANOS_PER_MS
            val remainingMs = (timeoutMs - elapsedMs).coerceAtLeast(0)
            if (remainingMs == 0L) return ReloadSettleOutcome.TimedOut
            // Pin the handle that is currently pumping into [events]; send Ping only on that
            // same instance so reconnect cannot pair a dead send with a live receive.
            return runBlocking { settleOn(handle, remainingMs, events) }
        } finally {
            events.cancel()
        }
    }

    /**
     * Waits up to [timeoutMs] for the Tooling client to finish its first (or next) connect. Avoids
     * a race where attach creates a reload-aware session and an immediate wait reports
     * `hotReloadUnavailable` before the reconnect loop connects.
     */
    private fun awaitConnection(timeoutMs: Long): OrchestrationHandle? {
        handleRef.get()?.let {
            return it
        }
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs && !closed.get()) {
            handleRef.get()?.let {
                return it
            }
            Thread.sleep(CONNECT_POLL_MS)
        }
        return handleRef.get()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reconnectJob?.cancel()
        invalidationBookkeeping.reset()
        clearLifecycleSubscribers()
        handleRef.getAndSet(null)?.let { runCatching { it.close() } }
        scope.cancel()
    }

    private suspend fun settleOn(
        connectedHandle: OrchestrationHandle,
        timeoutMs: Long,
        events: Channel<ReloadLifecycleEvent>,
    ): ReloadSettleOutcome {
        val machine = ReloadSettleStateMachine()
        val settled =
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    if (machine.needsPing()) {
                        // Only send on the same handle that was live when this waiter armed;
                        // reconnect clears subscribers (channel closes → Cancelled) and replaces
                        // handleRef, so a mismatched ref means this wait is already obsolete.
                        val live = handleRef.get()
                        if (live == null || live !== connectedHandle) {
                            return@withTimeoutOrNull ReloadSettleOutcome.Cancelled
                        }
                        val ping = OrchestrationMessage.Ping()
                        try {
                            live.send(ping)
                        } catch (_: Exception) {
                            return@withTimeoutOrNull ReloadSettleOutcome.Cancelled
                        }
                        machine.beginPingDrain(ping.messageId.toString())
                    }
                    val event =
                        events.receiveCatching().getOrNull()
                            ?: return@withTimeoutOrNull ReloadSettleOutcome.Cancelled
                    val outcome = machine.onEvent(event)
                    if (outcome != null) return@withTimeoutOrNull outcome
                }
                @Suppress("UNREACHABLE_CODE") ReloadSettleOutcome.TimedOut
            }
        return settled ?: machine.onTimeout()
    }

    public companion object {
        public const val DEFAULT_SETTLE_TIMEOUT_MS: Long = 60_000
        private const val DEFAULT_RECONNECT_DELAY_MS: Long = 2_000
        private const val CONNECT_POLL_MS: Long = 50
        private const val NANOS_PER_MS: Long = 1_000_000

        /**
         * Creates a session that discovers the port for [targetPid] on each reconnect attempt.
         *
         * When [autoStart] is false, the caller must invoke [start] after wiring listeners (#212)
         * so the first connect does not race past an unregistered invalidation listener.
         */
        public fun forTargetPid(
            targetPid: Long,
            explicitPidFile: java.nio.file.Path? = null,
            autoStart: Boolean = true,
        ): HotReloadSession {
            val session =
                HotReloadSession(
                    portProvider = {
                        HotReloadPortDiscovery.discover(
                                targetPid = targetPid,
                                explicitPidFile = explicitPidFile,
                            )
                            ?.port
                    }
                )
            if (autoStart) session.start()
            return session
        }

        /**
         * Creates a session pinned to a fixed [port] (tests and explicit orchestration endpoints).
         */
        public fun forPort(port: Int, autoStart: Boolean = true): HotReloadSession {
            val session = HotReloadSession(portProvider = { port })
            if (autoStart) session.start()
            return session
        }

        /**
         * Test seam: inject connect/port without starting automatic reconnect, for pure settle
         * tests that own the handle.
         */
        internal fun forTesting(
            portProvider: () -> Int?,
            connect: suspend (Int) -> OrchestrationHandle?,
            startReconnect: Boolean = true,
        ): HotReloadSession {
            val session = HotReloadSession(portProvider = portProvider, connect = connect)
            if (startReconnect) session.start()
            return session
        }
    }
}

private suspend fun defaultConnect(port: Int): OrchestrationHandle? {
    val result = connectOrchestrationClient(OrchestrationClientRole.Tooling, port)
    return if (result.isFailure()) null else result.getOrThrow()
}

/**
 * Tracks successful reload → matching UIRendered for the invalidation listener.
 *
 * Duplicate `ReloadClassesRequest` ids must not re-fire after the first matching `UIRendered`: e2e
 * plumbing rebroadcasts the same request while the Tooling client is still connecting, and treating
 * those repeats as a new generation would bump keys past `g1` after a single settle.
 */
internal class ReloadInvalidationBookkeeping {
    private var pendingSuccessfulRequestId: String? = null
    private var lastInvalidatedRequestId: String? = null

    /** Returns true when the settled-listener should run for this event. */
    fun onEvent(event: ReloadLifecycleEvent): Boolean =
        when (event) {
            is ReloadLifecycleEvent.ReloadClassesResult -> {
                pendingSuccessfulRequestId =
                    when {
                        event.isSuccess && event.reloadRequestId != lastInvalidatedRequestId ->
                            event.reloadRequestId
                        event.reloadRequestId == pendingSuccessfulRequestId -> null
                        else -> pendingSuccessfulRequestId
                    }
                false
            }
            is ReloadLifecycleEvent.UIRendered -> {
                val requestId = event.reloadRequestId
                if (requestId != null && requestId == pendingSuccessfulRequestId) {
                    pendingSuccessfulRequestId = null
                    if (lastInvalidatedRequestId != requestId) {
                        lastInvalidatedRequestId = requestId
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            else -> false
        }

    /**
     * Drops an in-flight pending result without forgetting which request already invalidated. Used
     * when the orchestration handle reconnects mid-session.
     */
    fun clearPending() {
        pendingSuccessfulRequestId = null
    }

    /** Full wipe when the attach session ends. */
    fun reset() {
        pendingSuccessfulRequestId = null
        lastInvalidatedRequestId = null
    }
}

internal fun OrchestrationMessage.toLifecycleEvent(): ReloadLifecycleEvent? =
    when (this) {
        is OrchestrationMessage.ReloadClassesRequest ->
            ReloadLifecycleEvent.ReloadClassesRequest(messageId.toString())
        is OrchestrationMessage.ReloadClassesResult ->
            ReloadLifecycleEvent.ReloadClassesResult(
                reloadRequestId = reloadRequestId.toString(),
                isSuccess = isSuccess,
                errorMessage = errorMessage,
            )
        is OrchestrationMessage.UIRendered ->
            ReloadLifecycleEvent.UIRendered(reloadRequestId = reloadRequestId?.toString())
        is OrchestrationMessage.Ack ->
            ReloadLifecycleEvent.Ack(acknowledgedMessageId = acknowledgedMessageId.toString())
        else -> null
    }

package dev.sebastiano.spectre.cli.hotreload

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
}

/**
 * Per-attach-session Hot Reload orchestration client (#211).
 *
 * Connects as [OrchestrationClientRole.Tooling], reconnects when the app restarts (same pattern as
 * HR's own MCP), and exposes [waitForReloadSettled] for the full settle chain.
 *
 * Lifetime is owned by the daemon session: [close] on detach.
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
    private var reconnectJob: Job? = null

    /** True when a live orchestration handle is connected. */
    public val isConnected: Boolean
        get() = handleRef.get() != null

    /** Starts the reconnect loop. Safe to call once after construction. */
    public fun start() {
        if (closed.get()) return
        reconnectJob = scope.launch { runReconnectLoop() }
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
        try {
            // Suspend until the handle finishes (app exit / disconnect).
            handle.await()
        } catch (_: Exception) {
            // fall through to reconnect
        } finally {
            handleRef.compareAndSet(handle, null)
            runCatching { handle.close() }
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
        val startedNs = System.nanoTime()
        val handle =
            awaitConnection(timeoutMs)
                ?: return if (closed.get()) {
                    ReloadSettleOutcome.Cancelled
                } else {
                    ReloadSettleOutcome.Unavailable
                }
        val elapsedMs = (System.nanoTime() - startedNs) / NANOS_PER_MS
        val remainingMs = (timeoutMs - elapsedMs).coerceAtLeast(0)
        if (remainingMs == 0L) return ReloadSettleOutcome.TimedOut
        return runBlocking { settleOn(handle, remainingMs) }
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
        handleRef.getAndSet(null)?.let { runCatching { it.close() } }
        scope.cancel()
    }

    private suspend fun settleOn(
        handle: OrchestrationHandle,
        timeoutMs: Long,
    ): ReloadSettleOutcome {
        val machine = ReloadSettleStateMachine()
        val channel = handle.asChannel()
        try {
            val settled =
                withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        if (machine.needsPing()) {
                            // Ping assigns its own messageId; match Ack against that id.
                            val ping = OrchestrationMessage.Ping()
                            handle.send(ping)
                            machine.beginPingDrain(ping.messageId.toString())
                        }
                        val message =
                            channel.receiveCatching().getOrNull()
                                ?: return@withTimeoutOrNull ReloadSettleOutcome.Cancelled
                        val event = message.toLifecycleEvent() ?: continue
                        val outcome = machine.onEvent(event)
                        if (outcome != null) return@withTimeoutOrNull outcome
                    }
                    @Suppress("UNREACHABLE_CODE") ReloadSettleOutcome.TimedOut
                }
            return settled ?: machine.onTimeout()
        } finally {
            channel.cancel()
        }
    }

    public companion object {
        public const val DEFAULT_SETTLE_TIMEOUT_MS: Long = 60_000
        private const val DEFAULT_RECONNECT_DELAY_MS: Long = 2_000
        private const val CONNECT_POLL_MS: Long = 50
        private const val NANOS_PER_MS: Long = 1_000_000

        /** Creates a session that discovers the port for [targetPid] on each reconnect attempt. */
        public fun forTargetPid(
            targetPid: Long,
            explicitPidFile: java.nio.file.Path? = null,
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
            session.start()
            return session
        }

        /**
         * Creates a session pinned to a fixed [port] (tests and explicit orchestration endpoints).
         */
        public fun forPort(port: Int): HotReloadSession {
            val session = HotReloadSession(portProvider = { port })
            session.start()
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

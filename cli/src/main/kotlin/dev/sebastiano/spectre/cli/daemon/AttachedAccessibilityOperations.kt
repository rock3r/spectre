package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.cli.hotreload.HotReloadCapability
import dev.sebastiano.spectre.cli.hotreload.ReloadAwareKeyGuard
import java.io.IOException

internal class AttachedAccessibilityOperations(
    private val handler: AccessibilityDaemonHandler,
    private val acquire: (String) -> Acquisition,
    private val liveSessionIds: () -> Set<String>,
) {
    fun handle(request: DaemonRequest.AccessibilityTree): DaemonResponse {
        handler.validate(request)?.let {
            return it
        }
        val sessionId = request.sessionId ?: return handler.handle(request)
        return withSession(sessionId) { session ->
            guarded(session, request.windowIndex) { verify ->
                val response =
                    handler.handle(request) { _, windowIndex ->
                        session.accessibilityTarget(windowIndex)
                    }
                verify() ?: response
            }
        }
    }

    fun capture(request: DaemonRequest.Capture): DaemonResponse =
        withSession(request.sessionId) { session ->
            guarded(session, request.windowIndex) { verify ->
                writeCapture(
                    request,
                    session.automator,
                    liveSessionIds(),
                    handler,
                    session::accessibilityTarget,
                    verify,
                )
            }
        }

    private fun guarded(
        session: DaemonSession,
        windowIndex: Int,
        operation: (() -> DaemonResponse?) -> DaemonResponse,
    ): DaemonResponse {
        var token: Long? = null
        return try {
            token = session.automator.beginWindowChangeCapture()
            val before = session.accessibilityTarget(windowIndex)
            operation {
                val after = session.accessibilityTarget(windowIndex)
                val generation = session.automator.endWindowChangeCapture(requireNotNull(token))
                token = null
                if (generation != 0L || before == null || before != after) changedWindowFailure()
                else null
            }
        } catch (exception: IOException) {
            DaemonResponse.Error(
                DaemonErrorCode.OperationFailed,
                exception.message ?: "session operation failed",
            )
        } catch (exception: UnsupportedOperationException) {
            DaemonResponse.Error(
                DaemonErrorCode.OperationFailed,
                exception.message ?: "agent runtime lacks atomic accessibility capability",
            )
        } finally {
            token?.let { runCatching { session.automator.endWindowChangeCapture(it) } }
        }
    }

    private fun withSession(
        sessionId: String,
        operation: (DaemonSession) -> DaemonResponse,
    ): DaemonResponse {
        val session =
            when (val acquired = acquire(sessionId)) {
                is Acquisition.Failure -> return acquired.response
                is Acquisition.Success -> acquired.session
            }
        return try {
            operation(session)
        } finally {
            session.finishAccessibility()
        }
    }

    sealed interface Acquisition {
        data class Success(val session: DaemonSession) : Acquisition

        data class Failure(val response: DaemonResponse.Error) : Acquisition
    }
}

private fun DaemonSession.accessibilityTarget(windowIndex: Int): SessionAccessibilityTarget? {
    val window = automator.windows().singleOrNull { it.index == windowIndex } ?: return null
    val identity = automator.windowIdentities(windowIndex).singleOrNull()
    return SessionAccessibilityTarget(summary.targetPid, window, identity?.nativeHandle)
}

private fun changedWindowFailure(): DaemonResponse =
    DaemonResponse.AccessibilityErrorResult(
        AccessibilityError(
            AccessibilityErrorCode.TargetNotFound,
            "The selected window changed while its accessibility tree was captured",
        )
    )

internal fun deadlineAfterMillis(timeoutMs: Long): Long =
    System.nanoTime() + timeoutMs.coerceIn(0, Long.MAX_VALUE / NANOS_PER_MS) * NANOS_PER_MS

internal fun hasTimeRemaining(deadlineNs: Long): Boolean = deadlineNs - System.nanoTime() > 0

internal fun remainingRegistryMillis(deadlineNs: Long): Long {
    val remainingNs = deadlineNs - System.nanoTime()
    if (remainingNs <= 0) return 0
    return (remainingNs / NANOS_PER_MS).coerceAtLeast(1)
}

internal data class DaemonSession(
    val summary: DaemonSessionSummary,
    val automator: DaemonSessionAutomator,
    var hotReloadSession: HotReloadCapability? = null,
    var keyGuard: ReloadAwareKeyGuard? = null,
    var retired: Boolean = false,
    val activeWaits: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0),
    val activeAccessibility: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0),
    /** Absolute nanoTime deadline for the longest currently tracked outside-lock wait. */
    val waitDeadlineNs: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic.AtomicLong(0),
) {
    private val accessibilityLock = java.util.concurrent.locks.ReentrantLock()
    private val accessibilityIdle = accessibilityLock.newCondition()

    val sessionId: String
        get() = summary.sessionId

    fun noteWaitStarted(timeoutMs: Long) {
        val candidate = deadlineAfterMillis(timeoutMs)
        waitDeadlineNs.updateAndGet { current -> maxOf(current, candidate) }
    }

    fun retire(): HotReloadCapability? =
        synchronized(this) {
            retired = true
            hotReloadSession
        }

    fun awaitIdleWaits(timeoutMs: Long = WAIT_DRAIN_TIMEOUT_MS) {
        val trackedRemainingMs =
            ((waitDeadlineNs.get() - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(0)
        val budgetMs = maxOf(timeoutMs, trackedRemainingMs + WAIT_DRAIN_MARGIN_MS)
        val deadline = deadlineAfterMillis(budgetMs)
        while (activeWaits.get() > 0 && hasTimeRemaining(deadline)) {
            Thread.sleep(WAIT_DRAIN_POLL_MS)
        }
    }

    fun finishAccessibility() {
        accessibilityLock.lock()
        try {
            activeAccessibility.decrementAndGet()
            accessibilityIdle.signalAll()
        } finally {
            accessibilityLock.unlock()
        }
    }

    fun awaitIdleAccessibility() {
        accessibilityLock.lock()
        try {
            while (activeAccessibility.get() > 0) accessibilityIdle.awaitUninterruptibly()
        } finally {
            accessibilityLock.unlock()
        }
    }
}

internal fun acquireAttachedAccessibilitySession(
    shutdown: Boolean,
    sessionsByPid: Map<Long, DaemonSession>,
    sessionId: String,
): AttachedAccessibilityOperations.Acquisition {
    if (shutdown) {
        return AttachedAccessibilityOperations.Acquisition.Failure(
            DaemonResponse.Error(DaemonErrorCode.ShutdownInProgress, "daemon is shutting down")
        )
    }
    val session =
        sessionsByPid.values.firstOrNull { it.sessionId == sessionId }
            ?: return AttachedAccessibilityOperations.Acquisition.Failure(
                sessionNotFound(sessionId)
            )
    session.activeAccessibility.incrementAndGet()
    return AttachedAccessibilityOperations.Acquisition.Success(session)
}

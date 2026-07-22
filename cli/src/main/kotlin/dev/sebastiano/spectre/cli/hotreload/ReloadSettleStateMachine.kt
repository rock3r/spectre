package dev.sebastiano.spectre.cli.hotreload

/**
 * Pure state machine mirroring Compose Hot Reload's own settle chain (#211):
 *
 * `ReloadClassesRequest` → matching `ReloadClassesResult` → matching `UIRendered` → `Ping`/`Ack`
 * drain.
 *
 * Decoupled from the orchestration TCP client so unit tests do not need a live HR process.
 */
public class ReloadSettleStateMachine {
    private var phase: Phase = Phase.AwaitRequest
    private var requestId: String? = null
    private var pingId: String? = null

    /** Current phase (for diagnostics / tests). */
    public val currentPhase: String
        get() = phase.name

    /**
     * Feeds one lifecycle [event]. Returns a terminal [ReloadSettleOutcome] when the chain
     * completes or fails; `null` means keep waiting.
     *
     * When the machine enters [Phase.NeedsPing], the caller must send a Ping whose message id is
     * supplied via [beginPingDrain] before further events are processed.
     */
    public fun onEvent(event: ReloadLifecycleEvent): ReloadSettleOutcome? =
        when (phase) {
            Phase.AwaitRequest -> onAwaitRequest(event)
            Phase.AwaitResult -> onAwaitResult(event)
            Phase.AwaitUiRendered -> onAwaitUiRendered(event)
            Phase.NeedsPing -> null // beginPingDrain first
            Phase.AwaitAck -> onAwaitAck(event)
            Phase.Terminal -> null
        }

    private fun onAwaitRequest(event: ReloadLifecycleEvent): ReloadSettleOutcome? {
        if (event is ReloadLifecycleEvent.ReloadClassesRequest) {
            requestId = event.messageId
            phase = Phase.AwaitResult
        }
        return null
    }

    private fun onAwaitResult(event: ReloadLifecycleEvent): ReloadSettleOutcome? {
        if (event !is ReloadLifecycleEvent.ReloadClassesResult) return null
        if (event.reloadRequestId != requestId) return null
        if (!event.isSuccess) {
            phase = Phase.Terminal
            return ReloadSettleOutcome.ReloadFailed(event.errorMessage ?: "reload failed")
        }
        phase = Phase.AwaitUiRendered
        return null
    }

    private fun onAwaitUiRendered(event: ReloadLifecycleEvent): ReloadSettleOutcome? {
        if (event !is ReloadLifecycleEvent.UIRendered) return null
        // Initial renderings carry a null reloadRequestId — skip them.
        if (event.reloadRequestId == null || event.reloadRequestId != requestId) return null
        phase = Phase.NeedsPing
        return null
    }

    private fun onAwaitAck(event: ReloadLifecycleEvent): ReloadSettleOutcome? {
        if (event !is ReloadLifecycleEvent.Ack) return null
        if (event.acknowledgedMessageId != pingId) return null
        phase = Phase.Terminal
        return ReloadSettleOutcome.Settled
    }

    /**
     * After [Phase.NeedsPing], the caller invents a Ping [messageId], sends it on the wire, then
     * calls this to arm Ack matching.
     */
    public fun beginPingDrain(messageId: String) {
        check(phase == Phase.NeedsPing) {
            "beginPingDrain only valid in NeedsPing phase, was $phase"
        }
        pingId = messageId
        phase = Phase.AwaitAck
    }

    /** Whether the caller should send a Ping and call [beginPingDrain]. */
    public fun needsPing(): Boolean = phase == Phase.NeedsPing

    /** Terminal timeout outcome (does not change phase if already terminal). */
    public fun onTimeout(): ReloadSettleOutcome {
        if (phase == Phase.Terminal) {
            return ReloadSettleOutcome.Settled // already finished; timeout is a no-op
        }
        phase = Phase.Terminal
        return ReloadSettleOutcome.TimedOut
    }

    private enum class Phase {
        AwaitRequest,
        AwaitResult,
        AwaitUiRendered,
        NeedsPing,
        AwaitAck,
        Terminal,
    }
}

/** Wire-agnostic events observed on the orchestration bus during a settle wait. */
public sealed interface ReloadLifecycleEvent {
    public data class ReloadClassesRequest(public val messageId: String) : ReloadLifecycleEvent

    public data class ReloadClassesResult(
        public val reloadRequestId: String,
        public val isSuccess: Boolean,
        public val errorMessage: String? = null,
    ) : ReloadLifecycleEvent

    public data class UIRendered(public val reloadRequestId: String?) : ReloadLifecycleEvent

    public data class Ack(public val acknowledgedMessageId: String) : ReloadLifecycleEvent
}

/** Terminal result of [ReloadSettleStateMachine] / [waitForReloadSettled]. */
public sealed interface ReloadSettleOutcome {
    public data object Settled : ReloadSettleOutcome

    public data class ReloadFailed(public val errorMessage: String) : ReloadSettleOutcome

    public data object TimedOut : ReloadSettleOutcome

    public data object Unavailable : ReloadSettleOutcome

    public data object Cancelled : ReloadSettleOutcome
}

/**
 * Stable #199-style taxonomy wire names for reload settle failures, surfaced on the daemon wire.
 */
public object ReloadSettleErrorCategory {
    public const val HOT_RELOAD_UNAVAILABLE: String = "hotReloadUnavailable"
    public const val RELOAD_FAILED: String = "reloadFailed"
    public const val TIMEOUT: String = "timeout"
    public const val CANCELLED: String = "cancelled"

    public fun forOutcome(outcome: ReloadSettleOutcome): String? =
        when (outcome) {
            is ReloadSettleOutcome.Settled -> null
            is ReloadSettleOutcome.ReloadFailed -> RELOAD_FAILED
            ReloadSettleOutcome.TimedOut -> TIMEOUT
            ReloadSettleOutcome.Unavailable -> HOT_RELOAD_UNAVAILABLE
            ReloadSettleOutcome.Cancelled -> CANCELLED
        }
}

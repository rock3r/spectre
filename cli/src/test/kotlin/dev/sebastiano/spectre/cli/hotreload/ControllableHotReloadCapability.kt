package dev.sebastiano.spectre.cli.hotreload

import java.util.Queue

/** Shared test double for reload-aware daemon tests (#211/#212). */
internal class ControllableHotReloadCapability(
    private val outcomes: Queue<ReloadSettleOutcome> = java.util.concurrent.ConcurrentLinkedQueue()
) : HotReloadCapability {
    private var reloadListener: (() -> Unit)? = null

    override fun waitForReloadSettled(timeoutMs: Long): ReloadSettleOutcome =
        outcomes.poll() ?: ReloadSettleOutcome.Unavailable

    override fun setReloadSettledListener(listener: (() -> Unit)?) {
        reloadListener = listener
    }

    fun fireReloadSettled() {
        reloadListener?.invoke()
    }

    /** Enqueue a controlled wait outcome (tests). */
    fun enqueue(outcome: ReloadSettleOutcome) {
        outcomes.add(outcome)
    }

    override fun close() = Unit
}

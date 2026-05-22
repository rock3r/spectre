@file:OptIn(ExperimentalComposeRuntimeApi::class, InternalSpectreApi::class)

package dev.sebastiano.spectre.core.perf

import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow
import dev.sebastiano.spectre.core.WindowTracker
import dev.sebastiano.spectre.core.readOnEdt
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Counts Compose recompositions across a set of attached [Recomposer]s. Exposes a monotonic
 * lifetime [total] and a sliding-window [ratePerSecond] for cheap "is the UI thrashing?" checks
 * during automation. Per-surface counters live under stable surface ids assigned by
 * `WindowTracker`.
 *
 * The monitor owns a [SupervisorJob]-backed [CoroutineScope]; [close] cancels the scope, disposes
 * every [CompositionObserverHandle], and is idempotent. Multiple [Recomposer]s can be attached
 * concurrently — the monitor sums their per-pass events.
 *
 * Counting alone is cheap: the installed [CompositionObserver] only implements
 * [CompositionObserver.onBeginComposition] (one atomic increment per recomposition pass) and leaves
 * the per-scope hooks as no-ops, so the per-recomposition overhead is essentially a single counter
 * bump plus a ring-buffer append.
 */
@ExperimentalSpectreApi
public class RecompositionMonitor
internal constructor(
    private val windowDuration: Duration = DEFAULT_WINDOW,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : AutoCloseable {

    /** Default user-facing constructor with the wall-clock time source. */
    public constructor(
        windowDuration: Duration = DEFAULT_WINDOW
    ) : this(windowDuration, TimeSource.Monotonic)

    private val ownedJob = SupervisorJob()

    /** Test-visible accessor for the owned scope so unit tests can assert cancellation. */
    internal val scopeForTests: CoroutineScope = CoroutineScope(ownedJob + Dispatchers.Default)

    private val surfaces = ConcurrentHashMap<String, SurfaceTracker>()

    /**
     * Per-Recomposer registration: each entry holds the top-level registration handle plus the
     * per-composition observer handles we installed via [ObservableComposition.setObserver]. We
     * dispose them in reverse on detach/close so Compose can release its observer state cleanly.
     * The [Recomposer] reference is retained so [awaitCompositionIdle] can query
     * [Recomposer.hasPendingWork] without re-resolving it via reflection on every poll.
     */
    private val attachments = ConcurrentHashMap<String, Attachment>()

    @Volatile private var closed: Boolean = false

    /** Lifetime recomposition count across every attached surface. */
    public val total: Long
        get() = surfaces.values.sumOf { it.total }

    /** Sum of per-surface sliding-window rates, in recompositions per second. */
    public val ratePerSecond: Double
        get() = surfaces.values.sumOf { it.ratePerSecond() }

    /** Number of distinct surfaces this monitor has observed at least once. */
    public val activeSurfaces: Int
        get() = surfaces.size

    public fun snapshot(): RecompositionSnapshot =
        RecompositionSnapshot(
            total = total,
            ratePerSecond = ratePerSecond,
            activeSurfaces = activeSurfaces,
            windowDuration = windowDuration,
        )

    /** Per-surface breakdown. Order is not specified; sort by `surfaceId` if you need stability. */
    public fun perSurface(): List<SurfaceRecompositions> =
        surfaces.entries.map { (id, tracker) ->
            SurfaceRecompositions(
                surfaceId = id,
                total = tracker.total,
                ratePerSecond = tracker.ratePerSecond(),
            )
        }

    /** Zeros counters and clears rate windows; surface registrations remain. */
    public fun reset() {
        surfaces.values.forEach { it.reset() }
    }

    /**
     * Installs the per-pass observer on every [androidx.compose.runtime.Composition] registered
     * with [recomposer], recording each `onBeginComposition` under [surfaceId]. Subsequent
     * compositions joining the same Recomposer attach automatically; departing compositions stop
     * counting.
     *
     * Calling [attach] twice with the same [surfaceId] disposes the previous registration before
     * installing the new one — useful when reconciling against a [WindowTracker] flow where the
     * same surfaceId may flip recomposer instances across host restarts.
     */
    internal fun attach(surfaceId: String, recomposer: Recomposer) {
        check(!closed) { "RecompositionMonitor is closed" }
        detach(surfaceId)
        val tracker =
            surfaces.computeIfAbsent(surfaceId) { SurfaceTracker(windowDuration, timeSource) }
        // Key per-Composition handles by Composition reference so onCompositionUnregistered can
        // dispose precisely the matching handle instead of waiting for full detach to drain them
        // — long-lived sessions that churn through compositions would otherwise leak handles.
        val perCompositionHandles =
            ConcurrentHashMap<ObservableComposition, CompositionObserverHandle>()
        val compositionObserver = SurfaceCompositionObserver(tracker)
        val registrationHandle =
            recomposer.observe(
                object : CompositionRegistrationObserver {
                    override fun onCompositionRegistered(composition: ObservableComposition) {
                        val handle = composition.setObserver(compositionObserver)
                        val previous = perCompositionHandles.put(composition, handle)
                        // Defensive: Compose's contract says register fires once per composition,
                        // but if a host re-registers the same instance we don't want to leak.
                        previous?.dispose()
                    }

                    override fun onCompositionUnregistered(composition: ObservableComposition) {
                        perCompositionHandles.remove(composition)?.dispose()
                    }
                }
            )
        attachments[surfaceId] = Attachment(recomposer, registrationHandle, perCompositionHandles)
    }

    /**
     * Subscribes the monitor to [windowTracker]'s flow of tracked surfaces and reconciles
     * attachments on every change: surfaces that appear are resolved to a [Recomposer] via
     * [RecomposerInspector] and attached; surfaces that disappear are detached. Surfaces whose
     * recomposer cannot be resolved (overlay popups in this MVP) are skipped silently —
     * `RecomposerInspector.findRecomposer` returns `null` and the monitor leaves them off the
     * per-surface map.
     */
    internal fun subscribeTo(windowTracker: WindowTracker) {
        windowTracker.trackedWindows
            .map { it.toSet() }
            .scan<Set<TrackedWindow>, Pair<Set<TrackedWindow>, Set<TrackedWindow>>>(
                emptySet<TrackedWindow>() to emptySet()
            ) { (_, prev), curr ->
                prev to curr
            }
            .drop(1)
            .onEach { (prev, curr) ->
                val departed = prev - curr
                val arrived = curr - prev
                departed.forEach { detach(it.surfaceId) }
                // Recomposer discovery reflects through Compose Desktop internals and may invoke
                // `getScene()` (a `by lazy` accessor that touches scene state). The collector
                // runs on Dispatchers.Default but live UI access in Spectre is EDT-marshalled —
                // hop onto the EDT for the resolve+attach so reflection sees consistent state.
                arrived.forEach { tracked ->
                    val recomposer = readOnEdt { RecomposerInspector.findRecomposer(tracked) }
                    if (recomposer != null) attach(tracked.surfaceId, recomposer)
                }
            }
            .launchIn(scopeForTests)
    }

    /**
     * Suspends until every attached [Recomposer] reports no pending work AND no per-pass counter
     * has ticked for [quietPeriod]. Returns `true` on success; `false` if [timeout] elapses first —
     * recomposition idleness is the cheapest of Spectre's three idleness signals (it bypasses the
     * semantics fingerprint and screenshot paths), so prefer it when you only need to assert
     * Compose has stopped thinking.
     *
     * Note: composition-idle ≠ visual-idle. A `graphicsLayer { translationX = animatedValue }`
     * animation can change pixels every frame without triggering recomposition; conversely, a
     * recomposition that produces identical pixels still counts as not-idle. See [awaitRateBelow]
     * for a threshold-based variant.
     */
    public suspend fun awaitCompositionIdle(
        quietPeriod: Duration = DEFAULT_QUIET_PERIOD,
        timeout: Duration = DEFAULT_AWAIT_TIMEOUT,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ): Boolean {
        val result =
            withTimeoutOrNull(timeout) {
                var lastTotal = total
                var lastChange = timeSource.markNow()
                while (true) {
                    val now = timeSource.markNow()
                    val currentTotal = total
                    if (currentTotal != lastTotal) {
                        lastTotal = currentTotal
                        lastChange = now
                    }
                    val anyPending = attachments.values.any { it.recomposer.hasPendingWork }
                    if (!anyPending && now - lastChange >= quietPeriod) {
                        return@withTimeoutOrNull true
                    }
                    delay(pollInterval)
                }
                @Suppress("UNREACHABLE_CODE") true
            }
        return result ?: false
    }

    /**
     * Suspends until [ratePerSecond] is strictly below [threshold] across all attached surfaces, or
     * [timeout] elapses. Useful for assertions like "after this interaction, no surface should be
     * recomposing more than N times per second."
     */
    public suspend fun awaitRateBelow(
        threshold: Double,
        timeout: Duration = DEFAULT_AWAIT_TIMEOUT,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ): Boolean {
        val result =
            withTimeoutOrNull(timeout) {
                while (ratePerSecond >= threshold) {
                    delay(pollInterval)
                }
                true
            }
        return result ?: false
    }

    /**
     * Disposes the per-Recomposer registration for [surfaceId] and clears that surface's rate ring
     * buffer so its `ratePerSecond` immediately reads as `0`. Lifetime `total` is preserved —
     * detaching stops new events from counting toward the rate, not from being remembered. Without
     * this clear, a closed popup would keep [awaitRateBelow] failing until the sliding window
     * naturally expires.
     */
    internal fun detach(surfaceId: String) {
        attachments.remove(surfaceId)?.dispose()
        surfaces[surfaceId]?.clearRateWindow()
    }

    /**
     * Test seam: records a recomposition for [surfaceId] as if `onBeginComposition` fired on the
     * attached observer. Production code uses the real [CompositionObserver] installed by [attach];
     * unit tests use this hook to drive deterministic counter / rate scenarios without standing up
     * a live Recomposer.
     */
    internal fun recordRecomposition(surfaceId: String) {
        val tracker =
            surfaces.computeIfAbsent(surfaceId) { SurfaceTracker(windowDuration, timeSource) }
        tracker.record()
    }

    override fun close() {
        if (closed) return
        closed = true
        attachments.keys.toList().forEach { detach(it) }
        scopeForTests.cancel()
    }

    private class Attachment(
        val recomposer: Recomposer,
        private val registrationHandle: CompositionObserverHandle,
        private val perCompositionHandles:
            ConcurrentHashMap<ObservableComposition, CompositionObserverHandle>,
    ) {
        fun dispose() {
            // Dispose the Recomposer-level registration first so no new compositions arrive while
            // we tear down per-composition handles. Any handles still in the map at this point
            // belong to compositions that hadn't yet unregistered themselves.
            registrationHandle.dispose()
            perCompositionHandles.values.forEach { it.dispose() }
            perCompositionHandles.clear()
        }
    }

    private class SurfaceCompositionObserver(private val tracker: SurfaceTracker) :
        CompositionObserver {

        override fun onBeginComposition(composition: ObservableComposition) {
            tracker.record()
        }

        // Per-scope hooks are deliberately no-ops: counting recomposition passes only requires
        // begin, and the per-scope callbacks are the expensive ones we explicitly opt out of.
        // (We also skip `onEndComposition` because the begin event already increments the counter;
        // measuring "in-progress" composition duration is out of scope for this MVP.)
        override fun onScopeEnter(scope: RecomposeScope): Unit = Unit

        override fun onReadInScope(scope: RecomposeScope, value: Any): Unit = Unit

        override fun onScopeExit(scope: RecomposeScope): Unit = Unit

        override fun onEndComposition(composition: ObservableComposition): Unit = Unit

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?): Unit = Unit

        override fun onScopeDisposed(scope: RecomposeScope): Unit = Unit
    }

    public companion object {
        public val DEFAULT_WINDOW: Duration = 1.seconds
        public val DEFAULT_AWAIT_TIMEOUT: Duration = 5.seconds
        public val DEFAULT_QUIET_PERIOD: Duration = 50.milliseconds
        public val DEFAULT_POLL_INTERVAL: Duration = 16.milliseconds
    }
}

/** Immutable point-in-time snapshot returned by [RecompositionMonitor.snapshot]. */
@ExperimentalSpectreApi
public data class RecompositionSnapshot(
    val total: Long,
    val ratePerSecond: Double,
    val activeSurfaces: Int,
    val windowDuration: Duration,
)

/** Per-surface row returned by [RecompositionMonitor.perSurface]. */
@ExperimentalSpectreApi
public data class SurfaceRecompositions(
    val surfaceId: String,
    val total: Long,
    val ratePerSecond: Double,
)

/**
 * Per-surface counter + sliding-window ring buffer. Thread-safe: every method synchronises on the
 * tracker so concurrent recompositions across Compose's internal dispatchers can't tear state.
 */
private class SurfaceTracker(
    private val windowDuration: Duration,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private val lock = Any()
    @Volatile private var totalCount: Long = 0L
    private val recentMarks = ArrayDeque<ComparableTimeMark>()

    val total: Long
        get() = totalCount

    fun record() {
        synchronized(lock) {
            totalCount++
            val now = timeSource.markNow()
            recentMarks.addLast(now)
            pruneOld(now)
        }
    }

    fun ratePerSecond(): Double =
        synchronized(lock) {
            val now = timeSource.markNow()
            pruneOld(now)
            val seconds = windowDuration.toDouble(DurationUnit.SECONDS)
            if (seconds <= 0.0) 0.0 else recentMarks.size / seconds
        }

    fun reset() {
        synchronized(lock) {
            totalCount = 0L
            recentMarks.clear()
        }
    }

    /** Clears the rate ring buffer without touching [total]. Called on detach. */
    fun clearRateWindow() {
        synchronized(lock) { recentMarks.clear() }
    }

    private fun pruneOld(now: ComparableTimeMark) {
        while (recentMarks.isNotEmpty() && now - recentMarks.first() > windowDuration) {
            recentMarks.removeFirst()
        }
    }
}

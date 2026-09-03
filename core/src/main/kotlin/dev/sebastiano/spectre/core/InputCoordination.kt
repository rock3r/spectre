@file:OptIn(
    InternalSpectreApi::class,
    dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class,
)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.CoordinatedInputLease
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.input.InputCoordinatorClientFactory
import dev.sebastiano.spectre.input.InputCoordinatorException
import dev.sebastiano.spectre.input.LeaseErrorCode
import dev.sebastiano.spectre.input.LocalCoordinatorEnvironment
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import java.io.IOException
import java.time.Duration as JavaDuration
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Controls whether this driver participates in cooperative desktop input coordination. */
@ExperimentalSpectreInputCoordinationApi
public enum class InputLeasePolicy {
    /**
     * Coordinates shared OS resources when the process artifact is available; a missing provider
     * degrades to uncoordinated operation while all other coordinator failures remain loud.
     */
    Auto,

    /** Requires coordination for every capability that touches shared OS state. */
    Required,

    /** Explicitly opts out because coordination is provided externally or is not wanted. */
    Off,
}

/** Read-only capabilities used by JUnit and advanced integrations to choose isolation. */
@ExperimentalSpectreInputCoordinationApi
public data class InputCapabilities(
    public val realOsInput: Boolean,
    public val sharedSystemClipboard: Boolean,
) {
    public val requiresDesktopCoordination: Boolean
        get() = realOsInput || sharedSystemClipboard
}

/** Acquisition options for an explicit input transaction or whole-test lease. */
@ExperimentalSpectreInputCoordinationApi
public data class InputLeaseOptions(
    public val acquireTimeout: Duration = 30.seconds,
    public val ownerLabel: String? = null,
)

/** Raised instead of waiting for a contended lease on the AUT's AWT event-dispatch thread. */
@ExperimentalSpectreInputCoordinationApi
public class ContendedEdtInputLeaseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Internal integration handle used by Spectre's in-process test wrappers to bind one lease to an
 * automator for a complete test lifecycle.
 */
@InternalSpectreApi
public interface AutomatorInputLease : AutoCloseable {
    /** Binds this lease to [automator] until the returned handle is closed. */
    public fun bind(automator: ComposeAutomator): AutoCloseable

    /** Makes this lease ambient while synchronous factory/setup code creates its automator. */
    public fun <T> withLease(block: () -> T): T = block()
}

/** Internal integration entry point for acquiring whole-test desktop input leases. */
@InternalSpectreApi
public object DesktopInputIsolation {
    /** Acquires a lease synchronously for a JUnit lifecycle running off the AWT EDT. */
    public fun acquire(options: InputLeaseOptions = InputLeaseOptions()): AutomatorInputLease {
        val coordinator = ProductionInputLeaseCoordinator()
        val lease =
            runCatching {
                    runBlocking { coordinator.acquire(options, "junitPerTest", immediate = false) }
                }
                .getOrElse { failure ->
                    coordinator.close()
                    throw failure
                }
        return ProductionAutomatorInputLease(lease, coordinator)
    }
}

private class ProductionAutomatorInputLease(
    private val lease: CoordinatedInputLease,
    private val coordinator: ProductionInputLeaseCoordinator,
) : AutomatorInputLease {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    override fun bind(automator: ComposeAutomator): AutoCloseable = automator.bindInputLease(lease)

    override fun <T> withLease(block: () -> T): T = AmbientInputLease.withLease(lease, block)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lease.close()
        } finally {
            coordinator.close()
        }
    }
}

internal fun interface InputLeaseCoordinator : AutoCloseable {
    suspend fun acquire(
        options: InputLeaseOptions,
        currentOperation: String,
        immediate: Boolean,
    ): CoordinatedInputLease

    override fun close(): Unit = Unit
}

internal enum class CoordinatedResource {
    DESKTOP_ANY,
    REAL_INPUT,
    SYSTEM_CLIPBOARD,
    FOCUS,
}

internal object AmbientInputLease {
    private val current = ThreadLocal<CoordinatedInputLease?>()

    fun current(): CoordinatedInputLease? = current.get()

    fun <T> withLease(lease: CoordinatedInputLease, block: () -> T): T {
        val previous = current.get()
        current.set(lease)
        return try {
            lease.checkpoint()
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

internal class InputLeaseGuard(
    private val policy: InputLeasePolicy,
    private val capabilities: InputCapabilities,
    private val coordinator: InputLeaseCoordinator,
) {
    private val boundLease = AtomicReference<CoordinatedInputLease?>()
    private val blockingContext = ThreadLocal<BlockingLeaseContext?>()
    private val operationMutex = Mutex()

    suspend fun <T> withOperation(
        currentOperation: String,
        resource: CoordinatedResource,
        options: InputLeaseOptions = InputLeaseOptions(),
        block: suspend () -> T,
    ): T {
        val operationContext = coroutineContext[LeaseContext]
        if (operationContext?.guard === this) {
            operationContext.lease?.checkpoint()
            // Child coroutines inherit LeaseContext but have distinct Jobs. Serialize those
            // siblings while keeping same-coroutine composite operations reentrant.
            if (operationContext.ownerJob === coroutineContext[Job]) return block()
            return operationContext.childOperationMutex.withLock {
                withLeaseContext(operationContext.lease, block)
            }
        }
        val bound = boundLease.get()
        val ambient = AmbientInputLease.current()
        if (bound == null && ambient == null && !coordinates(resource)) return block()
        return operationMutex.withLock {
            val existingLease = bound ?: ambient
            if (existingLease != null) {
                existingLease.checkpoint()
                return@withLock withLeaseContext(existingLease, block)
            }
            val immediate = SwingUtilities.isEventDispatchThread()
            val lease =
                try {
                    coordinator.acquire(options, currentOperation, immediate)
                } catch (failure: InputCoordinatorException) {
                    if (
                        policy == InputLeasePolicy.Auto &&
                            failure.errorCode in AUTO_DEGRADE_ERROR_CODES
                    ) {
                        return@withLock withLeaseContext(null, block)
                    }
                    if (immediate) {
                        throw ContendedEdtInputLeaseException(
                            "Desktop input is contended on the EDT (${failure.message}). " +
                                "Acquire with withExclusiveInput from a non-EDT test thread or " +
                                "use JUnit per-test input isolation.",
                            failure,
                        )
                    }
                    throw withRecoveryAdvice(failure)
                }
            try {
                lease.checkpoint()
                withLeaseContext(lease, block)
            } finally {
                lease.close()
            }
        }
    }

    fun <T> withBlockingOperation(
        currentOperation: String,
        resource: CoordinatedResource,
        block: () -> T,
    ): T {
        val activeContext = blockingContext.get()
        val leaseContext = activeContext?.leaseContext
        if (leaseContext?.guard === this) {
            leaseContext.lease?.checkpoint()
            if (
                leaseContext.ownerJob === activeContext.currentJob ||
                    activeContext.ownsChildOperationMutex
            ) {
                return block()
            }
            return runBlocking {
                leaseContext.childOperationMutex.withLock {
                    withBlockingContext(activeContext.copy(ownsChildOperationMutex = true), block)
                }
            }
        }
        return runBlocking { withOperation(currentOperation, resource) { block() } }
    }

    suspend fun checkpoint() {
        boundLease.get()?.checkpoint()
            ?: AmbientInputLease.current()?.checkpoint()
            ?: coroutineContext[LeaseContext]?.takeIf { it.guard === this }?.lease?.checkpoint()
    }

    fun bind(lease: CoordinatedInputLease): AutoCloseable {
        check(boundLease.compareAndSet(null, lease)) {
            "This RobotDriver is already bound to a live per-test input lease"
        }
        return AutoCloseable { boundLease.compareAndSet(lease, null) }
    }

    /**
     * Adds the recovery story to a failure that leaves a [InputLeasePolicy.Required] driver with no
     * way forward, and returns every other failure untouched.
     *
     * Scoped tightly on purpose. Only [UNREACHABLE_COORDINATOR_ERROR_CODES] mean *there is no
     * coordinator to talk to*, which is the one situation where proceeding uncoordinated is a
     * sensible thing for a human to choose. `ACQUIRE_TIMEOUT` and `FENCED` are the opposite: the
     * coordinator worked, and somebody else holds the desktop. Answering those with "you can turn
     * coordination off" would talk a user into the exact interleaved-input corruption the lease had
     * just prevented, so they keep the plain message.
     */
    private fun withRecoveryAdvice(failure: InputCoordinatorException): InputCoordinatorException {
        if (policy != InputLeasePolicy.Required) return failure
        if (failure.errorCode !in UNREACHABLE_COORDINATOR_ERROR_CODES) return failure
        return InputCoordinatorException(failure.errorCode, unreachableCoordinatorMessage(failure))
            .apply { initCause(failure) }
    }

    private fun coordinates(resource: CoordinatedResource): Boolean {
        if (policy == InputLeasePolicy.Off) return false
        return when (resource) {
            CoordinatedResource.DESKTOP_ANY -> true
            CoordinatedResource.REAL_INPUT -> capabilities.realOsInput
            // Synthetic input already skips REAL_INPUT. FOCUS has to follow that, otherwise
            // attach's first focusWindow is the only verb that needs a live coordinator.
            CoordinatedResource.FOCUS -> capabilities.realOsInput
            CoordinatedResource.SYSTEM_CLIPBOARD -> capabilities.sharedSystemClipboard
        }
    }

    private suspend fun <T> withLeaseContext(
        lease: CoordinatedInputLease?,
        block: suspend () -> T,
    ): T {
        val context = LeaseContext(this, lease, coroutineContext[Job])
        return withContext(context + BlockingLeaseContextElement(context, blockingContext)) {
            block()
        }
    }

    private fun <T> withBlockingContext(context: BlockingLeaseContext, block: () -> T): T {
        val previous = blockingContext.get()
        blockingContext.set(context)
        return try {
            block()
        } finally {
            if (previous == null) blockingContext.remove() else blockingContext.set(previous)
        }
    }

    private data class BlockingLeaseContext(
        val leaseContext: LeaseContext,
        val currentJob: Job?,
        val ownsChildOperationMutex: Boolean = false,
    )

    private class BlockingLeaseContextElement(
        private val leaseContext: LeaseContext,
        private val threadLocal: ThreadLocal<BlockingLeaseContext?>,
    ) :
        ThreadContextElement<BlockingLeaseContext?>,
        AbstractCoroutineContextElement(BlockingLeaseContextElement) {
        override fun updateThreadContext(context: CoroutineContext): BlockingLeaseContext? {
            val previous = threadLocal.get()
            threadLocal.set(BlockingLeaseContext(leaseContext, context[Job]))
            return previous
        }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: BlockingLeaseContext?,
        ) {
            if (oldState == null) threadLocal.remove() else threadLocal.set(oldState)
        }

        companion object Key : CoroutineContext.Key<BlockingLeaseContextElement>
    }

    private class LeaseContext(
        val guard: InputLeaseGuard,
        val lease: CoordinatedInputLease?,
        val ownerJob: Job?,
    ) : AbstractCoroutineContextElement(LeaseContext) {
        val childOperationMutex: Mutex = Mutex()

        companion object Key : CoroutineContext.Key<LeaseContext>
    }

    private companion object {
        val AUTO_DEGRADE_ERROR_CODES: Set<String> =
            setOf("COORDINATOR_PROVIDER_MISSING", "COORDINATOR_SESSION_UNAVAILABLE")

        /**
         * Failures that mean no coordinator could be reached at all, as opposed to one that
         * answered and said no.
         *
         * `COORDINATOR_IO` is the wedged case from #462: the launching provider spends its startup
         * budget, gives up with an `IOException`, and `ProductionInputLeaseCoordinator` reports it
         * under this code. `COORDINATOR_PROVIDER_MISSING` is the coordinator artifact being absent
         * from the classpath — a different cause, same dead end for the user.
         */
        val UNREACHABLE_COORDINATOR_ERROR_CODES: Set<String> =
            setOf("COORDINATOR_IO", "COORDINATOR_PROVIDER_MISSING")
    }
}

/**
 * Tells a user stuck behind an unreachable coordinator what they can do and what it will cost them.
 *
 * Modelled on `undeliveredInputMessage` in `InputDeliveryWitness.kt`: keep the measured cause
 * verbatim, say plainly which part is inference, name the escape hatch precisely enough to paste,
 * and link the issue.
 *
 * Two spellings are given because there are two ways to be here and the reader knows which one they
 * are. A property name from the attach path is listed even though this code also runs in-process:
 * the attach path is where the dead end was total (#472), it is where a user has no other lever,
 * and an unmistakably-labelled line that does not apply costs a reader a second — whereas omitting
 * it costs the CLI user the only route they have.
 *
 * The cost sentence is not decoration. Coordination is mutual exclusion, and a user who disables it
 * without understanding that has been handed a subtler bug than the one they were trying to escape.
 */
private fun unreachableCoordinatorMessage(failure: InputCoordinatorException): String =
    "${failure.message.orEmpty().trimEnd('.', ' ')}. " +
        "Spectre could not reach the desktop input coordinator " +
        "(${failure.errorCode}), and this driver runs with InputLeasePolicy.Required, which fails " +
        "rather than continuing uncoordinated. Measured: no coordinator answered. Not measured: " +
        "whether it is wedged, absent, or merely slower than its startup budget. " +
        "To proceed without coordination, deliberately, when attaching to a target: pass " +
        "-Ddev.sebastiano.spectre.agent.inputCoordination=disabled on the attaching JVM, or set " +
        "AttachOptions.inputCoordination = AttachInputCoordination.Disabled; in-process: construct " +
        "the driver as RobotDriver(InputLeasePolicy.Off). What that costs you: coordination is " +
        "what stops two Spectre processes driving the same mouse and keyboard at once, so only do " +
        "it when you know nothing else is automating this desktop. " +
        "See https://github.com/rock3r/spectre/issues/472"

internal class DriverInputCoordination(private val guard: InputLeaseGuard) {
    suspend fun checkpoint() {
        guard.checkpoint()
    }

    suspend fun <T> withOperation(
        operation: String,
        resource: CoordinatedResource,
        block: suspend () -> T,
    ): T = guard.withOperation(operation, resource, block = block)

    suspend fun <T> withExclusiveInput(options: InputLeaseOptions, block: suspend () -> T): T =
        guard.withOperation(
            currentOperation = "exclusiveInput",
            resource = CoordinatedResource.DESKTOP_ANY,
            options = options,
            block = block,
        )

    fun bind(lease: CoordinatedInputLease): AutoCloseable = guard.bind(lease)

    fun <T> withBlockingInput(operation: String, block: () -> T): T =
        guard.withBlockingOperation(operation, CoordinatedResource.FOCUS, block)
}

internal suspend fun <T> RobotDriver.withExclusiveInput(
    options: InputLeaseOptions,
    block: suspend () -> T,
): T = inputCoordination.withExclusiveInput(options, block)

internal fun RobotDriver.bindInputLease(lease: CoordinatedInputLease): AutoCloseable =
    inputCoordination.bind(lease)

internal fun <T> RobotDriver.withBlockingInput(operation: String, block: () -> T): T =
    inputCoordination.withBlockingInput(operation, block)

internal class ProductionInputLeaseCoordinator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectClient: ((String?) -> LocalInputCoordinatorClient)? = null,
) : InputLeaseCoordinator, AutoCloseable {
    private val client = AtomicReference<ClientSession?>()

    override suspend fun acquire(
        options: InputLeaseOptions,
        currentOperation: String,
        immediate: Boolean,
    ): CoordinatedInputLease {
        val currentSession = client.get()
        if (
            immediate && (currentSession == null || currentSession.ownerLabel != options.ownerLabel)
        ) {
            throw InputCoordinatorException(
                errorCode = "COORDINATOR_SESSION_UNAVAILABLE",
                message =
                    "A coordinator session with the requested owner label is not connected; " +
                        "establish an exclusive lease off the EDT",
            )
        }
        if (immediate) {
            return requireNotNull(currentSession).client.tryAcquire(currentOperation)
        }
        val acquired = AtomicReference<CoordinatedInputLease?>()
        return try {
            runInterruptible(ioDispatcher) {
                    acquireBlocking(options, currentOperation).also(acquired::set)
                }
                .also { acquired.set(null) }
        } finally {
            acquired.getAndSet(null)?.close()
        }
    }

    override fun close() {
        client.getAndSet(null)?.client?.close()
    }

    private fun acquireBlocking(
        options: InputLeaseOptions,
        currentOperation: String,
    ): CoordinatedInputLease {
        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            var activeClient: LocalInputCoordinatorClient? = null
            try {
                activeClient = currentClient(options.ownerLabel)
                return activeClient.acquire(
                    JavaDuration.ofMillis(options.acquireTimeout.inWholeMilliseconds),
                    currentOperation,
                )
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Input lease acquisition was cancelled").apply {
                        initCause(failure)
                    }
                }
                activeClient?.let(::discard)
                if (attempt == MAX_CONNECT_ATTEMPTS - 1) {
                    throw InputCoordinatorException(
                            errorCode = "COORDINATOR_IO",
                            message = "Input coordinator connection failed: ${failure.message}",
                        )
                        .apply { initCause(failure) }
                }
            } catch (failure: InputCoordinatorException) {
                if (failure.errorCode != LeaseErrorCode.STALE_EPOCH.name) throw failure
                activeClient?.let(::discard)
                if (attempt == MAX_CONNECT_ATTEMPTS - 1) throw failure
            }
        }
        error("Input coordinator acquisition retry loop exhausted")
    }

    private fun currentClient(ownerLabel: String?): LocalInputCoordinatorClient {
        while (true) {
            val current = client.get()
            if (current != null && current.ownerLabel == ownerLabel) return current.client
            val opened = ClientSession(ownerLabel, openClient(ownerLabel))
            if (client.compareAndSet(current, opened)) {
                current?.client?.close()
                return opened.client
            }
            opened.client.close()
        }
    }

    private fun discard(staleClient: LocalInputCoordinatorClient) {
        while (true) {
            val current = client.get() ?: return
            if (current.client !== staleClient) return
            if (client.compareAndSet(current, null)) {
                staleClient.close()
                return
            }
        }
    }

    private fun openClient(ownerLabel: String?): LocalInputCoordinatorClient =
        connectClient?.invoke(ownerLabel) ?: connectCoordinator(ownerLabel)

    private fun connectCoordinator(ownerLabel: String?): LocalInputCoordinatorClient =
        try {
            InputCoordinatorClientFactory.connectOrStart(ownerLabel = ownerLabel)
        } catch (failure: InputCoordinatorException) {
            if (failure.errorCode != "COORDINATOR_PROVIDER_MISSING") throw failure
            try {
                LocalInputCoordinatorClient.connect(
                    LocalCoordinatorEnvironment.defaultEndpoint(),
                    LocalCoordinatorEnvironment.defaultDesktopResourceKey(),
                    ownerLabel,
                )
            } catch (connectionFailure: IOException) {
                failure.addSuppressed(connectionFailure)
                throw failure
            }
        }

    private data class ClientSession(
        val ownerLabel: String?,
        val client: LocalInputCoordinatorClient,
    )

    private companion object {
        const val MAX_CONNECT_ATTEMPTS: Int = 2
    }
}

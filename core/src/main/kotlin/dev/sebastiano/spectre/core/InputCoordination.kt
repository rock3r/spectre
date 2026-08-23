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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lease.close()
        } finally {
            coordinator.close()
        }
    }
}

internal fun interface InputLeaseCoordinator {
    suspend fun acquire(
        options: InputLeaseOptions,
        currentOperation: String,
        immediate: Boolean,
    ): CoordinatedInputLease
}

internal enum class CoordinatedResource {
    DESKTOP_ANY,
    REAL_INPUT,
    SYSTEM_CLIPBOARD,
    FOCUS,
}

internal class InputLeaseGuard(
    private val policy: InputLeasePolicy,
    private val capabilities: InputCapabilities,
    private val coordinator: InputLeaseCoordinator,
) {
    private val boundLease = AtomicReference<CoordinatedInputLease?>()

    suspend fun <T> withOperation(
        currentOperation: String,
        resource: CoordinatedResource,
        options: InputLeaseOptions = InputLeaseOptions(),
        block: suspend () -> T,
    ): T {
        boundLease.get()?.let { lease ->
            lease.checkpoint()
            return block()
        }
        if (!coordinates(resource)) return block()
        val ambient = coroutineContext[LeaseContext]
        if (ambient?.guard === this) {
            ambient.lease.checkpoint()
            return block()
        }
        val immediate = SwingUtilities.isEventDispatchThread()
        val lease =
            try {
                coordinator.acquire(options, currentOperation, immediate)
            } catch (failure: InputCoordinatorException) {
                if (
                    policy == InputLeasePolicy.Auto && failure.errorCode in AUTO_DEGRADE_ERROR_CODES
                ) {
                    return block()
                }
                if (immediate) {
                    throw ContendedEdtInputLeaseException(
                        "Desktop input is contended on the EDT (${failure.message}). " +
                            "Acquire with withExclusiveInput from a non-EDT test thread or use " +
                            "JUnit per-test input isolation.",
                        failure,
                    )
                }
                throw failure
            }
        return try {
            lease.checkpoint()
            withContext(LeaseContext(this, lease)) { block() }
        } finally {
            lease.close()
        }
    }

    suspend fun checkpoint() {
        boundLease.get()?.checkpoint()
            ?: coroutineContext[LeaseContext]?.takeIf { it.guard === this }?.lease?.checkpoint()
    }

    fun bind(lease: CoordinatedInputLease): AutoCloseable {
        check(boundLease.compareAndSet(null, lease)) {
            "This RobotDriver is already bound to a live per-test input lease"
        }
        return AutoCloseable { boundLease.compareAndSet(lease, null) }
    }

    private fun coordinates(resource: CoordinatedResource): Boolean {
        if (policy == InputLeasePolicy.Off) return false
        return when (resource) {
            CoordinatedResource.DESKTOP_ANY -> capabilities.requiresDesktopCoordination
            CoordinatedResource.REAL_INPUT,
            CoordinatedResource.FOCUS -> capabilities.realOsInput
            CoordinatedResource.SYSTEM_CLIPBOARD -> capabilities.sharedSystemClipboard
        }
    }

    private class LeaseContext(val guard: InputLeaseGuard, val lease: CoordinatedInputLease) :
        AbstractCoroutineContextElement(LeaseContext) {
        companion object Key : CoroutineContext.Key<LeaseContext>
    }

    private companion object {
        val AUTO_DEGRADE_ERROR_CODES: Set<String> =
            setOf("COORDINATOR_PROVIDER_MISSING", "COORDINATOR_SESSION_UNAVAILABLE")
    }
}

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

    fun <T> withBlockingInput(operation: String, block: () -> T): T = runBlocking {
        guard.withOperation(operation, CoordinatedResource.FOCUS) { block() }
    }
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
    private val client = AtomicReference<LocalInputCoordinatorClient?>()

    override suspend fun acquire(
        options: InputLeaseOptions,
        currentOperation: String,
        immediate: Boolean,
    ): CoordinatedInputLease {
        if (immediate && client.get() == null) {
            throw InputCoordinatorException(
                errorCode = "COORDINATOR_SESSION_UNAVAILABLE",
                message =
                    "The coordinator session is not connected; establish an exclusive lease " +
                        "off the EDT",
            )
        }
        if (immediate) {
            return requireNotNull(client.get()).tryAcquire(currentOperation)
        }
        return runInterruptible(ioDispatcher) { acquireBlocking(options, currentOperation) }
    }

    override fun close() {
        client.getAndSet(null)?.close()
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

    private fun currentClient(ownerLabel: String?): LocalInputCoordinatorClient =
        client.get()
            ?: openClient(ownerLabel)
                .also { opened -> if (!client.compareAndSet(null, opened)) opened.close() }
                .let { client.get() ?: it }

    private fun discard(staleClient: LocalInputCoordinatorClient) {
        if (client.compareAndSet(staleClient, null)) staleClient.close()
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

    private companion object {
        const val MAX_CONNECT_ATTEMPTS: Int = 2
    }
}

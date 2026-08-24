@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.CoordinatorWireCodec
import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.input.OwnerOnlyEndpointProtection
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Same-user local coordinator server hosted in a small external JVM process. */
@ExperimentalSpectreInputCoordinationApi
public class LocalCoordinatorServer(
    private val endpoint: CoordinatorEndpoint,
    heartbeatTimeout: Duration = Duration.ofSeconds(DEFAULT_HEARTBEAT_TIMEOUT_SECONDS),
    private val idleTimeout: Duration = Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS),
    recoveryGrace: Duration = Duration.ofSeconds(DEFAULT_RECOVERY_GRACE_SECONDS),
    revokeGrace: Duration = Duration.ofSeconds(DEFAULT_REVOKE_GRACE_SECONDS),
    private val codec: CoordinatorWireCodec = CoordinatorWireCodec(),
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val ownsSocketPath = AtomicBoolean(false)
    private val terminated = CountDownLatch(1)
    private val activeConnections = AtomicInteger()
    private val lastActivityNanos = AtomicLong(System.nanoTime())
    private val handlers: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val initialFrameDeadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "spectre-input-frame-deadline").apply { isDaemon = true }
    }
    private val recoveryLedger =
        RecoveryLedger(endpoint.directory.resolve("recovery.properties"), heartbeatTimeout)
    private val service =
        CoordinatorLeaseService(
            epoch = UUID.randomUUID().toString(),
            heartbeatTimeout = heartbeatTimeout,
            revokeGrace = revokeGrace,
            recoveryGrace = recoveryGrace,
            recoveryRecord = recoveryLedger.load(),
            recoveryLedger = recoveryLedger,
        )
    private var listener: ServerSocketChannel? = null
    private var acceptThread: Thread? = null
    private var electionChannel: FileChannel? = null
    private var electionLock: FileLock? = null
    private var idleMonitor: java.util.concurrent.ScheduledExecutorService? = null

    init {
        require(!idleTimeout.isNegative && !idleTimeout.isZero) { "Idle timeout must be positive" }
    }

    /** Binds the endpoint and starts accepting local client sessions. */
    @Synchronized
    public fun start() {
        check(!closed.get()) { "Coordinator server is closed" }
        check(!running.get()) { "Coordinator server is already started" }
        val protection = OwnerOnlyEndpointProtection.forPath(endpoint.socketPath)
        protection.prepareDirectory(endpoint.socketPath)
        acquireElection()
        var started = false
        var openedChannel: ServerSocketChannel? = null
        try {
            prepareSocketPath()
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            openedChannel = channel
            channel.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
            ownsSocketPath.set(true)
            protection.protectSocket(endpoint.socketPath)
            listener = channel
            running.set(true)
            lastActivityNanos.set(System.nanoTime())
            startIdleMonitor()
            acceptThread =
                Thread.ofVirtual().name("spectre-input-accept").start { acceptLoop(channel) }
            started = true
        } finally {
            if (!started) {
                runCatching { openedChannel?.close() }
                releaseElection()
            }
        }
    }

    /** Blocks until this server's accept loop terminates. */
    @Throws(InterruptedException::class)
    public fun awaitTermination() {
        terminated.await()
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { listener?.close() }
        idleMonitor?.shutdownNow()
        handlers.shutdownNow()
        handlers.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
        initialFrameDeadlines.shutdownNow()
        service.close()
        if (ownsSocketPath.compareAndSet(true, false)) {
            runCatching { Files.deleteIfExists(endpoint.socketPath) }
        }
        releaseElection()
        terminated.countDown()
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (running.get()) {
            try {
                val client = channel.accept()
                activeConnections.incrementAndGet()
                lastActivityNanos.set(System.nanoTime())
                if (activeConnections.get() > MAX_ACTIVE_CONNECTIONS) {
                    activeConnections.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                try {
                    handlers.submit {
                        try {
                            handle(client)
                        } finally {
                            activeConnections.decrementAndGet()
                            lastActivityNanos.set(System.nanoTime())
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    activeConnections.decrementAndGet()
                    runCatching { client.close() }
                    if (running.get()) close()
                    return
                }
            } catch (failure: IOException) {
                if (running.get()) {
                    Thread.currentThread()
                        .uncaughtExceptionHandler
                        .uncaughtException(Thread.currentThread(), failure)
                    close()
                }
                return
            }
        }
    }

    private fun handle(channel: SocketChannel) {
        channel.use {
            val deadline =
                initialFrameDeadlines.schedule(
                    { runCatching { channel.close() } },
                    initialFrameTimeoutMillis(),
                    TimeUnit.MILLISECONDS,
                )
            val request =
                try {
                    codec.readOrNull(channel) ?: return
                } finally {
                    deadline.cancel(false)
                }
            if (request.kind == CoordinatorWireKind.SESSION_OPEN) {
                handleSession(channel, request)
            } else {
                val response = handleRequest(request)
                codec.write(channel, response)
            }
        }
    }

    private fun handleSession(channel: SocketChannel, request: CoordinatorWireMessage) {
        val clientId = requireNotNull(request.clientId)
        service.openSession(clientId)
        try {
            codec.write(
                channel,
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.RESPONSE,
                    coordinatorEpoch = service.epoch,
                ),
            )
            while (codec.readOrNull(channel) != null) {
                // The session connection is a liveness sentinel; operations use bounded
                // connections.
            }
        } finally {
            if (running.get()) service.disconnect(clientId)
        }
    }

    private fun handleRequest(request: CoordinatorWireMessage): CoordinatorWireMessage =
        when (request.kind) {
            CoordinatorWireKind.HEALTH ->
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.RESPONSE,
                    coordinatorEpoch = service.epoch,
                )
            CoordinatorWireKind.ACQUIRE ->
                try {
                    service
                        .acquire(request)
                        .get(
                            acquireResponseTimeoutMillis(requireNotNull(request.timeoutMillis)),
                            TimeUnit.MILLISECONDS,
                        )
                } catch (_: TimeoutException) {
                    service.cancel(request.copy(kind = CoordinatorWireKind.CANCEL))
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.RESPONSE,
                        ok = false,
                        errorCode = "ACQUIRE_TIMEOUT",
                        message = "Timed out waiting for the desktop input lease",
                    )
                }
            CoordinatorWireKind.RELEASE -> service.release(request)
            CoordinatorWireKind.HEARTBEAT -> service.heartbeat(request)
            CoordinatorWireKind.STATUS -> service.status(request)
            CoordinatorWireKind.REVOKE -> service.revoke(request)
            CoordinatorWireKind.CANCEL -> service.cancel(request)
            CoordinatorWireKind.SESSION_OPEN,
            CoordinatorWireKind.RESPONSE ->
                CoordinatorWireMessage(
                    kind = CoordinatorWireKind.RESPONSE,
                    ok = false,
                    errorCode = "UNSUPPORTED_OPERATION",
                    message = "Unsupported coordinator operation ${request.kind}",
                )
        }

    private fun acquireResponseTimeoutMillis(timeoutMillis: Long): Long =
        timeoutMillis.coerceAtMost(Long.MAX_VALUE - ACQUIRE_RESPONSE_GRACE_MILLIS) +
            ACQUIRE_RESPONSE_GRACE_MILLIS

    private fun initialFrameTimeoutMillis(): Long =
        idleTimeout.toMillis().coerceAtMost(DEFAULT_INITIAL_FRAME_TIMEOUT_MILLIS).coerceAtLeast(1)

    private fun acquireElection() {
        val lockPath = endpoint.directory.resolve("coordinator.lock")
        val channel = FileChannel.open(lockPath, CREATE, WRITE)
        val lock =
            try {
                channel.tryLock()
            } catch (failure: IOException) {
                runCatching { channel.close() }
                throw failure
            } catch (failure: OverlappingFileLockException) {
                runCatching { channel.close() }
                throw failure
            }
        if (lock == null) {
            channel.close()
            throw IOException("A coordinator already owns ${endpoint.socketPath}")
        }
        electionChannel = channel
        electionLock = lock
    }

    private fun startIdleMonitor() {
        val intervalMillis =
            (idleTimeout.toMillis() / IDLE_CHECK_DIVISOR).coerceIn(
                MIN_IDLE_CHECK_MILLIS,
                MAX_IDLE_CHECK_MILLIS,
            )
        idleMonitor =
            Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "spectre-input-idle").apply { isDaemon = true }
                }
                .also { executor ->
                    executor.scheduleWithFixedDelay(
                        ::closeIfIdle,
                        intervalMillis,
                        intervalMillis,
                        TimeUnit.MILLISECONDS,
                    )
                }
    }

    private fun closeIfIdle() {
        if (!running.get() || activeConnections.get() != 0) return
        if (System.nanoTime() - lastActivityNanos.get() >= idleTimeout.toNanos()) close()
    }

    private fun releaseElection() {
        runCatching { electionLock?.release() }
        runCatching { electionChannel?.close() }
        electionLock = null
        electionChannel = null
    }

    private fun prepareSocketPath() {
        if (!Files.exists(endpoint.socketPath, NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(endpoint.socketPath)) {
            throw IOException(
                "Coordinator socket ${endpoint.socketPath} must not be a symbolic link"
            )
        }
        if (isLiveCoordinator()) {
            throw IOException("A coordinator is already listening at ${endpoint.socketPath}")
        }
        Files.delete(endpoint.socketPath)
    }

    private fun isLiveCoordinator(): Boolean =
        runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
                    codec.write(channel, CoordinatorWireMessage(kind = CoordinatorWireKind.HEALTH))
                    codec.read(channel).ok
                }
            }
            .getOrDefault(false)

    private companion object {
        const val DEFAULT_HEARTBEAT_TIMEOUT_SECONDS: Long = 10
        const val DEFAULT_IDLE_TIMEOUT_SECONDS: Long = 30
        const val DEFAULT_RECOVERY_GRACE_SECONDS: Long = 2
        const val DEFAULT_REVOKE_GRACE_SECONDS: Long = 1
        const val ACQUIRE_RESPONSE_GRACE_MILLIS: Long = 1_000
        const val CLOSE_WAIT_SECONDS: Long = 2
        private const val MAX_ACTIVE_CONNECTIONS: Int = 64
        private const val DEFAULT_INITIAL_FRAME_TIMEOUT_MILLIS: Long = 5_000
        const val IDLE_CHECK_DIVISOR: Long = 4
        const val MIN_IDLE_CHECK_MILLIS: Long = 10
        const val MAX_IDLE_CHECK_MILLIS: Long = 1_000
    }
}

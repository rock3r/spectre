package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.recording.locateAccessibilityHelper
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val ACCESSIBILITY_COMMAND = "accessibility-snapshot"
private const val PROCESS_GRACE_MS = 250L
private const val PROCESS_POLL_MS = 5L
private const val NANOS_PER_MILLISECOND = 1_000_000L

@Serializable
internal sealed interface Target {
    @Serializable
    @SerialName("process-id")
    data class ProcessId(val value: Long) : Target {
        init {
            require(value > 0) { "Process id must be positive" }
        }
    }

    @Serializable
    @SerialName("window-id")
    data class WindowId(val value: String) : Target {
        init {
            require(value.isNotBlank()) { "Window id must not be blank" }
        }
    }
}

@Serializable
internal data class AccessibilitySnapshotRequest(
    val target: Target,
    val maximumDepth: Int,
    val maximumNodeCount: Int,
    val timeoutMs: Long,
) {
    init {
        require(maximumDepth > 0)
        require(maximumNodeCount > 0)
        require(timeoutMs > 0)
    }
}

@Serializable
internal enum class BoundsSpace {
    @SerialName("screen") Screen,
    @SerialName("window") Window,
}

@Serializable
internal data class AccessibilityRect(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
internal data class AccessibilityWindow(
    val key: String,
    val title: String?,
    val bounds: AccessibilityRect,
    val boundsSpace: BoundsSpace,
)

@Serializable
internal data class AccessibilityNode(
    val key: String,
    val parentKey: String?,
    val id: String?,
    val platformRole: String?,
    val role: String?,
    val name: String?,
    val states: List<String>,
    val value: String?,
    val bounds: AccessibilityRect?,
    val onScreen: Boolean?,
)

@Serializable
internal data class AccessibilitySnapshot(
    val schemaVersion: Int,
    val source: String,
    val capturedAt: String,
    val windows: List<AccessibilityWindow>,
    val nodes: List<AccessibilityNode>,
    val notes: List<String> = emptyList(),
)

@Serializable
internal data class AccessibilityError(
    val code: String,
    val message: String,
    val partialSnapshot: AccessibilitySnapshot? = null,
)

internal sealed interface AccessibilitySnapshotResponse {
    data class Success(val snapshot: AccessibilitySnapshot) : AccessibilitySnapshotResponse

    data class Failure(val error: AccessibilityError) : AccessibilitySnapshotResponse
}

@Serializable
private data class HelperEnvelope(
    val ok: Boolean,
    val snapshot: AccessibilitySnapshot? = null,
    val error: AccessibilityError? = null,
)

internal fun interface AccessibilityHelperRunner {
    fun run(argv: List<String>, stdin: String, timeoutMs: Long): String
}

/** Runs the already-packaged platform helper and validates its one-request/one-response JSON. */
internal class AccessibilityHelperClient(
    private val command: (Path) -> List<String> = { listOf(it.toString()) },
    private val runner: AccessibilityHelperRunner = AccessibilityHelperRunner(::runHelper),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(
        request: AccessibilitySnapshotRequest,
        helperExecutable: Path = defaultHelperExecutable(),
    ): AccessibilitySnapshotResponse {
        val input = json.encodeToString(AccessibilitySnapshotRequest.serializer(), request)
        val output =
            runner.run(command(helperExecutable) + ACCESSIBILITY_COMMAND, input, request.timeoutMs)
        val envelope = json.decodeFromString(HelperEnvelope.serializer(), output)
        require(
            envelope.ok == (envelope.snapshot != null) && envelope.ok != (envelope.error != null)
        ) {
            "Helper response must contain exactly one coherent success or error payload"
        }
        return if (envelope.ok) {
            val snapshot = requireNotNull(envelope.snapshot)
            validateSnapshot(snapshot)
            AccessibilitySnapshotResponse.Success(snapshot)
        } else {
            val error = requireNotNull(envelope.error)
            if (error.code == "limit-reached") {
                validateSnapshot(
                    requireNotNull(error.partialSnapshot) {
                        "limit-reached response must contain a partial snapshot"
                    }
                )
            } else {
                error.partialSnapshot?.let(::validateSnapshot)
            }
            AccessibilitySnapshotResponse.Failure(error)
        }
    }
}

private fun validateSnapshot(snapshot: AccessibilitySnapshot) {
    require(snapshot.schemaVersion == 2) { "Unsupported schemaVersion ${snapshot.schemaVersion}" }
    require(snapshot.source == "os-accessibility") {
        "Invalid snapshot source '${snapshot.source}'"
    }
    val windowKeys = snapshot.windows.mapTo(mutableSetOf()) { it.key }
    require(windowKeys.size == snapshot.windows.size) { "Snapshot contains duplicate window keys" }
    val nodeKeys = snapshot.nodes.mapTo(mutableSetOf()) { it.key }
    require(nodeKeys.size == snapshot.nodes.size) { "Snapshot contains duplicate node keys" }
    snapshot.nodes.forEach { node ->
        require(node.parentKey == null || node.parentKey in nodeKeys) {
            "Node '${node.key}' refers to missing parent '${node.parentKey}'"
        }
        require(windowKeys.any { node.key == it || node.key.startsWith("$it/") }) {
            "Node '${node.key}' does not belong to a snapshot window"
        }
    }
}

private fun runHelper(argv: List<String>, stdin: String, timeoutMs: Long): String {
    val process = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    return exchangeWithHelper(process, stdin, timeoutMs)
}

internal fun exchangeWithHelper(process: Process, stdin: String, timeoutMs: Long): String {
    val executor = Executors.newFixedThreadPool(2, DaemonThreadFactory)
    val deadlineNanos = System.nanoTime() + (timeoutMs + PROCESS_GRACE_MS) * NANOS_PER_MILLISECOND
    val output =
        executor.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
    val input =
        executor.submit<Unit> { process.outputStream.bufferedWriter().use { it.write(stdin) } }
    var interrupted = false
    try {
        input.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
        check(process.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
            "Accessibility helper exceeded its ${timeoutMs}ms deadline"
        }
        check(process.exitValue() == 0) { "Accessibility helper exited ${process.exitValue()}" }
        return output.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
    } catch (exception: InterruptedException) {
        interrupted = true
        throw exception
    } catch (exception: TimeoutException) {
        throw IllegalStateException(
            "Accessibility helper exceeded its ${timeoutMs}ms deadline",
            exception,
        )
    } catch (exception: ExecutionException) {
        throw exception.cause ?: exception
    } finally {
        // waitFor and awaitTermination return immediately when interrupted. Clear the flag while
        // cleanup runs, then restore it so callers observe both bounded reaping and interruption.
        interrupted = interrupted || Thread.interrupted()
        val cleanupDeadline = System.nanoTime() + PROCESS_GRACE_MS * NANOS_PER_MILLISECOND
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach { it.destroyForcibly() }
        // Start every owned process terminating before spending the shared grace period waiting.
        // In particular, ProcessHandle's exit notification can precede Process.waitFor updating the
        // Process object on Windows, so reap the parent through Process itself below.
        process.destroyForcibly()
        interrupted = waitForExit(process, cleanupDeadline) || interrupted
        descendants.forEach { interrupted = waitForExit(it, cleanupDeadline) || interrupted }
        input.cancel(true)
        output.cancel(true)
        executor.shutdownNow()
        try {
            executor.awaitTermination(remainingMillis(cleanupDeadline), TimeUnit.MILLISECONDS)
        } catch (cleanupInterrupted: InterruptedException) {
            interrupted = true
        }
        val cleanupFailed = process.isAlive || descendants.any { it.isAlive }
        if (interrupted) Thread.currentThread().interrupt()
        check(!cleanupFailed) { "Accessibility helper did not stop within its cleanup deadline" }
    }
}

private fun waitForExit(process: Process, deadlineNanos: Long): Boolean {
    var interrupted = false
    while (process.isAlive && System.nanoTime() < deadlineNanos) {
        try {
            process.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            interrupted = true
        }
    }
    return interrupted
}

private fun waitForExit(handle: ProcessHandle, deadlineNanos: Long): Boolean {
    var interrupted = false
    while (handle.isAlive && System.nanoTime() < deadlineNanos) {
        LockSupport.parkNanos(
            minOf(PROCESS_POLL_MS * NANOS_PER_MILLISECOND, deadlineNanos - System.nanoTime())
        )
        interrupted = Thread.interrupted() || interrupted
    }
    return interrupted
}

private fun remainingMillis(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(1)

private object DaemonThreadFactory : ThreadFactory {
    override fun newThread(task: Runnable): Thread =
        Thread(task, "spectre-accessibility-helper-io").apply { isDaemon = true }
}

private fun defaultHelperExecutable(): Path = locateAccessibilityHelper()

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.recording.locateAccessibilityHelper
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

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
            require(value > 0) { "process id must be positive" }
        }
    }

    /** Exact title target; [processId] disambiguates an attached session's native window. */
    @Serializable
    @SerialName("window-title")
    data class WindowTitle(val value: String, val processId: Long? = null) : Target {
        init {
            require(value.isNotBlank()) { "window title must not be blank" }
            require(processId == null || processId > 0) { "process id must be positive" }
        }
    }

    @Serializable
    @SerialName("window-id")
    data class WindowId(val value: String) : Target {
        init {
            require(value.isNotBlank()) { "window id must not be blank" }
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
public enum class BoundsSpace {
    @SerialName("screen") Screen,
    @SerialName("window") Window,
}

@Serializable
public data class AccessibilityRect(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
public data class AccessibilityWindow(
    val key: String,
    val title: String?,
    val bounds: AccessibilityRect,
    val boundsSpace: BoundsSpace,
)

@Serializable
public data class AccessibilityNode(
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
public data class AccessibilitySnapshot(
    val schemaVersion: Int,
    val source: String,
    val capturedAt: String,
    val windows: List<AccessibilityWindow>,
    val nodes: List<AccessibilityNode>,
    val notes: List<String> = emptyList(),
)

@Serializable
public enum class AccessibilityErrorCode {
    @SerialName("permission-missing") PermissionMissing,
    @SerialName("accessibility-disabled") AccessibilityDisabled,
    @SerialName("target-not-found") TargetNotFound,
    @SerialName("no-accessibility-tree") NoAccessibilityTree,
    @SerialName("limit-reached") LimitReached,
    @SerialName("timeout") Timeout,
    @SerialName("unsupported-target") UnsupportedTarget,
    @SerialName("unknown") Unknown,
}

@Serializable
public data class AccessibilityError(
    val code: AccessibilityErrorCode,
    val message: String,
    val partialSnapshot: AccessibilitySnapshot? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rawCode: String? = null,
)

internal sealed interface AccessibilitySnapshotResponse {
    data class Success(val snapshot: AccessibilitySnapshot) : AccessibilitySnapshotResponse

    data class Failure(val error: AccessibilityError) : AccessibilitySnapshotResponse
}

@Serializable
private data class SnapshotDto(
    val schemaVersion: Int,
    val source: String,
    val capturedAt: String,
    val windows: List<WindowDto>,
    val nodes: List<AccessibilityNode>,
    val notes: List<String> = emptyList(),
)

@Serializable
private data class WindowDto(
    val key: String,
    val title: String?,
    val bounds: AccessibilityRect,
    val boundsSpace: String,
)

@Serializable
private data class ErrorDto(
    val code: String,
    val message: String,
    val partialSnapshot: SnapshotDto? = null,
)

internal fun interface AccessibilityHelperRunner {
    fun run(argv: List<String>, stdin: String, timeoutMs: Long): String
}

/** Runs the already-packaged platform helper and validates its one-request/one-response JSON. */
internal class AccessibilityHelperClient(
    private val helperExecutable: () -> Path = ::locateAccessibilityHelper,
    private val command: (Path) -> List<String> = { listOf(it.toString()) },
    private val runner: AccessibilityHelperRunner = AccessibilityHelperRunner(::runHelper),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(
        request: AccessibilitySnapshotRequest,
        helperExecutable: Path = this.helperExecutable(),
    ): AccessibilitySnapshotResponse {
        val input = json.encodeToString(AccessibilitySnapshotRequest.serializer(), request)
        val output =
            runner.run(command(helperExecutable) + ACCESSIBILITY_COMMAND, input, request.timeoutMs)
        val envelope = json.parseToJsonElement(output).jsonObject
        val ok =
            (envelope["ok"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        val snapshotElement = envelope["snapshot"]?.takeUnless { it is JsonNull }
        val errorElement = envelope["error"]?.takeUnless { it is JsonNull }
        require(ok != null && ok == (snapshotElement != null) && ok != (errorElement != null)) {
            "Helper response must contain exactly one coherent success or error payload"
        }
        return if (ok) {
            val snapshot =
                validateSnapshot(
                    json
                        .decodeFromJsonElement<SnapshotDto>(requireNotNull(snapshotElement))
                        .toSnapshot()
                )
            AccessibilitySnapshotResponse.Success(snapshot)
        } else {
            val dto = json.decodeFromJsonElement<ErrorDto>(requireNotNull(errorElement))
            val code = knownErrorCode(dto.code)
            val partial = dto.partialSnapshot?.let { validateSnapshot(it.toSnapshot()) }
            require(
                when (code) {
                    AccessibilityErrorCode.LimitReached -> partial != null
                    AccessibilityErrorCode.Timeout -> true
                    else -> partial == null
                }
            ) {
                "${dto.code} response has invalid partialSnapshot presence"
            }
            val error =
                AccessibilityError(
                    code,
                    dto.message,
                    partial,
                    rawCode = dto.code.takeIf { code == AccessibilityErrorCode.Unknown },
                )
            AccessibilitySnapshotResponse.Failure(error)
        }
    }
}

private fun SnapshotDto.toSnapshot(): AccessibilitySnapshot =
    AccessibilitySnapshot(
        schemaVersion,
        source,
        capturedAt,
        windows.map { window ->
            val boundsSpace =
                when (window.boundsSpace) {
                    "screen" -> BoundsSpace.Screen
                    "window" -> BoundsSpace.Window
                    else ->
                        throw IllegalArgumentException(
                            "Unknown boundsSpace '${window.boundsSpace}' for window '${window.key}'"
                        )
                }
            AccessibilityWindow(window.key, window.title, window.bounds, boundsSpace)
        },
        nodes,
        notes,
    )

private fun knownErrorCode(raw: String): AccessibilityErrorCode =
    when (raw) {
        "permission-missing" -> AccessibilityErrorCode.PermissionMissing
        "accessibility-disabled" -> AccessibilityErrorCode.AccessibilityDisabled
        "target-not-found" -> AccessibilityErrorCode.TargetNotFound
        "no-accessibility-tree" -> AccessibilityErrorCode.NoAccessibilityTree
        "limit-reached" -> AccessibilityErrorCode.LimitReached
        "timeout" -> AccessibilityErrorCode.Timeout
        "unsupported-target" -> AccessibilityErrorCode.UnsupportedTarget
        else -> AccessibilityErrorCode.Unknown
    }

private fun validateSnapshot(snapshot: AccessibilitySnapshot): AccessibilitySnapshot {
    require(snapshot.schemaVersion == 2) { "Unsupported schemaVersion ${snapshot.schemaVersion}" }
    require(snapshot.source == "os-accessibility") {
        "Invalid snapshot source '${snapshot.source}'"
    }
    val windowKeys = snapshot.windows.mapTo(mutableSetOf()) { it.key }
    require(windowKeys.size == snapshot.windows.size) { "Snapshot contains duplicate window keys" }
    val nodeKeys = mutableSetOf<String>()
    snapshot.nodes.forEach { node ->
        val window =
            requireNotNull(
                windowKeys.singleOrNull { node.key == it || node.key.startsWith("$it/") }
            ) {
                "Node '${node.key}' does not belong to a snapshot window"
            }
        require(node.key !in nodeKeys) { "Snapshot contains duplicate node key '${node.key}'" }
        node.parentKey?.let { parent ->
            require(parent in nodeKeys) {
                "Node '${node.key}' refers to a parent '$parent' that does not precede it"
            }
            require(parent == window || parent.startsWith("$window/")) {
                "Node '${node.key}' and parent '$parent' belong to different windows"
            }
        }
        nodeKeys.add(node.key)
    }
    return snapshot.ownedCopy()
}

private fun AccessibilitySnapshot.ownedCopy() =
    AccessibilitySnapshot(
        schemaVersion,
        source,
        capturedAt,
        Collections.unmodifiableList(windows.toList()),
        Collections.unmodifiableList(
            nodes.map { it.copy(states = Collections.unmodifiableList(it.states.toList())) }
        ),
        Collections.unmodifiableList(notes.toList()),
    )

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

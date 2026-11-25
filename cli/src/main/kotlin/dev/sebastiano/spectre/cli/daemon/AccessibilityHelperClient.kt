package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val ACCESSIBILITY_COMMAND = "accessibility-snapshot"
private const val PROCESS_GRACE_MS = 250L

@Serializable
internal sealed interface Target {
    @Serializable @SerialName("process-id") data class ProcessId(val value: Long) : Target

    @Serializable @SerialName("window-id") data class WindowId(val value: String) : Target
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
    val bounds: AccessibilityRect,
    val onScreen: Boolean,
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
    private val runner: AccessibilityHelperRunner = AccessibilityHelperRunner(::runHelper)
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(
        request: AccessibilitySnapshotRequest,
        helperExecutable: Path = defaultHelperExecutable(),
    ): AccessibilitySnapshotResponse {
        val input = json.encodeToString(AccessibilitySnapshotRequest.serializer(), request)
        val output =
            runner.run(
                listOf(helperExecutable.toString(), ACCESSIBILITY_COMMAND),
                input,
                request.timeoutMs,
            )
        val envelope = json.decodeFromString(HelperEnvelope.serializer(), output)
        return if (envelope.ok) {
            AccessibilitySnapshotResponse.Success(
                requireNotNull(envelope.snapshot) { "Successful helper response has no snapshot" }
            )
        } else {
            AccessibilitySnapshotResponse.Failure(
                requireNotNull(envelope.error) { "Failed helper response has no error" }
            )
        }
    }
}

private fun runHelper(argv: List<String>, stdin: String, timeoutMs: Long): String {
    val process = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    process.outputStream.bufferedWriter().use { it.write(stdin) }
    val executor = Executors.newSingleThreadExecutor()
    val output =
        executor.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
    check(process.waitFor(timeoutMs + PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        "Accessibility helper exceeded its ${timeoutMs}ms deadline"
    }
    try {
        check(process.exitValue() == 0) { "Accessibility helper exited ${process.exitValue()}" }
        return output.get(PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)
    } finally {
        executor.shutdownNow()
    }
}

private fun defaultHelperExecutable(): Path =
    Path.of(
        System.getenv("SPECTRE_CAPTURE_HELPER")
            ?: error("SPECTRE_CAPTURE_HELPER must point to the packaged native helper")
    )

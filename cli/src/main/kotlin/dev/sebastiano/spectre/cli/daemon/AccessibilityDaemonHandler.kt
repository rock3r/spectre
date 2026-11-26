package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.transport.WindowSummaryDto

internal class AccessibilityDaemonHandler(private val client: AccessibilityHelperClient) {
    fun handle(
        request: DaemonRequest.AccessibilityTree,
        sessionTarget: (String, Int) -> SessionAccessibilityTarget? = { _, _ -> null },
    ): DaemonResponse {
        validate(request)?.let {
            return it
        }
        val target = resolveTarget(request, sessionTarget)
        if (target is TargetResolution.Error) return target.response
        return snapshot(request, (target as TargetResolution.Resolved).target)
    }

    fun validate(request: DaemonRequest.AccessibilityTree): DaemonResponse.Error? {
        if (
            listOf(request.processId, request.windowTitle, request.sessionId).count {
                it != null
            } != 1
        ) {
            return protocolError("accessibility tree requires exactly one target selector")
        }
        if (
            request.windowIndex < 0 ||
                request.maximumDepth <= 0 ||
                request.maximumNodeCount <= 0 ||
                request.timeoutMs <= 0
        ) {
            return protocolError("accessibility window index and traversal budgets must be valid")
        }
        return null
    }

    private fun resolveTarget(
        request: DaemonRequest.AccessibilityTree,
        sessionTarget: (String, Int) -> SessionAccessibilityTarget?,
    ): TargetResolution =
        try {
            val target =
                when {
                    request.processId != null -> Target.ProcessId(request.processId)
                    request.windowTitle != null -> Target.WindowTitle(request.windowTitle)
                    else -> return resolveSession(request, sessionTarget)
                }
            TargetResolution.Resolved(target)
        } catch (exception: IllegalArgumentException) {
            TargetResolution.Error(
                protocolError(exception.message ?: "invalid accessibility target")
            )
        } catch (exception: java.io.IOException) {
            TargetResolution.Error(
                DaemonResponse.Error(
                    DaemonErrorCode.OperationFailed,
                    exception.message ?: "session operation failed",
                )
            )
        }

    private fun resolveSession(
        request: DaemonRequest.AccessibilityTree,
        sessionTarget: (String, Int) -> SessionAccessibilityTarget?,
    ): TargetResolution {
        val selected =
            sessionTarget(requireNotNull(request.sessionId), request.windowIndex)
                ?: return TargetResolution.Error(
                    DaemonResponse.Error(
                        DaemonErrorCode.SessionNotFound,
                        "Session '${request.sessionId}' or window ${request.windowIndex} not found",
                    )
                )
        if (isWindows() && selected.nativeHandle != null && selected.nativeHandle != 0L) {
            return TargetResolution.Resolved(
                Target.WindowId(java.lang.Long.toUnsignedString(selected.nativeHandle))
            )
        }
        val title =
            selected.window.title
                ?: return TargetResolution.Error(
                    DaemonResponse.AccessibilityErrorResult(
                        AccessibilityError(
                            AccessibilityErrorCode.UnsupportedTarget,
                            "Window ${request.windowIndex} has no OS title; " +
                                "this native reader cannot align it uniquely",
                        )
                    )
                )
        return TargetResolution.Resolved(Target.WindowTitle(title, selected.pid))
    }

    private fun snapshot(request: DaemonRequest.AccessibilityTree, target: Target): DaemonResponse {
        return try {
            when (
                val response =
                    client.snapshot(
                        AccessibilitySnapshotRequest(
                            target,
                            request.maximumDepth,
                            request.maximumNodeCount,
                            request.timeoutMs,
                        )
                    )
            ) {
                is AccessibilitySnapshotResponse.Success ->
                    DaemonResponse.AccessibilitySnapshotResult(response.snapshot)
                is AccessibilitySnapshotResponse.Failure ->
                    DaemonResponse.AccessibilityErrorResult(response.error)
            }
        } catch (exception: UnsupportedOperationException) {
            DaemonResponse.AccessibilityErrorResult(
                AccessibilityError(
                    AccessibilityErrorCode.UnsupportedTarget,
                    exception.message ?: "Accessibility target is unsupported",
                )
            )
        } catch (exception: java.io.IOException) {
            helperFailure(exception)
        } catch (exception: kotlinx.serialization.SerializationException) {
            helperFailure(exception)
        } catch (exception: IllegalStateException) {
            helperFailure(exception)
        } catch (exception: IllegalArgumentException) {
            helperFailure(exception)
        }
    }

    private fun helperFailure(exception: Exception) =
        DaemonResponse.Error(
            DaemonErrorCode.OperationFailed,
            "Accessibility helper failed: ${exception.message}",
        )

    private fun protocolError(message: String) =
        DaemonResponse.Error(DaemonErrorCode.ProtocolError, message)

    private sealed interface TargetResolution {
        data class Resolved(val target: Target) : TargetResolution

        data class Error(val response: DaemonResponse) : TargetResolution
    }
}

internal data class SessionAccessibilityTarget(
    val pid: Long,
    val window: WindowSummaryDto,
    val nativeHandle: Long? = null,
)

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

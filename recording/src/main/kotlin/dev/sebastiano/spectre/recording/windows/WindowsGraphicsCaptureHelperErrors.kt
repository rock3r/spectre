package dev.sebastiano.spectre.recording.windows

internal fun messageForWindowsGraphicsCaptureHelperExit(
    exit: Int,
    argv: List<String>,
    launchArgv: List<String>? = null,
    stderr: String = "",
): String {
    val shown =
        if (launchArgv != null && launchArgv != argv) {
            "Launch: $launchArgv. Logical argv: $argv"
        } else {
            "Argv: $argv"
        }
    return when (exit) {
        EXIT_ARGUMENTS_REJECTED -> "spectre-window-capture rejected its arguments (exit 2). $shown"
        EXIT_WINDOW_NOT_FOUND ->
            "spectre-window-capture could not find the target window (exit 3), or the " +
                "matching title belongs to a different process. $shown"
        EXIT_CAPTURE_UNSUPPORTED ->
            "spectre-window-capture reported Windows Graphics Capture is unsupported (exit 4). " +
                "Native Windows window capture requires Windows 10 version 1903 or newer. " +
                shown
        EXIT_CAPTURE_FAILED ->
            "spectre-window-capture's Windows Graphics Capture pipeline failed (exit 5). " +
                "${windowsCaptureFailedAdvice(stderr)} $shown"
        else -> "spectre-window-capture exited with code $exit. $shown"
    }
}

/**
 * Exit 5 is any post-parse pipeline failure. A missing Desktop runtime is one of those, and only
 * when the helper stderr says the runtime failed to load. `PlatformNotSupportedException` from
 * `GraphicsCaptureItem.As` is a CsWinRT IInspectable marshalling failure.
 */
internal fun windowsCaptureFailedAdvice(stderr: String): String =
    when {
        isIInspectableMarshallingFailure(stderr) ->
            "WinRT interop threw PlatformNotSupportedException (marshalling as IInspectable is " +
                "not supported in this .NET runtime). The capture helper's GraphicsCaptureItem " +
                "cast failed under this CsWinRT combination."
        isMissingWindowsCaptureRuntime(stderr) ->
            "Check that .NET 8 Desktop Runtime and Windows App Runtime 1.8 are installed."
        else ->
            "Read the helper stderr for the exception. A missing .NET 8 Desktop Runtime or " +
                "Windows App Runtime 1.8 is the cause only when that stderr says the runtime " +
                "failed to load."
    }

internal fun isIInspectableMarshallingFailure(stderr: String): Boolean =
    stderr.contains("PlatformNotSupportedException") ||
        stderr.contains("Marshalling as IInspectable")

internal fun isMissingWindowsCaptureRuntime(stderr: String): Boolean =
    stderr.contains("You must install or update .NET") ||
        stderr.contains("Microsoft.WindowsDesktop.App") ||
        stderr.contains("hostfxr") ||
        stderr.contains("WindowsAppRuntime") ||
        stderr.contains("Bootstrap.Initialize")

internal const val EXIT_ARGUMENTS_REJECTED: Int = 2
internal const val EXIT_WINDOW_NOT_FOUND: Int = 3
internal const val EXIT_CAPTURE_UNSUPPORTED: Int = 4
internal const val EXIT_CAPTURE_FAILED: Int = 5

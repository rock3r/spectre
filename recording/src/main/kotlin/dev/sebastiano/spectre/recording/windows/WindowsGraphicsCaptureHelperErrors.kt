package dev.sebastiano.spectre.recording.windows

internal fun messageForWindowsGraphicsCaptureHelperExit(
    exit: Int,
    argv: List<String>,
    launchArgv: List<String>? = null,
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
                "Check that .NET 8 Desktop Runtime and Windows App Runtime 1.8 are installed. " +
                shown
        else -> "spectre-window-capture exited with code $exit. $shown"
    }
}

internal const val EXIT_ARGUMENTS_REJECTED: Int = 2
internal const val EXIT_WINDOW_NOT_FOUND: Int = 3
internal const val EXIT_CAPTURE_UNSUPPORTED: Int = 4
internal const val EXIT_CAPTURE_FAILED: Int = 5

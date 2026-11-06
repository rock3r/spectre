package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.HelperStillSessions
import dev.sebastiano.spectre.recording.StillHelper

/**
 * Keeps one `spectre-window-capture --mode stills` helper per capture target between stills (#612).
 *
 * Starting the helper and a Windows Graphics Capture session takes a few hundred milliseconds.
 * Visual-idle sampling takes many stills of the same window in a row, so paying that per still made
 * each sample take about 0.4 to 0.7 s, longer than a caret blink. An open session answers a still
 * in one frame copy. The protocol and session handling are shared with macOS in
 * [HelperStillSessions].
 *
 * The Windows helper reports a still as stale when its window no longer matches the selector.
 */
internal class WindowsStillSessions(
    idleCloseMillis: Long = HelperStillSessions.DEFAULT_IDLE_CLOSE_MILLIS,
    readyTimeoutMillis: Long = HelperStillSessions.DEFAULT_READY_TIMEOUT_MILLIS,
    frameTimeoutMillis: Long = HelperStillSessions.DEFAULT_FRAME_TIMEOUT_MILLIS,
) :
    HelperStillSessions(
        helper = WindowsStillHelper,
        idleCloseMillis = idleCloseMillis,
        readyTimeoutMillis = readyTimeoutMillis,
        frameTimeoutMillis = frameTimeoutMillis,
    )

private object WindowsStillHelper : StillHelper {
    override val name: String = "spectre-window-capture"

    override val closeWaitMillis: Long = 1_000

    override fun failureMessage(
        process: Process,
        argv: List<String>,
        exit: Int?,
        message: String,
    ): String {
        val stderr = helperFailureDetail(process)
        val detail =
            if (exit == null) "$message Argv: $argv"
            else
                messageForWindowsGraphicsCaptureHelperExit(
                    exit,
                    argv,
                    helperLaunchArgv(process),
                    stderr,
                )
        return appendHelperStderr(detail, stderr)
    }

    override fun timeoutMessage(process: Process, message: String): String =
        appendHelperStderr(message, helperFailureDetail(process))
}

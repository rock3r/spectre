package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows window-targeted recorder via ffmpeg's `gdigrab` device. Captures the named window's
 * pixels by passing `-i title=<title>` to ffmpeg, so screen coordinates aren't required and the
 * window's movement on the desktop is automatically followed by the encoder.
 *
 * Counterpart to `ScreenCaptureKitRecorder` on macOS: same `WindowRecorder` interface, same
 * `TitledWindow` input, same `RecordingHandle` lifecycle. The high-level [AutoRecorder] router
 * picks between this recorder, SCK, and the region-based [FfmpegRecorder] per call based on host
 * OS + whether a usable title was supplied.
 *
 * Constraints inherited from gdigrab's `title=` form:
 * - The title must be **non-blank** and exactly match the OS-side window title (case-sensitive).
 *   Spectre's [TitledWindow] surface goes through AWT's `Frame.getTitle()` which mirrors what the
 *   OS sees, so any mismatch here is a caller bug rather than an encoder quirk.
 * - The window must be **visible** — gdigrab can't capture minimised windows. Z-order and occlusion
 *   don't matter; the encoder reads from the window's backing store directly.
 * - **Jewel-in-IDE tool windows have no top-level title.** Callers in that scenario should fall
 *   back to region capture; [AutoRecorder] handles that fallback automatically when
 *   [TitledWindow.title] is null/blank.
 *
 * On hosts other than Windows, the spawned ffmpeg subprocess will exit immediately because no
 * gdigrab device exists. [spawnFfmpegRecording]'s startup probe surfaces that as a clear
 * `IllegalStateException` rather than a "success" handle that produces nothing — but the right
 * place to gate this off non-Windows hosts is at [AutoRecorder] construction time. Tests that
 * exercise the lifecycle inject a fake `processFactory` so the gating doesn't matter for them.
 *
 * Construction mirrors [FfmpegRecorder]'s shape: an [ffmpegPath] resolved by default from `PATH`
 * via [FfmpegRecorder.resolveFfmpegPath], and a [processFactory] seam for tests.
 */
class FfmpegWindowRecorder
internal constructor(
    private val ffmpegPath: Path,
    private val processFactory: FfmpegRecorder.ProcessFactory,
) : WindowRecorder {

    constructor(
        ffmpegPath: Path = FfmpegRecorder.resolveFfmpegPath()
    ) : this(ffmpegPath, FfmpegRecorder.SystemProcessFactory)

    init {
        require(Files.isExecutable(ffmpegPath) || ffmpegPath == FfmpegRecorder.PROBE_PATH) {
            "ffmpeg binary not executable at $ffmpegPath"
        }
    }

    override fun start(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val title = window.title
        require(!title.isNullOrBlank()) {
            "FfmpegWindowRecorder requires a non-blank window title; got \"$title\". " +
                "Callers without a title (e.g. Jewel-in-IDE tool windows) should route to " +
                "FfmpegRecorder region capture instead — AutoRecorder handles that fallback."
        }
        val argv = FfmpegCli.gdigrabWindowCapture(ffmpegPath, title, output, options)
        return spawnFfmpegRecording(processFactory, argv, output)
    }
}

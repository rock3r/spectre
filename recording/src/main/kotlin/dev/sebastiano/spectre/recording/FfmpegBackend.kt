package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Picks the platform-appropriate `ffmpeg` argv builder for [FfmpegRecorder].
 *
 * Each backend wraps the native capture device that ffmpeg exposes on its host OS:
 * - [MacOsAvfoundation] — `-f avfoundation` with crop-filter region selection. Requires the macOS
 *   Screen Recording permission.
 * - [WindowsGdigrab] — `-f gdigrab` with input-side `-offset_x`/`-offset_y`/`-video_size` region
 *   selection. No equivalent of macOS's TCC permission gate, but the window must be visible.
 * - [LinuxX11Grab] — `-f x11grab` with input-side `-video_size` and the `<display>+x,y` URL form
 *   for region selection. Reads the `DISPLAY` env var at argv build time, falling back to `:0.0`.
 *   X11 only — Wayland session capture needs PipeWire + xdg-desktop-portal and isn't covered.
 *
 * The backend is selected at [FfmpegRecorder] construction time via [detect], which inspects
 * `os.name` (the same approach the rest of the module uses — see `MacOsRecordingPermissions`).
 * Tests inject a specific backend directly so the produced argv is deterministic regardless of the
 * host OS.
 */
internal sealed interface FfmpegBackend {

    /** Builds the ffmpeg argv for a region capture in this backend's coordinate space. */
    fun buildRegionArgv(
        ffmpegPath: Path,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): List<String>

    object MacOsAvfoundation : FfmpegBackend {
        override fun buildRegionArgv(
            ffmpegPath: Path,
            region: Rectangle,
            output: Path,
            options: RecordingOptions,
        ): List<String> = FfmpegCli.avfoundationRegionCapture(ffmpegPath, region, output, options)
    }

    object WindowsGdigrab : FfmpegBackend {
        override fun buildRegionArgv(
            ffmpegPath: Path,
            region: Rectangle,
            output: Path,
            options: RecordingOptions,
        ): List<String> = FfmpegCli.gdigrabRegionCapture(ffmpegPath, region, output, options)
    }

    object LinuxX11Grab : FfmpegBackend {
        override fun buildRegionArgv(
            ffmpegPath: Path,
            region: Rectangle,
            output: Path,
            options: RecordingOptions,
        ): List<String> {
            // X11's display name is conventionally read from the `DISPLAY` env var. Most desktop
            // sessions set it (`:0`, `:0.0`, `:1`, etc.); over SSH-without-X-forwarding it's
            // unset. Fall back to `:0.0` so a misconfigured environment produces a clear
            // ffmpeg-side "cannot open display" error rather than a confusing argv NPE.
            val display = System.getenv("DISPLAY")?.takeIf { it.isNotBlank() } ?: ":0.0"
            return FfmpegCli.x11grabRegionCapture(ffmpegPath, region, output, options, display)
        }
    }

    companion object {

        /**
         * Resolves the backend for the current OS. macOS → [MacOsAvfoundation]; Windows →
         * [WindowsGdigrab]; Linux → [LinuxX11Grab]. Any other host (BSD, Solaris) throws
         * [UnsupportedOperationException] with a message naming the OS.
         *
         * Linux's selection is X11-only — a host running a Wayland session without XWayland will
         * see x11grab fail at ffmpeg-spawn time. Wayland-native capture (PipeWire +
         * xdg-desktop-portal) is a separate backend tracked as a follow-up.
         */
        fun detect(): FfmpegBackend {
            val osName = System.getProperty("os.name").orEmpty()
            return when {
                osName.lowercase().contains("mac") -> MacOsAvfoundation
                osName.lowercase().contains("windows") -> WindowsGdigrab
                osName.lowercase().contains("linux") -> LinuxX11Grab
                else ->
                    throw UnsupportedOperationException(
                        "FfmpegRecorder has no backend for os.name=\"$osName\". " +
                            "Supported: macOS (avfoundation), Windows (gdigrab), Linux (x11grab)."
                    )
            }
        }
    }
}

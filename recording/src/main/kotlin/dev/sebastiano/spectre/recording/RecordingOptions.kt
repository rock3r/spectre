package dev.sebastiano.spectre.recording

/**
 * Knobs for [Recorder.start]. Defaults are chosen to match the spike-plan recommendations for
 * scenario-level test recordings: 30fps, cursor visible, hardware-accelerated H.264 on macOS where
 * available.
 */
public data class RecordingOptions(
    val frameRate: Int = DEFAULT_FRAME_RATE,
    val captureCursor: Boolean = true,
    /**
     * H.264 encoder name. Leaves room for swapping `h264_videotoolbox` (macOS hw accel) for
     * `libx264` (cross-platform software fallback) without restructuring the API. The ffmpeg
     * backends accept other ffmpeg encoder names when the platform supports them. Linux helper /
     * GStreamer recording currently accepts only `libx264` and `x264enc`; other encoder pipelines
     * need structured parser and property support before they can be represented safely.
     */
    val codec: String = DEFAULT_CODEC,
    /**
     * Index of the display to capture for fixed-region recorders that support multiple displays.
     * Defaults to `0` — the primary display. macOS ScreenCaptureKit region capture orders displays
     * primary first, then by display frame `minX`, then by `minY`; region coordinates use the
     * selected display's ScreenCaptureKit source-rect space. Explicit legacy [FfmpegRecorder]
     * callers still get ffmpeg avfoundation device ordering (`Capture screen N`).
     */
    val screenIndex: Int = DEFAULT_SCREEN_INDEX,
) {

    init {
        require(frameRate > 0) { "frameRate must be positive, was $frameRate" }
        require(codec.isNotBlank()) { "codec must not be blank" }
        require(screenIndex >= 0) { "screenIndex must be non-negative, was $screenIndex" }
    }

    public companion object {

        public const val DEFAULT_FRAME_RATE: Int = 30
        public const val DEFAULT_CODEC: String = "libx264"
        public const val DEFAULT_SCREEN_INDEX: Int = 0
    }
}

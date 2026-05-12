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
     * `libx264` (cross-platform software fallback) without restructuring the API. Other ffmpeg
     * encoders work too if the platform supports them.
     */
    val codec: String = DEFAULT_CODEC,
    /**
     * Index of the macOS display to capture from, used to build ffmpeg's avfoundation device name
     * `Capture screen $screenIndex`. Defaults to `0` — the primary display. Multi-monitor users
     * whose target region lives on a secondary display must set this to the matching index
     * (typically the AWT `GraphicsDevice` index of the screen, but verify by running `ffmpeg -f
     * avfoundation -list_devices true -i ""` once and matching the "Capture screen N" entries
     * against your physical display arrangement). The crop coordinates in the region are
     * interpreted relative to the chosen screen's top-left.
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

package dev.sebastiano.spectre.recording

/**
 * Knobs for [Recorder.start]. Defaults are chosen to match the spike-plan recommendations for
 * scenario-level test recordings: 30fps, cursor visible, hardware-accelerated H.264 on macOS where
 * available.
 */
data class RecordingOptions(
    val frameRate: Int = DEFAULT_FRAME_RATE,
    val captureCursor: Boolean = true,
    /**
     * H.264 encoder name. Leaves room for swapping `h264_videotoolbox` (macOS hw accel) for
     * `libx264` (cross-platform software fallback) without restructuring the API. Other ffmpeg
     * encoders work too if the platform supports them.
     */
    val codec: String = DEFAULT_CODEC,
) {

    init {
        require(frameRate > 0) { "frameRate must be positive, was $frameRate" }
        require(codec.isNotBlank()) { "codec must not be blank" }
    }

    companion object {

        const val DEFAULT_FRAME_RATE: Int = 30
        const val DEFAULT_CODEC: String = "libx264"
    }
}

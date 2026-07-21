package dev.sebastiano.spectre.recording.windows

import java.awt.Rectangle
import java.nio.file.Path

internal enum class WindowsGraphicsCaptureMode(val cliValue: String) {
    Screenshot("screenshot"),
    Recording("recording"),
}

internal enum class WindowsGraphicsCaptureSource(val cliValue: String) {
    Window("window"),
    Region("region"),
}

internal data class WindowsGraphicsCaptureArguments(
    val mode: WindowsGraphicsCaptureMode,
    val source: WindowsGraphicsCaptureSource,
    val title: String? = null,
    val ownerPid: Long? = null,
    val region: Rectangle? = null,
    /**
     * Optional window-relative crop in **device pixels** (WGC capture-item space). Only valid for
     * [WindowsGraphicsCaptureSource.Window]. Fixed for the recording lifetime (#186).
     */
    val crop: Rectangle? = null,
    val output: Path,
    val fps: Int,
    val captureCursor: Boolean,
) {

    init {
        when (source) {
            WindowsGraphicsCaptureSource.Window -> {
                require(!title.isNullOrBlank()) { "title must not be blank" }
                require(ownerPid != null && ownerPid > 0) {
                    "ownerPid must be positive, was $ownerPid"
                }
                crop?.let { c ->
                    require(c.x >= 0 && c.y >= 0) { "crop origin must be non-negative; got $c" }
                    require(c.width > 0 && c.height > 0) {
                        "crop dimensions must be positive; got $c"
                    }
                }
            }
            WindowsGraphicsCaptureSource.Region -> {
                requireNotNull(region) { "region is required for region capture" }
                require(region.width > 0 && region.height > 0) {
                    "region dimensions must be positive, was ${region.width}x${region.height}"
                }
                require(crop == null) { "crop is only valid for window source" }
            }
        }
        require(fps > 0) { "fps must be positive, was $fps" }
    }

    fun toArgv(helperPath: Path): List<String> = buildList {
        add(helperPath.toString())
        add("--mode")
        add(mode.cliValue)
        add("--source")
        add(source.cliValue)
        when (source) {
            WindowsGraphicsCaptureSource.Window -> {
                add("--title")
                add(requireNotNull(title))
                add("--owner-pid")
                add(requireNotNull(ownerPid).toString())
                crop?.let { c ->
                    add("--crop-x")
                    add(c.x.toString())
                    add("--crop-y")
                    add(c.y.toString())
                    add("--crop-width")
                    add(c.width.toString())
                    add("--crop-height")
                    add(c.height.toString())
                }
            }
            WindowsGraphicsCaptureSource.Region -> {
                val requiredRegion = requireNotNull(region)
                add("--x")
                add(requiredRegion.x.toString())
                add("--y")
                add(requiredRegion.y.toString())
                add("--width")
                add(requiredRegion.width.toString())
                add("--height")
                add(requiredRegion.height.toString())
            }
        }
        add("--fps")
        add(fps.toString())
        add("--cursor")
        add(captureCursor.toString())
        add("--output")
        add(output.toString())
    }
}

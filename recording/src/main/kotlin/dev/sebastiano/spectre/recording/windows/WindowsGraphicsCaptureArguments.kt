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
            }
            WindowsGraphicsCaptureSource.Region -> {
                require(region != null) { "region is required for region capture" }
                require(region.width > 0 && region.height > 0) {
                    "region dimensions must be positive, was ${region.width}x${region.height}"
                }
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

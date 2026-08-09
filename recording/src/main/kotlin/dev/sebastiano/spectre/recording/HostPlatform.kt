package dev.sebastiano.spectre.recording

internal object HostPlatform {
    fun isMacOs(): Boolean = osName().contains("mac")

    fun isWindows(): Boolean = osName().contains("windows")

    fun isLinux(): Boolean = osName().contains("linux")

    fun isWayland(): Boolean = isLinux() && !isActiveX11Display() && FfmpegBackend.detectWaylandSession(System::getenv)

    private fun isActiveX11Display(): Boolean {
        val display = System.getenv("DISPLAY")
        if (display.isNullOrBlank()) return false
        return try { java.awt.GraphicsEnvironment.isHeadless() == false } catch (_: Exception) { false } || display.contains(":")
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
}

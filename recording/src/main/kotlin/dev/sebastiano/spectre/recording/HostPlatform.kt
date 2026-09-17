package dev.sebastiano.spectre.recording

internal object HostPlatform {
    fun isMacOs(): Boolean = osName().contains("mac")

    fun isWindows(): Boolean = osName().contains("windows")

    fun isLinux(): Boolean = osName().contains("linux")

    fun isWayland(): Boolean = isLinux() && FfmpegBackend.detectWaylandSession(System::getenv)

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
}

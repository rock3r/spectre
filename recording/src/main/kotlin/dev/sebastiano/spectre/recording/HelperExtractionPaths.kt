package dev.sebastiano.spectre.recording

import java.nio.file.Path

internal object HelperExtractionPaths {
    fun defaultHelperDir(
        helperName: String,
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        getenv: (String) -> String? = System::getenv,
    ): Path =
        stableBaseDir(osName = osName, userHome = userHome, getenv = getenv).resolve(helperName)

    private fun stableBaseDir(osName: String, userHome: String, getenv: (String) -> String?): Path {
        val normalizedOs = osName.lowercase()
        return when {
            normalizedOs.contains("mac") ->
                Path.of(userHome, "Library", "Application Support", "spectre", "helpers")
            normalizedOs.contains("win") -> windowsBaseDir(userHome, getenv)
            else -> linuxBaseDir(userHome, getenv)
        }
    }

    private fun windowsBaseDir(userHome: String, getenv: (String) -> String?): Path {
        val localAppData = getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        val base = localAppData?.let { Path.of(it) } ?: Path.of(userHome, "AppData", "Local")
        return base.resolve("spectre").resolve("helpers")
    }

    private fun linuxBaseDir(userHome: String, getenv: (String) -> String?): Path {
        val xdgCacheHome = getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = xdgCacheHome?.let { Path.of(it) } ?: Path.of(userHome, ".cache")
        return base.resolve("spectre").resolve("helpers")
    }
}

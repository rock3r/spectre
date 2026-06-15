package dev.sebastiano.spectre.recording

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal object HelperExtractionPaths {
    fun defaultHelperDir(
        helperName: String,
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        getenv: (String) -> String? = System::getenv,
    ): Path =
        stableBaseDir(osName = osName, userHome = userHome, getenv = getenv).resolve(helperName)

    fun helperFingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(
            separator = "",
            limit = FINGERPRINT_BYTE_COUNT,
            truncated = "",
        ) {
            (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }

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

    @Synchronized
    fun <T> withExtractionLock(dir: Path, body: () -> T): T {
        Files.createDirectories(dir)
        val lockPath = dir.resolve(LOCK_FILE_NAME)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
            channel ->
            channel.lock().use {
                return body()
            }
        }
    }

    private fun linuxBaseDir(userHome: String, getenv: (String) -> String?): Path {
        val xdgCacheHome = getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = xdgCacheHome?.let { Path.of(it) } ?: Path.of(userHome, ".cache")
        return base.resolve("spectre").resolve("helpers")
    }

    private const val LOCK_FILE_NAME: String = ".extract.lock"
    private const val FINGERPRINT_BYTE_COUNT: Int = 6
    private const val BYTE_MASK: Int = 0xff
    private const val HEX_RADIX: Int = 16
    private const val HEX_BYTE_WIDTH: Int = 2
}

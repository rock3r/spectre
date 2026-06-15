package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.HelperExtractionPaths
import java.io.InputStream
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

internal class WindowsGraphicsCaptureHelperBinaryExtractor(
    private val resourceLocator: () -> InputStream? = { null },
    private val targetDirProvider: () -> Path = ::defaultTargetDir,
    private val getenv: (String) -> String? = System::getenv,
    private val osArch: () -> String = { System.getProperty("os.arch") },
) {

    private var cached: Path? = null

    @Synchronized
    fun extract(): Path {
        getenv(HELPER_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return Path.of(it)
            }
        getenv(LEGACY_SCREENSHOT_HELPER_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return Path.of(it)
            }
        cached?.let {
            return it
        }
        val target = targetDirProvider().resolve(windowsArch(osArch())).resolve(BINARY_NAME)
        val extracted =
            HelperExtractionPaths.withExtractionLock(target.parent) {
                if (!Files.exists(target)) {
                    val source = resourceLocator()
                    if (source != null) {
                        source.use { stream -> Files.copy(stream, target) }
                    } else {
                        copyBundledHelperDirectory(target.parent, osArch())
                        if (!Files.exists(target)) {
                            throw WindowsGraphicsCaptureHelperNotBundledException(
                                "Bundled Windows Graphics Capture helper not found at classpath " +
                                    "resource '${resourcePath(osArch())}'. Add " +
                                    "spectre-recording-windows as a runtime dependency when " +
                                    "using native Windows capture."
                            )
                        }
                    }
                }
                target
            }
        cached = extracted
        return extracted
    }

    private companion object {
        const val BINARY_NAME: String = "spectre-window-capture.exe"
        const val HELPER_ENV: String = "SPECTRE_WINDOWS_GRAPHICS_CAPTURE_HELPER"
        const val LEGACY_SCREENSHOT_HELPER_ENV: String = "SPECTRE_WINDOWS_SCREENSHOT_HELPER"

        @JvmStatic
        fun defaultTargetDir(): Path =
            HelperExtractionPaths.defaultHelperDir("spectre-window-capture")

        fun resourcePath(osArch: String): String =
            "native/windows/${windowsArch(osArch)}/$BINARY_NAME"

        fun windowsArch(osArch: String): String =
            when (osArch.lowercase()) {
                "amd64",
                "x86_64" -> "x64"
                "aarch64",
                "arm64" -> "arm64"
                else ->
                    throw WindowsGraphicsCaptureHelperNotBundledException(
                        "Unsupported Windows architecture '$osArch' for native window capture."
                    )
            }

        fun copyBundledHelperDirectory(targetDir: Path, osArch: String) {
            val prefix = "native/windows/${windowsArch(osArch)}/"
            val loader = WindowsGraphicsCaptureHelperBinaryExtractor::class.java.classLoader
            val exeUrl = loader.getResource("$prefix$BINARY_NAME") ?: return
            when (exeUrl.protocol) {
                "file" -> {
                    val sourceDir = Path.of(exeUrl.toURI()).parent
                    Files.list(sourceDir).use { entries ->
                        entries
                            .filter { Files.isRegularFile(it) }
                            .forEach { source ->
                                Files.copy(
                                    source,
                                    targetDir.resolve(source.fileName.toString()),
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                            }
                    }
                }
                "jar" -> {
                    val connection = exeUrl.openConnection() as JarURLConnection
                    copyJarPrefix(connection.jarFile, prefix, targetDir)
                }
                else ->
                    loader.getResourceAsStream("$prefix$BINARY_NAME")?.use { stream ->
                        Files.copy(
                            stream,
                            targetDir.resolve(BINARY_NAME),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
            }
        }

        private fun copyJarPrefix(jar: JarFile, prefix: String, targetDir: Path) {
            jar.entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) }
                .forEach { entry ->
                    val relative = entry.name.removePrefix(prefix)
                    if (!relative.contains('/')) {
                        jar.getInputStream(entry).use { stream ->
                            Files.copy(
                                stream,
                                targetDir.resolve(relative),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    }
                }
        }
    }
}

internal class WindowsGraphicsCaptureHelperNotBundledException(message: String) :
    IllegalStateException(message)

internal object DefaultWindowsGraphicsCaptureHelperBinaryExtractor {
    val instance: WindowsGraphicsCaptureHelperBinaryExtractor by lazy {
        WindowsGraphicsCaptureHelperBinaryExtractor()
    }
}

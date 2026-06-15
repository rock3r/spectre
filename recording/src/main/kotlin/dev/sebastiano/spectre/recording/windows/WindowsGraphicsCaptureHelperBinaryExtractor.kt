package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.HelperExtractionPaths
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
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
        val overrideBytes = resourceLocator()?.use { it.readBytes() }
        val helperBytes =
            overrideBytes
                ?: bundledHelperBytes(osArch())
                ?: throw WindowsGraphicsCaptureHelperNotBundledException(
                    "Bundled Windows Graphics Capture helper not found at classpath resource " +
                        "'${resourcePath(osArch())}'. Add spectre-recording-windows as a " +
                        "runtime dependency when using native Windows capture."
                )
        val fingerprint =
            overrideBytes?.let { HelperExtractionPaths.helperFingerprint(it) }
                ?: bundledHelperDirectoryFingerprint(osArch())
                ?: HelperExtractionPaths.helperFingerprint(helperBytes)
        val target =
            targetDirProvider()
                .resolve(fingerprint)
                .resolve(windowsArch(osArch()))
                .resolve(BINARY_NAME)
        val extracted =
            HelperExtractionPaths.withExtractionLock(target.parent) {
                if (!Files.exists(target) || !target.readBytesContentEquals(helperBytes)) {
                    Files.write(target, helperBytes)
                }
                if (overrideBytes == null) {
                    copyBundledHelperDirectory(target.parent, osArch())
                }
                target
            }
        cached = extracted
        return extracted
    }

    private fun Path.readBytesContentEquals(expected: ByteArray): Boolean =
        Files.readAllBytes(this).contentEquals(expected)

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

        fun bundledHelperBytes(osArch: String): ByteArray? {
            val loader = WindowsGraphicsCaptureHelperBinaryExtractor::class.java.classLoader
            return loader.getResourceAsStream(resourcePath(osArch))?.use { it.readBytes() }
        }

        fun bundledHelperDirectoryFingerprint(osArch: String): String? {
            val prefix = "native/windows/${windowsArch(osArch)}/"
            val loader = WindowsGraphicsCaptureHelperBinaryExtractor::class.java.classLoader
            val exeUrl = loader.getResource("$prefix$BINARY_NAME") ?: return null
            val bytes = ByteArrayOutputStream()
            when (exeUrl.protocol) {
                "file" -> {
                    val sourceDir = Path.of(exeUrl.toURI()).parent
                    Files.list(sourceDir).use { entries ->
                        entries
                            .filter { Files.isRegularFile(it) }
                            .sorted(Comparator.comparing { it.fileName.toString() })
                            .forEach { source ->
                                bytes.write(source.fileName.toString().toByteArray(Charsets.UTF_8))
                                bytes.write(Files.readAllBytes(source))
                            }
                    }
                }
                "jar" -> {
                    val connection = exeUrl.openConnection() as JarURLConnection
                    connection.jarFile
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(prefix) }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            bytes.write(entry.name.removePrefix(prefix).toByteArray(Charsets.UTF_8))
                            connection.jarFile.getInputStream(entry).use {
                                bytes.write(it.readBytes())
                            }
                        }
                }
                else -> bytes.write(bundledHelperBytes(osArch) ?: return null)
            }
            return HelperExtractionPaths.helperFingerprint(bytes.toByteArray())
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
                                copyIfDifferent(
                                    source,
                                    targetDir.resolve(source.fileName.toString()),
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
                        copyIfDifferent(stream.readBytes(), targetDir.resolve(BINARY_NAME))
                    }
            }
        }

        private fun copyIfDifferent(source: Path, target: Path) {
            copyIfDifferent(Files.readAllBytes(source), target)
        }

        private fun copyIfDifferent(sourceBytes: ByteArray, target: Path) {
            if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(sourceBytes)) {
                Files.write(target, sourceBytes)
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
                            copyIfDifferent(stream.readBytes(), targetDir.resolve(relative))
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

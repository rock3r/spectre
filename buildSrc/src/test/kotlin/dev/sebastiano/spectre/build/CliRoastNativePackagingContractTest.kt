package dev.sebastiano.spectre.build

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Proves the shared Roast native-layout contract used by filtered Construo application jars and
 * [VerifyRoastCliDistribution]: each platform zip keeps only that OS's helper tree.
 */
class CliRoastNativePackagingContractTest {

    @Test
    fun `keep prefix maps Construo target names to OS roots`() {
        assertEquals(
            CliRoastNativePackagingContract.LINUX_PREFIX,
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget("linuxX64"),
        )
        assertEquals(
            CliRoastNativePackagingContract.LINUX_PREFIX,
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget("linuxArm64"),
        )
        assertEquals(
            CliRoastNativePackagingContract.MACOS_PREFIX,
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget("macosArm64"),
        )
        assertEquals(
            CliRoastNativePackagingContract.WINDOWS_PREFIX,
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget("windowsX64"),
        )
    }

    @Test
    fun `unknown Roast target is rejected`() {
        assertThrows<IllegalStateException> {
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget("freebsdX64")
        }
    }

    @Test
    fun `filter keeps bytecode agent-runtime and only one OS native tree`() {
        val keep = CliRoastNativePackagingContract.MACOS_PREFIX
        assertTrue(
            CliRoastNativePackagingContract.shouldIncludeJarEntry(
                "dev/sebastiano/spectre/cli/SpectreCliKt.class",
                keep,
            )
        )
        assertTrue(
            CliRoastNativePackagingContract.shouldIncludeJarEntry("spectre/agent-runtime.jar", keep)
        )
        assertTrue(
            CliRoastNativePackagingContract.shouldIncludeJarEntry(
                "native/macos/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
                keep,
            )
        )
        assertFalse(
            CliRoastNativePackagingContract.shouldIncludeJarEntry(
                "native/windows/x64/spectre-window-capture.exe",
                keep,
            )
        )
        assertFalse(
            CliRoastNativePackagingContract.shouldIncludeJarEntry(
                "native/linux/x86_64/spectre-wayland-helper",
                keep,
            )
        )
    }

    @Test
    fun `foreign native entries are reported for multi-OS payload`() {
        val entries =
            listOf(
                "META-INF/MANIFEST.MF",
                "native/macos/SpectreCaptureHelper.app/Contents/Info.plist",
                "native/windows/x64/spectre-window-capture.exe",
                "native/linux/x86_64/spectre-wayland-helper",
                "spectre/agent-runtime.jar",
            )
        val foreign =
            CliRoastNativePackagingContract.foreignNativeEntries(
                entries,
                CliRoastNativePackagingContract.MACOS_PREFIX,
            )
        assertEquals(
            listOf(
                "native/linux/x86_64/spectre-wayland-helper",
                "native/windows/x64/spectre-window-capture.exe",
            ),
            foreign,
        )
        val errors =
            CliRoastNativePackagingContract.validateEmbeddedJarEntries(
                entries,
                CliRoastNativePackagingContract.MACOS_PREFIX,
            )
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.single().contains("native/macos/"))
        assertTrue(errors.single().contains("foreign"))
    }

    @Test
    fun `mac-only payload validates for macos target`() {
        val entries =
            listOf(
                "dev/sebastiano/spectre/cli/SpectreCliKt.class",
                "native/macos/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
                "spectre/agent-runtime.jar",
            )
        assertEquals(
            emptyList<String>(),
            CliRoastNativePackagingContract.validateEmbeddedJarEntries(
                entries,
                CliRoastNativePackagingContract.MACOS_PREFIX,
            ),
        )
    }

    @Test
    fun `filterJar copies only allowed native roots and preserves agent-runtime`() {
        val input = createTempFile(prefix = "cli-multi-", suffix = ".jar")
        val output = createTempFile(prefix = "cli-mac-", suffix = ".jar")
        try {
            writeJar(
                input.toFile(),
                mapOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    "spectre/agent-runtime.jar" to byteArrayOf(1, 2, 3),
                    "native/macos/helper.bin" to byteArrayOf(9),
                    "native/windows/x64/spectre-window-capture.exe" to ByteArray(1024) { 7 },
                    "native/linux/x86_64/spectre-wayland-helper" to ByteArray(64) { 5 },
                    "dev/sebastiano/spectre/cli/Main.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte()),
                ),
            )
            FilterCliShadowJarNatives.filterJar(
                inputJar = input.toFile(),
                outputJar = output.toFile(),
                keepNativePrefix = CliRoastNativePackagingContract.MACOS_PREFIX,
            )
            JarFile(output.toFile()).use { jar ->
                val names = jar.entries().asSequence().map { it.name }.filter { !it.endsWith("/") }.toSet()
                assertTrue("spectre/agent-runtime.jar" in names)
                assertTrue("native/macos/helper.bin" in names)
                assertTrue("dev/sebastiano/spectre/cli/Main.class" in names)
                assertFalse(names.any { it.startsWith("native/windows/") })
                assertFalse(names.any { it.startsWith("native/linux/") })
                assertEquals(
                    emptyList<String>(),
                    CliRoastNativePackagingContract.validateEmbeddedJarEntries(
                        names,
                        CliRoastNativePackagingContract.MACOS_PREFIX,
                    ),
                )
            }
        } finally {
            input.deleteIfExists()
            output.deleteIfExists()
        }
    }

    private fun writeJar(file: java.io.File, entries: Map<String, ByteArray>) {
        JarOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(JarEntry(name).apply { method = ZipEntry.DEFLATED })
                out.write(bytes)
                out.closeEntry()
            }
        }
    }
}

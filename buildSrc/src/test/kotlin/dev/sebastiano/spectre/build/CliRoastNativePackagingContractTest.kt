package dev.sebastiano.spectre.build

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Proves the shared Roast native-layout contract used by filtered Construo application jars and
 * [VerifyRoastCliDistribution]: each platform zip keeps only that OS's helper tree.
 */
class CliRoastNativePackagingContractTest {

    @ParameterizedTest
    @CsvSource(
        "linuxX64,native/linux/",
        "linuxArm64,native/linux/",
        "macosX64,native/macos/",
        "macosArm64,native/macos/",
        "windowsX64,native/windows/",
    )
    fun `keep prefix maps Construo target names to OS roots`(target: String, expected: String) {
        assertEquals(
            expected,
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget(target),
        )
    }

    @Test
    fun `unknown Roast target is rejected`() {
        assertThrows<IllegalStateException> {
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget("freebsdX64")
        }
    }

    @Test
    fun `malformed keep prefix is rejected`() {
        assertThrows<IllegalStateException> {
            CliRoastNativePackagingContract.requireKnownKeepNativePrefix("native/mac")
        }
        assertThrows<IllegalStateException> {
            CliRoastNativePackagingContract.requireKnownKeepNativePrefix("native/linux")
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

    @ParameterizedTest
    @ValueSource(strings = ["native/linux/", "native/macos/", "native/windows/"])
    fun `filterJar keeps only the requested OS root and preserves agent-runtime`(keep: String) {
        val input = createTempFile(prefix = "cli-multi-", suffix = ".jar")
        val output = createTempFile(prefix = "cli-filtered-", suffix = ".jar")
        try {
            writeJar(
                input.toFile(),
                mapOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                    "spectre/agent-runtime.jar" to byteArrayOf(1, 2, 3),
                    "native/macos/helper.bin" to byteArrayOf(9),
                    "native/windows/x64/spectre-window-capture.exe" to ByteArray(1024) { 7 },
                    "native/linux/x86_64/spectre-wayland-helper" to ByteArray(64) { 5 },
                    "dev/sebastiano/spectre/cli/Main.class" to
                        byteArrayOf(0xCA.toByte(), 0xFE.toByte()),
                ),
            )
            FilterCliShadowJarNatives.filterJar(
                inputJar = input.toFile(),
                outputJar = output.toFile(),
                keepNativePrefix = keep,
            )
            JarFile(output.toFile()).use { jar ->
                val names =
                    jar.entries().asSequence().map { it.name }.filter { !it.endsWith("/") }.toSet()
                assertTrue("spectre/agent-runtime.jar" in names)
                assertTrue("dev/sebastiano/spectre/cli/Main.class" in names)
                for (prefix in CliRoastNativePackagingContract.OS_NATIVE_PREFIXES) {
                    val present = names.any { it.startsWith(prefix) }
                    if (prefix == keep) {
                        assertTrue(present, "expected natives under $keep; names=$names")
                    } else {
                        assertFalse(present, "foreign natives under $prefix; names=$names")
                    }
                }
                assertEquals(
                    emptyList<String>(),
                    CliRoastNativePackagingContract.validateEmbeddedJarEntries(names, keep),
                )
            }
        } finally {
            input.deleteIfExists()
            output.deleteIfExists()
        }
    }

    @Test
    fun `filterJar preserves STORED agent-runtime bytes and CRC`() {
        val input = createTempFile(prefix = "cli-stored-in-", suffix = ".jar")
        val output = createTempFile(prefix = "cli-stored-out-", suffix = ".jar")
        val agentBytes = ByteArray(4096) { i -> (i % 251).toByte() }
        val nativeBytes = ByteArray(512) { 42 }
        try {
            writeJarWithStored(
                input.toFile(),
                stored =
                    mapOf(
                        "spectre/agent-runtime.jar" to agentBytes,
                        "native/windows/x64/spectre-window-capture.exe" to nativeBytes,
                    ),
                deflated =
                    mapOf(
                        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                        "native/macos/helper.bin" to byteArrayOf(1),
                    ),
            )
            FilterCliShadowJarNatives.filterJar(
                inputJar = input.toFile(),
                outputJar = output.toFile(),
                keepNativePrefix = CliRoastNativePackagingContract.WINDOWS_PREFIX,
            )
            JarFile(output.toFile()).use { jar ->
                val agent = jar.getJarEntry("spectre/agent-runtime.jar")
                assertEquals(ZipEntry.STORED, agent.method)
                assertEquals(agentBytes.size.toLong(), agent.size)
                assertEquals(agentBytes.size.toLong(), agent.compressedSize)
                val crc = CRC32().also { it.update(agentBytes) }.value
                assertEquals(crc, agent.crc)
                assertArrayEquals(agentBytes, jar.getInputStream(agent).readBytes())
                val names =
                    jar.entries().asSequence().map { it.name }.filter { !it.endsWith("/") }.toSet()
                assertTrue(names.any { it.startsWith("native/windows/") })
                assertFalse(names.any { it.startsWith("native/macos/") })
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

    private fun writeJarWithStored(
        file: java.io.File,
        stored: Map<String, ByteArray>,
        deflated: Map<String, ByteArray>,
    ) {
        JarOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in stored) {
                val crc = CRC32().also { it.update(bytes) }.value
                val entry =
                    JarEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc
                    }
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
            for ((name, bytes) in deflated) {
                out.putNextEntry(JarEntry(name).apply { method = ZipEntry.DEFLATED })
                out.write(bytes)
                out.closeEntry()
            }
        }
    }
}

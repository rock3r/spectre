package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.HelperExtractionPaths
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsWindowScreenshotterTest {

    @Test
    fun `captureWindow rejects blank titles before helper extraction`() {
        val screenshotter =
            WindowsWindowScreenshotter(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { error("should not extract") },
                        targetDirProvider = { Path.of(".") },
                        osArch = { "amd64" },
                    ),
                processFactory = ScreenshotProcessFactory(),
            )

        assertFailsWith<IllegalArgumentException> {
            screenshotter.captureWindow(ScreenshotWindow(title = ""))
        }
    }

    @Test
    fun `extractor copies bundled helper bytes once`() {
        val targetDir = Files.createTempDirectory("spectre-windows-helper-test")
        var opened = 0
        val extractor =
            WindowsGraphicsCaptureHelperBinaryExtractor(
                resourceLocator = {
                    opened += 1
                    ByteArrayInputStream(byteArrayOf(1, 2, 3))
                },
                targetDirProvider = { targetDir },
                getenv = { null },
                osArch = { "aarch64" },
            )

        val first = extractor.extract()
        val second = extractor.extract()

        assertEquals(first, second)
        assertEquals(1, opened)
        assertEquals("arm64", first.parent.fileName.toString())
        assertEquals(
            HelperExtractionPaths.helperFingerprint(byteArrayOf(1, 2, 3)),
            first.parent.parent.fileName.toString(),
        )
        assertTrue(Files.readAllBytes(first).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `extractor reuses an existing helper without overwriting it`() {
        val targetDir = Files.createTempDirectory("spectre-windows-helper-test")
        val payload = byteArrayOf(9, 8, 7)
        val existing =
            targetDir
                .resolve(HelperExtractionPaths.helperFingerprint(payload))
                .resolve("x64")
                .resolve("spectre-window-capture.exe")
        Files.createDirectories(existing.parent)
        Files.write(existing, payload)
        val extractor =
            WindowsGraphicsCaptureHelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(payload) },
                targetDirProvider = { targetDir },
                getenv = { null },
                osArch = { "amd64" },
            )

        val path = extractor.extract()

        assertEquals(existing, path)
        assertTrue(Files.readAllBytes(path).contentEquals(payload))
    }

    @Test
    fun `extractor rejects unsupported architecture`() {
        val extractor =
            WindowsGraphicsCaptureHelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(byteArrayOf(1)) },
                targetDirProvider = { Path.of(".") },
                getenv = { null },
                osArch = { "riscv64" },
            )

        val error =
            assertFailsWith<WindowsGraphicsCaptureHelperNotBundledException> { extractor.extract() }

        assertContains(error.message.orEmpty(), "Unsupported Windows architecture")
    }
}

private class ScreenshotWindow(title: String?) : TitledWindow {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class ScreenshotProcessFactory : WindowsWindowScreenshotter.ProcessFactory {
    override fun start(argv: List<String>): Process = error("blank titles must not start a helper")
}

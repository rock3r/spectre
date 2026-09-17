package dev.sebastiano.spectre.testing

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScreenshotGoldPathsTest {

    @Test
    fun `gold path is class name os scale and gold png`() {
        val root = Path.of("src", "test", "resources", "spectre-golds")
        val path =
            ScreenshotGoldPaths.goldFile(
                goldRoot = root,
                testClassName = "dev.example.MainWindowTest",
                name = "main-window",
                osKey = "macos",
                scaleKey = "scale-2x2",
            )
        assertEquals(
            root
                .resolve("dev.example.MainWindowTest")
                .resolve("main-window")
                .resolve("macos")
                .resolve("scale-2x2")
                .resolve("gold.png"),
            path,
        )
    }

    @Test
    fun `report path is class method name under spectre-screenshots`() {
        val root = Path.of("build", "reports", "spectre-screenshots")
        val dir =
            ScreenshotGoldPaths.reportDirectory(
                reportsRoot = root,
                testClassName = "dev.example.MainWindowTest",
                testMethodName = "rendersHome",
                name = "main-window",
            )
        assertEquals(
            root
                .resolve("dev.example.MainWindowTest")
                .resolve("rendersHome")
                .resolve("main-window"),
            dir,
        )
    }

    @Test
    fun `hostile name segments are sanitized and cannot escape the gold root`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("spectre-golds")
        val path =
            ScreenshotGoldPaths.goldFile(
                goldRoot = goldRoot,
                testClassName = "a/../../etc",
                name = "foo/../../passwd",
                osKey = "linux-x11",
                scaleKey = "scale-1x1",
            )
        val normalized = path.normalize()
        assertTrue(
            normalized.startsWith(goldRoot.normalize()),
            "gold file escaped root: $normalized",
        )
    }

    @Test
    fun `windows reserved device names are escaped without hashing`() {
        val segment = ScreenshotGoldPaths.sanitizeGoldSegment("NUL")
        assertEquals("NUL_", segment)
        assertFalse(segment.contains(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun `scale key formats integer and fractional densities`() {
        assertEquals("scale-2x2", ScreenshotGoldPaths.scaleKey(2.0, 2.0))
        assertEquals("scale-1x1", ScreenshotGoldPaths.scaleKey(1.0, 1.0))
        assertEquals("scale-1.5x1.5", ScreenshotGoldPaths.scaleKey(1.5, 1.5))
    }

    @Test
    fun `os keys cover macos windows linux-x11 and linux-wayland`() {
        assertEquals(
            "macos",
            ScreenshotGoldPaths.osKey(osName = "Mac OS X", display = null, waylandDisplay = null),
        )
        assertEquals(
            "windows",
            ScreenshotGoldPaths.osKey(osName = "Windows 11", display = null, waylandDisplay = null),
        )
        assertEquals(
            "linux-x11",
            ScreenshotGoldPaths.osKey(
                osName = "Linux",
                display = ":113",
                waylandDisplay = null,
                sessionType = null,
                captureBackend = null,
            ),
        )
        assertEquals(
            "linux-wayland",
            ScreenshotGoldPaths.osKey(
                osName = "Linux",
                display = null,
                waylandDisplay = "wayland-0",
                sessionType = null,
                captureBackend = null,
            ),
        )
        assertEquals(
            "linux-wayland",
            ScreenshotGoldPaths.osKey(
                osName = "Linux",
                display = ":113",
                waylandDisplay = "wayland-0",
                sessionType = null,
                captureBackend = null,
            ),
        )
        assertEquals(
            "linux-x11",
            ScreenshotGoldPaths.osKey(
                osName = "Linux",
                display = ":113",
                waylandDisplay = "wayland-0",
                sessionType = null,
                captureBackend = "xvfb",
            ),
        )
    }
}

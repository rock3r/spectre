package dev.sebastiano.spectre.testing

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScreenshotGoldPathsTest {

    @Test
    fun `gold path is class method name os scale and gold png`() {
        val root = Path.of("src", "test", "resources", "spectre-golds")
        val path =
            ScreenshotGoldPaths.goldFile(
                goldRoot = root,
                testClassName = "dev.example.MainWindowTest",
                testMethodName = "rendersHome",
                name = "main-window",
                osKey = "macos",
                scaleKey = "scale-2x2",
            )
        assertEquals(
            root
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("dev.example.MainWindowTest"))
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("rendersHome"))
                .resolve("main-window")
                .resolve("macos")
                .resolve("scale-2x2")
                .resolve("gold.png"),
            path,
        )
    }

    @Test
    fun `invocation key is inserted between name and os`(@TempDir temp: Path) {
        val path =
            ScreenshotGoldPaths.goldFile(
                goldRoot = temp,
                testClassName = "dev.example.MainWindowTest",
                testMethodName = "rendersEachTheme",
                name = "main-window",
                osKey = "macos",
                scaleKey = "scale-1x1",
                invocationKey = "[1] dark",
            )
        assertEquals(
            temp
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("dev.example.MainWindowTest"))
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("rendersEachTheme"))
                .resolve("main-window")
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("[1] dark"))
                .resolve("macos")
                .resolve("scale-1x1")
                .resolve("gold.png"),
            path,
        )
    }

    @Test
    fun `same name with different invocation keys writes distinct gold files`(@TempDir temp: Path) {
        val dark =
            ScreenshotGoldPaths.goldFile(
                temp,
                "dev.example.MainWindowTest",
                "rendersEachTheme",
                "main-window",
                "macos",
                "scale-1x1",
                "[1] dark",
            )
        val light =
            ScreenshotGoldPaths.goldFile(
                temp,
                "dev.example.MainWindowTest",
                "rendersEachTheme",
                "main-window",
                "macos",
                "scale-1x1",
                "[2] light",
            )
        assertNotEquals(dark, light)
    }

    @Test
    fun `report directory includes the invocation key`(@TempDir temp: Path) {
        val dir =
            ScreenshotGoldPaths.reportDirectory(
                reportsRoot = temp,
                testClassName = "dev.example.MainWindowTest",
                testMethodName = "rendersEachTheme",
                name = "main-window",
                invocationKey = "[1] dark",
            )
        assertEquals(
            temp
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("dev.example.MainWindowTest"))
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("rendersEachTheme"))
                .resolve("main-window")
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("[1] dark")),
            dir,
        )
    }

    @Test
    fun `same screenshot name in two methods writes distinct gold files`(@TempDir temp: Path) {
        val home =
            ScreenshotGoldPaths.goldFile(
                temp,
                "dev.example.MainWindowTest",
                "rendersHome",
                "main-window",
                "macos",
                "scale-1x1",
            )
        val settings =
            ScreenshotGoldPaths.goldFile(
                temp,
                "dev.example.MainWindowTest",
                "rendersSettings",
                "main-window",
                "macos",
                "scale-1x1",
            )
        assertNotEquals(home, settings)
        assertTrue(home.toString().contains("rendersHome"))
        assertTrue(settings.toString().contains("rendersSettings"))
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
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("dev.example.MainWindowTest"))
                .resolve(ScreenshotGoldPaths.sanitizeGoldSegment("rendersHome"))
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
                testMethodName = "renders",
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
    fun `reserved device names stay distinct from the escaped literal`() {
        val reserved = ScreenshotGoldPaths.sanitizeGoldSegment("NUL")
        val literal = ScreenshotGoldPaths.sanitizeGoldSegment("NUL_")
        assertNotEquals(reserved, literal)
        assertTrue(reserved.matches(Regex("NUL__[0-9a-f]{8}")), reserved)
        assertTrue(literal.matches(Regex("NUL__[0-9a-f]{8}")), literal)
    }

    @Test
    fun `lossy sanitization keeps slash names distinct from underscore names`() {
        val slash = ScreenshotGoldPaths.sanitizeGoldSegment("foo/bar")
        val underscore = ScreenshotGoldPaths.sanitizeGoldSegment("foo_bar")
        assertEquals("foo_bar", underscore)
        assertNotEquals(slash, underscore)
        assertTrue(slash.matches(Regex("foo_bar_[0-9a-f]{8}")), slash)
    }

    @Test
    fun `literal fingerprinted segment does not collide with a hashed rewrite`() {
        val hashedSlash = ScreenshotGoldPaths.sanitizeGoldSegment("foo/bar")
        val literalFingerprint = ScreenshotGoldPaths.sanitizeGoldSegment(hashedSlash)
        assertNotEquals(hashedSlash, literalFingerprint)
        assertTrue(hashedSlash.matches(Regex("foo_bar_[0-9a-f]{8}")), hashedSlash)
        assertTrue(
            literalFingerprint.matches(Regex("foo_bar_[0-9a-f]{8}_[0-9a-f]{8}")),
            literalFingerprint,
        )
    }

    @Test
    fun `nested class dollar names stay distinct from underscore names`() {
        val nested = ScreenshotGoldPaths.sanitizeGoldSegment("Outer\$Inner")
        val underscore = ScreenshotGoldPaths.sanitizeGoldSegment("Outer_Inner")
        assertNotEquals(nested, underscore)
        assertTrue(nested.matches(Regex("Outer_Inner_[0-9a-f]{8}")), nested)
        assertTrue(underscore.matches(Regex("Outer_Inner_[0-9a-f]{8}")), underscore)
    }

    @Test
    fun `overlong segments are truncated to a filesystem-safe length`() {
        val longName = "a".repeat(300)
        val longerName = "a".repeat(301)
        val truncated = ScreenshotGoldPaths.sanitizeGoldSegment(longName)
        val other = ScreenshotGoldPaths.sanitizeGoldSegment(longerName)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= 255, truncated)
        assertTrue(other.toByteArray(Charsets.UTF_8).size <= 255, other)
        assertNotEquals(truncated, other)
        assertTrue(truncated.matches(Regex("a+_[0-9a-f]{8}")), truncated)
    }

    @Test
    fun `lossy sanitization suffix is stable for the same raw segment`() {
        assertEquals(
            ScreenshotGoldPaths.sanitizeGoldSegment("foo/bar"),
            ScreenshotGoldPaths.sanitizeGoldSegment("foo/bar"),
        )
    }

    @Test
    fun `lossless lowercase names are not hashed`() {
        assertEquals("main-window", ScreenshotGoldPaths.sanitizeGoldSegment("main-window"))
        assertEquals("macos", ScreenshotGoldPaths.sanitizeGoldSegment("macos"))
        val mixed = ScreenshotGoldPaths.sanitizeGoldSegment("dev.example.HomeTest")
        assertTrue(mixed.matches(Regex("dev\\.example\\.HomeTest_[0-9a-f]{8}")), mixed)
    }

    @Test
    fun `case-only names stay distinct on case-insensitive filesystems`() {
        val lower = ScreenshotGoldPaths.sanitizeGoldSegment("main")
        val title = ScreenshotGoldPaths.sanitizeGoldSegment("Main")
        assertEquals("main", lower)
        assertNotEquals(lower, title)
        assertTrue(title.matches(Regex("Main_[0-9a-f]{8}")), title)
    }

    @Test
    fun `colliding identities resolve to distinct gold files`(@TempDir temp: Path) {
        val slash =
            ScreenshotGoldPaths.goldFile(
                temp,
                "dev.example.HomeTest",
                "renders",
                "foo/bar",
                "macos",
                "scale-1x1",
            )
        val underscore =
            ScreenshotGoldPaths.goldFile(
                temp,
                "dev.example.HomeTest",
                "renders",
                "foo_bar",
                "macos",
                "scale-1x1",
            )
        assertNotEquals(slash, underscore)
        assertTrue(slash.normalize().startsWith(temp.normalize()))
        assertTrue(underscore.normalize().startsWith(temp.normalize()))
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

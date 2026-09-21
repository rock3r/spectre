package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScreenshotGoldAssertTest {

    @Test
    fun `identical gold passes without writing reports`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        val image = solid(2, 2, 0x00FF00)
        writeGold(goldRoot, image)

        assertMatchesGold(
            name = "main-window",
            image = image,
            testClassName = "dev.example.HomeTest",
            testMethodName = "renders",
            goldRoot = goldRoot,
            reportsRoot = reportsRoot,
            osKey = "macos",
            scaleKey = "scale-1x1",
            updateEnabled = false,
        )

        assertFalse(Files.exists(reportsRoot.resolve("dev.example.HomeTest")))
    }

    @Test
    fun `a later match deletes stale report files from a prior mismatch`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        val image = solid(2, 2, 0x00FF00)
        writeGold(goldRoot, image)
        val reportDir =
            ScreenshotGoldPaths.reportDirectory(
                reportsRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
            )
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("actual.png"), "stale-actual")
        Files.writeString(reportDir.resolve("gold.png"), "stale-gold")
        Files.writeString(reportDir.resolve("diff.png"), "stale-diff")

        assertMatchesGold(
            name = "main-window",
            image = image,
            testClassName = "dev.example.HomeTest",
            testMethodName = "renders",
            goldRoot = goldRoot,
            reportsRoot = reportsRoot,
            osKey = "macos",
            scaleKey = "scale-1x1",
            updateEnabled = false,
        )

        assertFalse(Files.exists(reportDir.resolve("actual.png")))
        assertFalse(Files.exists(reportDir.resolve("gold.png")))
        assertFalse(Files.exists(reportDir.resolve("diff.png")))
        assertFalse(Files.exists(reportDir))
    }

    @Test
    fun `mismatch writes actual diff and expected gold under the reports tree`(
        @TempDir temp: Path
    ) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        val expected = solid(2, 2, 0x00FF00)
        writeGold(goldRoot, expected)
        val actual = solid(2, 2, 0xFF0000)

        val error =
            assertFailsWith<AssertionError> {
                assertMatchesGold(
                    name = "main-window",
                    image = actual,
                    testClassName = "dev.example.HomeTest",
                    testMethodName = "renders",
                    goldRoot = goldRoot,
                    reportsRoot = reportsRoot,
                    osKey = "macos",
                    scaleKey = "scale-1x1",
                    updateEnabled = false,
                )
            }

        val reportDir = homeReportDir(reportsRoot)
        assertTrue(Files.isRegularFile(reportDir.resolve("actual.png")), error.message)
        assertTrue(Files.isRegularFile(reportDir.resolve("diff.png")), error.message)
        assertTrue(Files.isRegularFile(reportDir.resolve("gold.png")), error.message)
        assertTrue(error.message!!.contains(reportDir.toString()))
    }

    @Test
    fun `size mismatch fails without writing a gold rewrite`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        writeGold(goldRoot, solid(2, 2, 0x000000))
        val reportDir = homeReportDir(reportsRoot)
        Files.createDirectories(reportDir)
        // Stale diff from a prior equal-size mismatch must not survive a size-mismatch report.
        Files.writeString(reportDir.resolve("diff.png"), "stale")
        val error =
            assertFailsWith<AssertionError> {
                assertMatchesGold(
                    name = "main-window",
                    image = solid(3, 2, 0x000000),
                    testClassName = "dev.example.HomeTest",
                    testMethodName = "renders",
                    goldRoot = goldRoot,
                    reportsRoot = reportsRoot,
                    osKey = "macos",
                    scaleKey = "scale-1x1",
                    updateEnabled = false,
                )
            }
        assertTrue(error.message!!.contains("size"), error.message)
        assertTrue(Files.isRegularFile(reportDir.resolve("actual.png")))
        assertTrue(Files.isRegularFile(reportDir.resolve("gold.png")))
        assertFalse(Files.exists(reportDir.resolve("diff.png")), "stale diff.png must be deleted")
    }

    @Test
    fun `update mode rewrites the current platform scale gold and passes`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
                "linux-x11",
                "scale-1x1",
            )
        Files.createDirectories(goldFile.parent)
        ImageIO.write(solid(2, 2, 0x0000FF), "png", goldFile.toFile())
        val replacement = solid(2, 2, 0x112233)

        assertMatchesGold(
            name = "main-window",
            image = replacement,
            testClassName = "dev.example.HomeTest",
            testMethodName = "renders",
            goldRoot = goldRoot,
            reportsRoot = reportsRoot,
            osKey = "linux-x11",
            scaleKey = "scale-1x1",
            updateEnabled = true,
        )

        val rewritten = ImageIO.read(goldFile.toFile())
        assertEquals(opaque(0x112233), rewritten.getRGB(0, 0))
        assertFalse(Files.exists(reportsRoot.resolve("dev.example.HomeTest")))
    }

    @Test
    fun `failed atomic gold write leaves the previous file intact`(@TempDir temp: Path) {
        val goldFile = temp.resolve("gold.png")
        Files.writeString(goldFile, "previous-gold")
        val error =
            assertFailsWith<IllegalStateException> {
                writeFileAtomically(goldFile) { error("disk full") }
            }
        assertEquals("disk full", error.message)
        assertEquals("previous-gold", Files.readString(goldFile))
        val leftoverTmp =
            Files.list(temp).use { stream ->
                stream.anyMatch { path -> path.fileName.toString().endsWith(".tmp") }
            }
        assertFalse(leftoverTmp)
    }

    @Test
    fun `update mode creates a missing gold file`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
                "macos",
                "scale-1x1",
            )
        assertFalse(Files.exists(goldFile))
        assertMatchesGold(
            name = "main-window",
            image = solid(2, 2, 0xABCDEF),
            testClassName = "dev.example.HomeTest",
            testMethodName = "renders",
            goldRoot = goldRoot,
            reportsRoot = temp.resolve("reports"),
            osKey = "macos",
            scaleKey = "scale-1x1",
            updateEnabled = true,
        )
        assertTrue(Files.isRegularFile(goldFile))
    }

    @Test
    fun `missing gold fails closed when update mode is off`(@TempDir temp: Path) {
        val error =
            assertFailsWith<AssertionError> {
                assertMatchesGold(
                    name = "missing",
                    image = solid(1, 1, 0xFFFFFF),
                    testClassName = "dev.example.HomeTest",
                    testMethodName = "renders",
                    goldRoot = temp.resolve("golds"),
                    reportsRoot = temp.resolve("reports"),
                    osKey = "windows",
                    scaleKey = "scale-1x1",
                    updateEnabled = false,
                )
            }
        assertTrue(error.message!!.contains("gold"), error.message)
    }

    @Test
    fun `missing gold deletes stale report files from a prior mismatch`(@TempDir temp: Path) {
        val reportsRoot = temp.resolve("reports")
        val reportDir = homeReportDir(reportsRoot)
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("actual.png"), "stale-actual")
        Files.writeString(reportDir.resolve("gold.png"), "stale-gold")
        Files.writeString(reportDir.resolve("diff.png"), "stale-diff")

        val error =
            assertFailsWith<AssertionError> {
                assertMatchesGold(
                    name = "main-window",
                    image = solid(1, 1, 0xFFFFFF),
                    testClassName = "dev.example.HomeTest",
                    testMethodName = "renders",
                    goldRoot = temp.resolve("golds"),
                    reportsRoot = reportsRoot,
                    osKey = "macos",
                    scaleKey = "scale-1x1",
                    updateEnabled = false,
                )
            }

        assertTrue(error.message!!.contains("gold"), error.message)
        assertFalse(Files.exists(reportDir.resolve("actual.png")))
        assertFalse(Files.exists(reportDir.resolve("gold.png")))
        assertFalse(Files.exists(reportDir.resolve("diff.png")))
        assertFalse(Files.exists(reportDir))
    }

    @Test
    fun `distinct invocation keys write distinct gold files`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        val first = solid(2, 2, 0x00FF00)
        val second = solid(2, 2, 0x0000FF)
        assertMatchesGold(
            name = "main-window",
            image = first,
            testClassName = "dev.example.HomeTest",
            testMethodName = "rendersEachTheme",
            goldRoot = goldRoot,
            reportsRoot = reportsRoot,
            osKey = "macos",
            scaleKey = "scale-1x1",
            updateEnabled = true,
            invocationKey = "[1] dark",
        )
        assertMatchesGold(
            name = "main-window",
            image = second,
            testClassName = "dev.example.HomeTest",
            testMethodName = "rendersEachTheme",
            goldRoot = goldRoot,
            reportsRoot = reportsRoot,
            osKey = "macos",
            scaleKey = "scale-1x1",
            updateEnabled = true,
            invocationKey = "[2] light",
        )
        val dark =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "rendersEachTheme",
                "main-window",
                "macos",
                "scale-1x1",
                "[1] dark",
            )
        val light =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "rendersEachTheme",
                "main-window",
                "macos",
                "scale-1x1",
                "[2] light",
            )
        assertNotEquals(dark, light)
        assertEquals(opaque(0x00FF00), ImageIO.read(dark.toFile()).getRGB(0, 0))
        assertEquals(opaque(0x0000FF), ImageIO.read(light.toFile()).getRGB(0, 0))
    }

    @Test
    fun `unreadable gold png fails with an assertion error`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
                "macos",
                "scale-1x1",
            )
        Files.createDirectories(goldFile.parent)
        Files.writeString(goldFile, "not a png")
        val error =
            assertFailsWith<AssertionError> {
                assertMatchesGold(
                    name = "main-window",
                    image = solid(1, 1, 0xFFFFFF),
                    testClassName = "dev.example.HomeTest",
                    testMethodName = "renders",
                    goldRoot = goldRoot,
                    reportsRoot = temp.resolve("reports"),
                    osKey = "macos",
                    scaleKey = "scale-1x1",
                    updateEnabled = false,
                )
            }
        assertTrue(error.message!!.contains("unreadable"), error.message)
    }

    @Test
    fun `unreadable gold deletes stale report files from a prior mismatch`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val reportsRoot = temp.resolve("reports")
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
                "macos",
                "scale-1x1",
            )
        Files.createDirectories(goldFile.parent)
        Files.writeString(goldFile, "not a png")
        val reportDir = homeReportDir(reportsRoot)
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("actual.png"), "stale-actual")
        Files.writeString(reportDir.resolve("gold.png"), "stale-gold")
        Files.writeString(reportDir.resolve("diff.png"), "stale-diff")

        val error =
            assertFailsWith<AssertionError> {
                assertMatchesGold(
                    name = "main-window",
                    image = solid(1, 1, 0xFFFFFF),
                    testClassName = "dev.example.HomeTest",
                    testMethodName = "renders",
                    goldRoot = goldRoot,
                    reportsRoot = reportsRoot,
                    osKey = "macos",
                    scaleKey = "scale-1x1",
                    updateEnabled = false,
                )
            }

        assertTrue(error.message!!.contains("unreadable"), error.message)
        assertFalse(Files.exists(reportDir.resolve("actual.png")))
        assertFalse(Files.exists(reportDir.resolve("gold.png")))
        assertFalse(Files.exists(reportDir.resolve("diff.png")))
        assertFalse(Files.exists(reportDir))
    }

    private fun homeReportDir(reportsRoot: Path): Path =
        ScreenshotGoldPaths.reportDirectory(
            reportsRoot,
            "dev.example.HomeTest",
            "renders",
            "main-window",
        )

    private fun writeGold(goldRoot: Path, image: BufferedImage) {
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
                "macos",
                "scale-1x1",
            )
        Files.createDirectories(goldFile.parent)
        ImageIO.write(image, "png", goldFile.toFile())
    }

    private fun solid(width: Int, height: Int, rgb: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val packed = opaque(rgb)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, packed)
            }
        }
        return image
    }

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0x00FFFFFF)
}

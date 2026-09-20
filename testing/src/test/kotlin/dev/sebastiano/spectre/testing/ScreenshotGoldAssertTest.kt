package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

        val reportDir =
            reportsRoot.resolve("dev.example.HomeTest").resolve("renders").resolve("main-window")
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
        val reportDir =
            reportsRoot.resolve("dev.example.HomeTest").resolve("renders").resolve("main-window")
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
    fun `update mode creates a missing gold file`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
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
    fun `TestInfo identity ignores the calling thread when class and method are present`() {
        val info =
            object : org.junit.jupiter.api.TestInfo {
                override fun getDisplayName(): String = "probe()"

                override fun getTags(): Set<String> = emptySet()

                override fun getTestClass(): java.util.Optional<Class<*>> =
                    java.util.Optional.of(ScreenshotGoldAssertTest::class.java)

                override fun getTestMethod(): java.util.Optional<java.lang.reflect.Method> =
                    java.util.Optional.of(
                        ScreenshotGoldAssertTest::class
                            .java
                            .getDeclaredMethod(
                                "missing gold fails closed when update mode is off",
                                Path::class.java,
                            )
                    )
            }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val identity = worker.submit<Pair<String, String>> { identityFromTestInfo(info) }.get()
            assertEquals(ScreenshotGoldAssertTest::class.java.name, identity.first)
            assertEquals("missing gold fails closed when update mode is off", identity.second)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `unreadable gold png fails with an assertion error`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
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

    @RepeatedTest(1)
    fun `RepeatedTest methods are recognized for gold identity`() {
        val (cls, method) = inferTestIdentity()
        assertEquals(ScreenshotGoldAssertTest::class.java.name, cls)
        assertEquals("RepeatedTest methods are recognized for gold identity", method)
    }

    @ParameterizedTest
    @ValueSource(ints = [1])
    fun `ParameterizedTest methods are recognized for gold identity`(value: Int) {
        assertEquals(1, value)
        val (cls, method) = inferTestIdentity()
        assertEquals(ScreenshotGoldAssertTest::class.java.name, cls)
        assertEquals("ParameterizedTest methods are recognized for gold identity", method)
    }

    @ComposedGoldTest
    fun `composed Test meta-annotations are recognized for gold identity`() {
        val (cls, method) = inferTestIdentity()
        assertEquals(ScreenshotGoldAssertTest::class.java.name, cls)
        assertEquals("composed Test meta-annotations are recognized for gold identity", method)
    }

    @Test
    fun `unannotated overload first does not hide an annotated same-name test`() {
        val cls = GoldIdentityOverloadHost::class.java
        val unannotated = cls.getDeclaredMethod("probe")
        val annotated = cls.getDeclaredMethod("probe", Int::class.java)
        assertFalse(unannotated.isJunitTestMethod())
        assertTrue(annotated.isJunitTestMethod())
        assertEquals("probe", resolveJunitTestMethodName(arrayOf(unannotated, annotated), "probe"))
        assertEquals("probe", resolveJunitTestMethodName(arrayOf(annotated, unannotated), "probe"))
        val frame = StackTraceElement(cls.name, "probe", "GoldIdentityOverloadHost.kt", 1)
        assertEquals(cls.name to "probe", testIdentityFromFrame(frame))
    }

    @Test
    fun `scale key uses the matching capture surface instead of the fallback`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val key =
            currentScaleKey(
                image = image,
                surfaces =
                    listOf(
                        CaptureSurfaceScale(
                            pixelWidth = 200,
                            pixelHeight = 100,
                            scaleX = 2.0,
                            scaleY = 2.0,
                        ),
                        CaptureSurfaceScale(
                            pixelWidth = 800,
                            pixelHeight = 600,
                            scaleX = 1.0,
                            scaleY = 1.0,
                        ),
                    ),
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key matches a compose client surface when the decorated window does not`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 110,
                awtHeight = 70,
                insetLeft = 5,
                insetTop = 20,
                insetRight = 5,
                insetBottom = 0,
                scaleX = 2.0,
                scaleY = 2.0,
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key uses a unique window density when the still size does not match`() {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
        val key =
            currentScaleKey(
                image = image,
                surfaces = listOf(CaptureSurfaceScale(200, 100, 2.0, 2.0)),
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key falls back when matching surfaces disagree on density`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val key =
            currentScaleKey(
                image = image,
                surfaces =
                    listOf(
                        CaptureSurfaceScale(200, 100, 2.0, 2.0),
                        CaptureSurfaceScale(200, 100, 1.25, 1.25),
                    ),
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-1x1", key)
    }

    @Test
    fun `explicit scale key is used instead of the inferred capture scale`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val image = solid(2, 2, 0x00AA00)
        assertMatchesGold(
            name = "main-window",
            image = image,
            testClassName = "dev.example.HomeTest",
            testMethodName = "renders",
            goldRoot = goldRoot,
            reportsRoot = temp.resolve("reports"),
            osKey = "macos",
            scaleKey = "scale-2x2",
            updateEnabled = true,
        )
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "main-window",
                "macos",
                "scale-2x2",
            )
        assertTrue(Files.isRegularFile(goldFile))
    }

    private fun writeGold(goldRoot: Path, image: BufferedImage) {
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
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

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
private annotation class ComposedGoldTest

/** Host for overload-identity tests; JUnit must not execute these as specs. */
@Disabled("reflective fixture for gold identity overload resolution")
internal class GoldIdentityOverloadHost {
    @Suppress("unused")
    fun probe() {
        error("unannotated overload")
    }

    @Test
    fun probe(ignored: Int) {
        error("annotated overload is only used reflectively")
    }
}

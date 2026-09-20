package dev.sebastiano.spectre.testing

import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import javax.swing.RootPaneContainer
import kotlin.math.roundToInt
import org.junit.jupiter.api.TestInfo

/**
 * Opt-in gold PNG assertion for in-process JUnit tests. Never runs automatically; call it from a
 * test after capturing a still (prefer [dev.sebastiano.spectre.core.ComposeAutomator.screenshot] on
 * a window when recording helpers are present).
 *
 * Golds live under `src/test/resources/spectre-golds/<class>/<name>/<os>/scale-<sx>x<sy>/gold.png`.
 * Mismatches write `actual.png` and a copy of `gold.png` under
 * `build/reports/spectre-screenshots/<class>/<method>/<name>/`. `diff.png` is written when
 * dimensions match; a stale `diff.png` is deleted when there is no diff. A later match deletes
 * leftover report PNGs from a prior mismatch.
 *
 * This overload infers the test from the **calling thread** stack. Inside [runSpectreTest], use the
 * [TestInfo] overload instead — the body runs on a worker dispatcher that has no JUnit frame.
 *
 * [scaleKey] defaults to the captured window's display scale when a showing AWT window's outer,
 * client, or content-pane size matches the still (or every showing window shares one density).
 * Otherwise it uses the primary/default screen transform. Pass an explicit key (from
 * [ScreenshotGoldPaths.scaleKey]) when several densities are visible and inference is ambiguous.
 */
public fun assertMatchesGold(
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
    scaleKey: String = currentScaleKey(image),
) {
    val (testClassName, testMethodName) = inferTestIdentity()
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = testClassName,
        testMethodName = testMethodName,
        tolerance = tolerance,
        scaleKey = scaleKey,
    )
}

/**
 * JUnit 5 overload. Safe inside [runSpectreTest] because identity comes from [testInfo], not the
 * worker stack. [scaleKey] follows the same capture-display default as the name-only overload.
 */
public fun assertMatchesGold(
    testInfo: TestInfo,
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
    scaleKey: String = currentScaleKey(image),
) {
    val (testClassName, testMethodName) = identityFromTestInfo(testInfo)
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = testClassName,
        testMethodName = testMethodName,
        tolerance = tolerance,
        scaleKey = scaleKey,
    )
}

internal fun assertMatchesGold(
    name: String,
    image: BufferedImage,
    testClassName: String,
    testMethodName: String,
    goldRoot: Path = ScreenshotGoldPaths.defaultGoldRoot(),
    reportsRoot: Path = ScreenshotGoldPaths.defaultReportsRoot(),
    osKey: String = ScreenshotGoldPaths.osKey(),
    scaleKey: String = currentScaleKey(image),
    updateEnabled: Boolean = ScreenshotUpdateMode.isEnabled(),
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
) {
    val goldFile = ScreenshotGoldPaths.goldFile(goldRoot, testClassName, name, osKey, scaleKey)
    val reportDir =
        ScreenshotGoldPaths.reportDirectory(reportsRoot, testClassName, testMethodName, name)
    if (updateEnabled) {
        Files.createDirectories(goldFile.parent)
        check(ImageIO.write(image, "png", goldFile.toFile())) {
            "Failed to write PNG gold: $goldFile"
        }
        deleteReportDirectory(reportDir)
        return
    }
    if (!Files.isRegularFile(goldFile)) {
        throw AssertionError(
            "Screenshot gold missing: $goldFile. Re-run with " +
                "${ScreenshotUpdateMode.ENV}=true or -P${ScreenshotUpdateMode.GRADLE_PROPERTY}=true " +
                "to write the current platform/scale gold."
        )
    }
    val expected =
        ImageIO.read(goldFile.toFile())
            ?: throw AssertionError("Screenshot gold is unreadable: $goldFile")
    val result = ScreenshotComparer.compare(expected, image, tolerance)
    if (result.matches) {
        deleteReportDirectory(reportDir)
        return
    }
    Files.createDirectories(reportDir)
    check(ImageIO.write(image, "png", reportDir.resolve("actual.png").toFile())) {
        "Failed to write actual.png under $reportDir"
    }
    val diffFile = reportDir.resolve("diff.png")
    val diffImage = result.diff
    if (diffImage != null) {
        check(ImageIO.write(diffImage, "png", diffFile.toFile())) {
            "Failed to write diff.png under $reportDir"
        }
    } else {
        // Size mismatches omit a diff; clear any leftover diff.png from a prior equal-size run.
        Files.deleteIfExists(diffFile)
    }
    Files.copy(goldFile, reportDir.resolve("gold.png"), StandardCopyOption.REPLACE_EXISTING)
    val reason =
        if (result.sizeMismatch) {
            "size mismatch: expected ${result.expectedWidth}x${result.expectedHeight}, " +
                "actual ${result.actualWidth}x${result.actualHeight}"
        } else {
            "${result.differingPixels} differing pixel(s)"
        }
    throw AssertionError(
        "Screenshot '$name' did not match gold ($reason). " +
            "Wrote diagnostic PNGs under $reportDir"
    )
}

private fun deleteReportDirectory(reportDir: Path) {
    if (!Files.exists(reportDir)) return
    val paths = Files.walk(reportDir).use { it.toList() }
    paths.sortedByDescending { it.nameCount }.forEach { Files.deleteIfExists(it) }
}

internal data class CaptureSurfaceScale(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val scaleX: Double,
    val scaleY: Double,
)

internal fun currentScaleKey(
    image: BufferedImage? = null,
    surfaces: List<CaptureSurfaceScale> = liveCaptureSurfaces(),
    fallbackScaleX: Double = fallbackDisplayScale().first,
    fallbackScaleY: Double = fallbackDisplayScale().second,
): String {
    val matchingScales =
        if (image == null) {
            emptyList()
        } else {
            surfaces
                .filter { it.pixelWidth == image.width && it.pixelHeight == image.height }
                .map { it.scaleX to it.scaleY }
                .distinct()
        }
    val uniqueSurfaceScales = surfaces.map { it.scaleX to it.scaleY }.distinct()
    val (scaleX, scaleY) =
        matchingScales.singleOrNull()
            ?: uniqueSurfaceScales.singleOrNull()
            ?: (fallbackScaleX to fallbackScaleY)
    return ScreenshotGoldPaths.scaleKey(scaleX, scaleY)
}

internal fun liveCaptureSurfaces(): List<CaptureSurfaceScale> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    return Window.getWindows()
        .filter { it.isShowing }
        .flatMap { window ->
            val configuration = window.graphicsConfiguration ?: return@flatMap emptyList()
            val content =
                (window as? RootPaneContainer)?.contentPane?.takeIf {
                    it.width > 0 && it.height > 0
                }
            captureSurfacesForBounds(
                awtWidth = window.width,
                awtHeight = window.height,
                insetLeft = window.insets.left,
                insetTop = window.insets.top,
                insetRight = window.insets.right,
                insetBottom = window.insets.bottom,
                scaleX = configuration.defaultTransform.scaleX,
                scaleY = configuration.defaultTransform.scaleY,
                contentWidth = content?.width,
                contentHeight = content?.height,
            )
        }
}

internal fun captureSurfacesForBounds(
    awtWidth: Int,
    awtHeight: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int,
    scaleX: Double,
    scaleY: Double,
    contentWidth: Int? = null,
    contentHeight: Int? = null,
): List<CaptureSurfaceScale> {
    val clientWidth = (awtWidth - insetLeft - insetRight).coerceAtLeast(0)
    val clientHeight = (awtHeight - insetTop - insetBottom).coerceAtLeast(0)
    return buildList {
            add(awtWidth to awtHeight)
            add(clientWidth to clientHeight)
            if (contentWidth != null && contentHeight != null) {
                add(contentWidth to contentHeight)
            }
        }
        .distinct()
        .filter { (width, height) -> width > 0 && height > 0 }
        .map { (width, height) ->
            CaptureSurfaceScale(
                pixelWidth = (width * scaleX).roundToInt(),
                pixelHeight = (height * scaleY).roundToInt(),
                scaleX = scaleX,
                scaleY = scaleY,
            )
        }
}

internal fun fallbackDisplayScale(): Pair<Double, Double> {
    if (GraphicsEnvironment.isHeadless()) return 1.0 to 1.0
    val transform =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
    return transform.scaleX to transform.scaleY
}

internal fun identityFromTestInfo(testInfo: TestInfo): Pair<String, String> {
    val fromClass = testInfo.testClass.map { it.name }.orElse(null)
    val fromMethod = testInfo.testMethod.map { it.name }.orElse(null)
    if (fromClass != null && fromMethod != null) return fromClass to fromMethod
    val inferred = inferTestIdentity()
    return (fromClass ?: inferred.first) to (fromMethod ?: inferred.second)
}

internal fun inferTestIdentity(): Pair<String, String> =
    Thread.currentThread().stackTrace.firstNotNullOfOrNull(::testIdentityFromFrame)
        ?: error(
            "assertMatchesGold could not infer the calling test method; pass TestInfo explicitly"
        )

internal fun testIdentityFromFrame(frame: StackTraceElement): Pair<String, String>? {
    val className = frame.className
    // Only skip frames from this file (ScreenshotGoldKt), not other types whose names
    // happen to start with ScreenshotGold (e.g. ScreenshotGoldAssertTest).
    if (
        className == "dev.sebastiano.spectre.testing.ScreenshotGoldKt" ||
            className.startsWith("dev.sebastiano.spectre.testing.ScreenshotGoldKt$")
    ) {
        return null
    }
    val cls = runCatching { Class.forName(className) }.getOrNull() ?: return null
    val methodName =
        resolveJunitTestMethodName(cls.declaredMethods, frame.methodName) ?: return null
    return className to methodName
}

internal fun resolveJunitTestMethodName(
    methods: Array<java.lang.reflect.Method>,
    methodName: String,
): String? = methods.firstOrNull { it.name == methodName && it.isJunitTestMethod() }?.name

/**
 * True for JUnit 4 `@Test`, JUnit 5 `@Test` / `@TestTemplate` / `@TestFactory`, the platform
 * `@Testable` meta-annotation, and composed annotations that meta-annotate those (including
 * `@ParameterizedTest` and `@RepeatedTest`).
 */
internal fun java.lang.reflect.Method.isJunitTestMethod(): Boolean = annotations.any {
    isJunitTestAnnotation(it.annotationClass.java)
}

private fun isJunitTestAnnotation(
    annotationType: Class<out Annotation>,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    val name = annotationType.name
    if (!visited.add(name)) return false
    when (name) {
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.api.TestTemplate",
        "org.junit.jupiter.api.TestFactory",
        "org.junit.platform.commons.annotation.Testable",
        "org.junit.Test" -> return true
    }
    if (name.startsWith("java.") || name.startsWith("kotlin.")) return false
    return annotationType.annotations.any { meta ->
        isJunitTestAnnotation(meta.annotationClass.java, visited)
    }
}

package dev.sebastiano.spectre.testing

import androidx.compose.ui.awt.ComposePanel
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Opt-in gold PNG assertion for in-process JUnit tests. Never runs automatically; call it from a
 * test after capturing a still (prefer [dev.sebastiano.spectre.core.ComposeAutomator.screenshot] on
 * a window when recording helpers are present).
 *
 * Golds live under
 * `src/test/resources/spectre-golds/<class>/<method>/<name>[/<invocation>]/<os>/scale-<sx>x<sy>/gold.png`.
 * Mismatches write `actual.png` and a copy of `gold.png` under
 * `build/reports/spectre-screenshots/<class>/<method>/<name>[/<invocation>]/`. `diff.png` is
 * written when dimensions match; a stale `diff.png` is deleted when there is no diff. A later
 * match, update write, missing-gold failure, or unreadable gold deletes leftover report PNGs from a
 * prior mismatch.
 *
 * This name-only overload lives on `ScreenshotGoldKt` and does not mention JUnit 5 `TestInfo`, so
 * JUnit 4-only Java callers can resolve it without `junit-jupiter-api`. It infers the test from the
 * **calling thread** stack. Inside [runSpectreTest], use the JUnit 5 facade
 * (`ScreenshotGoldJunit5`) instead — the body runs on a worker dispatcher that has no JUnit frame.
 *
 * `@ParameterizedTest`, `@RepeatedTest`, JUnit 5 `@ParameterizedClass` / `@ClassTemplate`, and
 * JUnit 4 `@RunWith(Parameterized)` invocations that share a [name] must pass [invocationKey] here,
 * or use the TestInfo facade when its display name is unique per invocation (`[1] …` or `repetition
 * N of M`). The facade only trusts method-level patterns that include `{index}` /
 * `{currentRepetition}` (or JUnit's default, which includes `{index}`). Identity-hash display text
 * (`Foo@4a12bc`) keeps only that index. Constant custom display names, argument-only patterns such
 * as `[{0}] theme`, and ordinary `@Test` methods on a parameterized class still require
 * [invocationKey] — `TestInfo.displayName` is the method name, not the class invocation.
 *
 * [scaleKey] defaults to the captured window's display scale when a showing AWT window's outer,
 * client, content-pane, or showing embedded ComposePanel size matches the still (or every showing
 * window shares one density). Cropped stills use the same edge rounding as
 * [dev.sebastiano.spectre.core.capture.screenRectToImageRect], so a fractional-DPI client or panel
 * crop does not miss by one pixel. Hidden panels are ignored. Surface geometry is read on the EDT.
 * Otherwise it uses the primary/default screen transform. Pass an explicit key (from
 * [ScreenshotGoldPaths.scaleKey]) when several densities are visible and inference is ambiguous.
 */
@JvmOverloads
public fun assertMatchesGold(
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
    scaleKey: String = currentScaleKey(image),
    invocationKey: String? = null,
) {
    val (testClassName, testMethodName) = inferTestIdentity()
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = testClassName,
        testMethodName = testMethodName,
        tolerance = tolerance,
        scaleKey = scaleKey,
        invocationKey = resolveInvocationKey(testClassName, testMethodName, invocationKey),
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
    invocationKey: String? = null,
) {
    val goldFile =
        ScreenshotGoldPaths.goldFile(
            goldRoot,
            testClassName,
            testMethodName,
            name,
            osKey,
            scaleKey,
            invocationKey,
        )
    val reportDir =
        ScreenshotGoldPaths.reportDirectory(
            reportsRoot,
            testClassName,
            testMethodName,
            name,
            invocationKey,
        )
    if (updateEnabled) {
        Files.createDirectories(goldFile.parent)
        check(ImageIO.write(image, "png", goldFile.toFile())) {
            "Failed to write PNG gold: $goldFile"
        }
        deleteReportDirectory(reportDir)
        return
    }
    if (!Files.isRegularFile(goldFile)) {
        deleteReportDirectory(reportDir)
        throw AssertionError(
            "Screenshot gold missing: $goldFile. Re-run with " +
                "${ScreenshotUpdateMode.ENV}=true or " +
                "-P${ScreenshotUpdateMode.GRADLE_PROPERTY}=true " +
                "to write the current platform/scale gold."
        )
    }
    val expected =
        try {
            ImageIO.read(goldFile.toFile())
        } catch (e: IOException) {
            deleteReportDirectory(reportDir)
            throw AssertionError("Screenshot gold is unreadable: $goldFile", e)
        }
    if (expected == null) {
        deleteReportDirectory(reportDir)
        throw AssertionError("Screenshot gold is unreadable: $goldFile")
    }
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

internal fun <T> readAwtSnapshotOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val box = arrayOfNulls<Any>(1)
    try {
        SwingUtilities.invokeAndWait { box[0] = block() }
    } catch (thrown: InvocationTargetException) {
        throw thrown.cause ?: thrown
    }
    @Suppress("UNCHECKED_CAST")
    return box[0] as T
}

internal fun liveCaptureSurfaces(): List<CaptureSurfaceScale> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    return readAwtSnapshotOnEdt {
        Window.getWindows()
            .filter { it.isShowing }
            .flatMap { window ->
                val configuration = window.graphicsConfiguration ?: return@flatMap emptyList()
                val content =
                    (window as? RootPaneContainer)?.contentPane?.takeIf {
                        it.width > 0 && it.height > 0
                    }
                val contentOrigin = content?.let { pane ->
                    SwingUtilities.convertPoint(pane, 0, 0, window)
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
                    contentX = contentOrigin?.x ?: window.insets.left,
                    contentY = contentOrigin?.y ?: window.insets.top,
                    extraAwtRegions = composePanelAwtRegions(window),
                )
            }
    }
}

internal data class CaptureAwtRegion(val x: Int, val y: Int, val width: Int, val height: Int)

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
    contentX: Int = insetLeft,
    contentY: Int = insetTop,
    extraAwtSizes: List<Pair<Int, Int>> = emptyList(),
    extraAwtRegions: List<CaptureAwtRegion> = emptyList(),
): List<CaptureSurfaceScale> {
    val clientWidth = (awtWidth - insetLeft - insetRight).coerceAtLeast(0)
    val clientHeight = (awtHeight - insetTop - insetBottom).coerceAtLeast(0)
    val regions = buildList {
        add(CaptureAwtRegion(0, 0, awtWidth, awtHeight))
        add(CaptureAwtRegion(insetLeft, insetTop, clientWidth, clientHeight))
        if (contentWidth != null && contentHeight != null) {
            add(CaptureAwtRegion(contentX, contentY, contentWidth, contentHeight))
        }
        extraAwtSizes.forEach { (width, height) -> add(CaptureAwtRegion(0, 0, width, height)) }
        addAll(extraAwtRegions)
    }
    return regions
        .distinct()
        .filter { region -> region.width > 0 && region.height > 0 }
        .map { region ->
            val (pixelWidth, pixelHeight) = capturePixelSize(region, scaleX, scaleY)
            CaptureSurfaceScale(
                pixelWidth = pixelWidth,
                pixelHeight = pixelHeight,
                scaleX = scaleX,
                scaleY = scaleY,
            )
        }
}

/**
 * Device-pixel size of an AWT region inside a window-scoped capture. Matches
 * [dev.sebastiano.spectre.core.capture.screenRectToImageRect]: transform both edges, then derive
 * size, because rounding origin and size independently can miss a fractional-DPI crop by one pixel.
 */
internal fun capturePixelSize(
    region: CaptureAwtRegion,
    scaleX: Double,
    scaleY: Double,
): Pair<Int, Int> {
    val left = (region.x * scaleX).roundToInt()
    val top = (region.y * scaleY).roundToInt()
    val right = ((region.x + region.width) * scaleX).roundToInt()
    val bottom = ((region.y + region.height) * scaleY).roundToInt()
    return (right - left).coerceAtLeast(0) to (bottom - top).coerceAtLeast(0)
}

internal fun composePanelAwtRegions(root: Component): List<CaptureAwtRegion> {
    val regions = mutableListOf<CaptureAwtRegion>()
    fun walk(component: Component) {
        if (
            component is ComposePanel &&
                component.isShowing &&
                component.width > 0 &&
                component.height > 0
        ) {
            val origin = SwingUtilities.convertPoint(component, 0, 0, root)
            regions += CaptureAwtRegion(origin.x, origin.y, component.width, component.height)
        }
        if (component is Container) {
            component.components.forEach(::walk)
        }
    }
    walk(root)
    return regions
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

@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.AlphaComposite
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max

/** Result of capturing multiple physical windows as independent images and one composite. */
public data class CompositeScreenshot(
    public val windows: List<WindowScreenshotResult>,
    /** Null when any requested window could not be captured. */
    public val composite: CompositedWindowImage?,
)

/** Capture outcome for one requested physical window. */
public sealed interface WindowScreenshotResult {
    public val windowIndex: Int
    public val title: String?
    public val boundsOnScreen: Rectangle
    /** Back-to-front order used to paint the composite. */
    public val captureOrder: Int

    public data class Success(
        override val windowIndex: Int,
        public val surfaceId: String,
        override val title: String?,
        override val boundsOnScreen: Rectangle,
        public val densityScaleX: Double,
        public val densityScaleY: Double,
        override val captureOrder: Int,
        public val image: BufferedImage,
    ) : WindowScreenshotResult

    public data class Failure(
        override val windowIndex: Int,
        override val title: String?,
        override val boundsOnScreen: Rectangle,
        override val captureOrder: Int,
        public val message: String,
    ) : WindowScreenshotResult
}

/** A transparent composite and the screen-space rectangle represented by its pixels. */
public data class CompositedWindowImage(
    public val image: BufferedImage,
    public val boundsOnScreen: Rectangle,
    public val densityScale: Double,
)

internal data class CompositeWindowTarget(
    val windowIndex: Int,
    val trackedWindow: TrackedWindow,
    val windowBounds: Rectangle,
    val windowInsets: Insets,
)

internal fun captureCompositeWindows(
    targets: List<CompositeWindowTarget>,
    backend: ScreenCaptureBackend,
): CompositeScreenshot {
    val outcomes = targets.mapIndexed { captureOrder, target ->
        try {
            val captured =
                backend.captureWindow(
                    target.trackedWindow,
                    target.windowBounds,
                    target.windowInsets,
                )
            check(captured.boundsOnScreen.width > 0 && captured.boundsOnScreen.height > 0) {
                "Captured window has invalid screen bounds: ${captured.boundsOnScreen}"
            }
            WindowScreenshotResult.Success(
                windowIndex = target.windowIndex,
                surfaceId = target.trackedWindow.surfaceId,
                title = target.trackedWindow.windowTitle,
                boundsOnScreen = captured.boundsOnScreen,
                densityScaleX = captured.image.width.toDouble() / captured.boundsOnScreen.width,
                densityScaleY = captured.image.height.toDouble() / captured.boundsOnScreen.height,
                captureOrder = captureOrder,
                image = captured.image,
            )
        } catch (error: IllegalArgumentException) {
            target.failure(captureOrder, error)
        } catch (error: IllegalStateException) {
            target.failure(captureOrder, error)
        } catch (error: UnsupportedOperationException) {
            target.failure(captureOrder, error)
        } catch (error: SecurityException) {
            target.failure(captureOrder, error)
        }
    }
    val successes = outcomes.filterIsInstance<WindowScreenshotResult.Success>()
    return CompositeScreenshot(
        windows = outcomes,
        composite = if (successes.size == outcomes.size) compositeWindowLayers(successes) else null,
    )
}

private fun CompositeWindowTarget.failure(
    captureOrder: Int,
    error: RuntimeException,
): WindowScreenshotResult.Failure =
    WindowScreenshotResult.Failure(
        windowIndex = windowIndex,
        title = trackedWindow.windowTitle,
        boundsOnScreen = windowBounds,
        captureOrder = captureOrder,
        message = error.message ?: error.javaClass.simpleName,
    )

internal fun compositeWindowLayers(
    layers: List<WindowScreenshotResult.Success>
): CompositedWindowImage {
    require(layers.isNotEmpty()) { "At least one captured window is required" }
    val minX = layers.minOf { it.boundsOnScreen.x }
    val minY = layers.minOf { it.boundsOnScreen.y }
    val maxX = layers.maxOf { it.boundsOnScreen.x.toLong() + it.boundsOnScreen.width }
    val maxY = layers.maxOf { it.boundsOnScreen.y.toLong() + it.boundsOnScreen.height }
    val width = maxX - minX
    val height = maxY - minY
    require(width in 1..Int.MAX_VALUE.toLong() && height in 1..Int.MAX_VALUE.toLong()) {
        "Requested window bounds cannot be represented by an AWT Rectangle"
    }
    val union = Rectangle(minX, minY, width.toInt(), height.toInt())
    val density = layers.maxOf { layer -> max(layer.densityScaleX, layer.densityScaleY) }
    require(density.isFinite() && density > 0.0) { "Window capture density must be positive" }
    val imageWidth = ceil(width * density).toLong()
    val imageHeight = ceil(height * density).toLong()
    require(imageWidth in 1..Int.MAX_VALUE.toLong() && imageHeight in 1..Int.MAX_VALUE.toLong()) {
        "Composite image dimensions are too large: ${imageWidth}x$imageHeight"
    }
    val image = BufferedImage(imageWidth.toInt(), imageHeight.toInt(), BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().use { graphics ->
        graphics.composite = AlphaComposite.SrcOver
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        for (layer in layers.sortedBy { it.captureOrder }) {
            val left = ((layer.boundsOnScreen.x - minX) * density).toInt()
            val top = ((layer.boundsOnScreen.y - minY) * density).toInt()
            val right =
                ceil(
                        (layer.boundsOnScreen.x.toLong() + layer.boundsOnScreen.width - minX) *
                            density
                    )
                    .toInt()
            val bottom =
                ceil(
                        (layer.boundsOnScreen.y.toLong() + layer.boundsOnScreen.height - minY) *
                            density
                    )
                    .toInt()
            graphics.drawImage(
                layer.image,
                left,
                top,
                right,
                bottom,
                0,
                0,
                layer.image.width,
                layer.image.height,
                null,
            )
        }
    }
    return CompositedWindowImage(image, union, density)
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

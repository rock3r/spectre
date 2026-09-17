package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Pixel-match tolerances for [ScreenshotComparer] / gold PNG assertions.
 *
 * Defaults are strict: every channel must match exactly, and no differing pixels are allowed.
 * Callers loosen [maxChannelDelta] for font AA, and/or a differing-pixel [maxDifferingPixels] count
 * and/or [maxDifferingPixelFraction] budget. When both budgets are set, a mismatch must satisfy
 * both. Dimensions must still be equal regardless of tolerance.
 */
public data class ScreenshotTolerance(
    public val maxChannelDelta: Int = 0,
    public val maxDifferingPixels: Int? = null,
    public val maxDifferingPixelFraction: Double? = null,
) {
    init {
        require(maxChannelDelta >= 0) { "maxChannelDelta must be >= 0, was $maxChannelDelta" }
        require(maxDifferingPixels == null || maxDifferingPixels >= 0) {
            "maxDifferingPixels must be null or >= 0, was $maxDifferingPixels"
        }
        require(maxDifferingPixelFraction == null || maxDifferingPixelFraction in 0.0..1.0) {
            "maxDifferingPixelFraction must be null or in 0.0..1.0, was $maxDifferingPixelFraction"
        }
    }

    public companion object {
        /** Channel delta 0 and no differing-pixel budget (zero pixels may differ). */
        public val Strict: ScreenshotTolerance = ScreenshotTolerance()
    }
}

/** Outcome of comparing two same-type raster images. */
public data class ScreenshotCompareResult(
    public val matches: Boolean,
    public val expectedWidth: Int,
    public val expectedHeight: Int,
    public val actualWidth: Int,
    public val actualHeight: Int,
    public val differingPixels: Int,
    public val sizeMismatch: Boolean,
    public val diff: BufferedImage?,
)

/**
 * Pure pixel comparison for screenshot golds. Independent of JUnit, filesystem golds, and live
 * capture so tests can use synthetic [BufferedImage]s.
 */
public object ScreenshotComparer {

    /** ARGB magenta used on [ScreenshotCompareResult.diff] for pixels outside tolerance. */
    public const val DIFF_HIGHLIGHT_RGB: Int = 0xFFFF00FF.toInt()

    private const val MATCH_RGB: Int = 0xFF000000.toInt()
    private const val ALPHA_SHIFT: Int = 24
    private const val RED_SHIFT: Int = 16
    private const val GREEN_SHIFT: Int = 8
    private const val CHANNEL_MASK: Int = 0xFF

    public fun compare(
        expected: BufferedImage,
        actual: BufferedImage,
        tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
    ): ScreenshotCompareResult {
        val sizeMismatch = expected.width != actual.width || expected.height != actual.height
        if (sizeMismatch) {
            return ScreenshotCompareResult(
                matches = false,
                expectedWidth = expected.width,
                expectedHeight = expected.height,
                actualWidth = actual.width,
                actualHeight = actual.height,
                differingPixels = 0,
                sizeMismatch = true,
                diff = null,
            )
        }
        val width = expected.width
        val height = expected.height
        val total = width * height
        val diff = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var differing = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedPixel = expected.getRGB(x, y)
                val actualPixel = actual.getRGB(x, y)
                val mismatch = channelDelta(expectedPixel, actualPixel) > tolerance.maxChannelDelta
                if (mismatch) {
                    differing += 1
                    diff.setRGB(x, y, DIFF_HIGHLIGHT_RGB)
                } else {
                    diff.setRGB(x, y, MATCH_RGB)
                }
            }
        }
        val matches = withinDifferingBudget(differing, total, tolerance)
        return ScreenshotCompareResult(
            matches = matches,
            expectedWidth = width,
            expectedHeight = height,
            actualWidth = width,
            actualHeight = height,
            differingPixels = differing,
            sizeMismatch = false,
            diff = diff,
        )
    }

    private fun withinDifferingBudget(
        differing: Int,
        total: Int,
        tolerance: ScreenshotTolerance,
    ): Boolean {
        val countLimit = tolerance.maxDifferingPixels
        val fractionLimit = tolerance.maxDifferingPixelFraction
        if (countLimit == null && fractionLimit == null) return differing == 0
        val countOk = countLimit == null || differing <= countLimit
        val fractionOk =
            fractionLimit == null ||
                (total == 0 && differing == 0) ||
                (total > 0 && differing.toDouble() / total.toDouble() <= fractionLimit)
        return countOk && fractionOk
    }

    private fun channelDelta(expected: Int, actual: Int): Int {
        val alpha = abs(channel(expected, ALPHA_SHIFT) - channel(actual, ALPHA_SHIFT))
        val red = abs(channel(expected, RED_SHIFT) - channel(actual, RED_SHIFT))
        val green = abs(channel(expected, GREEN_SHIFT) - channel(actual, GREEN_SHIFT))
        val blue = abs(channel(expected, 0) - channel(actual, 0))
        return maxOf(alpha, red, green, blue)
    }

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and CHANNEL_MASK
}

package dev.sebastiano.spectre.testing

import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import org.junit.jupiter.api.TestInfo

/**
 * Opt-in gold PNG assertion for in-process JUnit tests. Never runs automatically; call it from a
 * test after capturing a still (prefer [dev.sebastiano.spectre.core.ComposeAutomator.screenshot] on
 * a window when recording helpers are present).
 *
 * Golds live under `src/test/resources/spectre-golds/<class>/<name>/<os>/scale-<sx>x<sy>/gold.png`.
 * Mismatches write `actual.png` and a copy of `gold.png` under
 * `build/reports/spectre-screenshots/<class>/<method>/<name>/`. `diff.png` is written when
 * dimensions match.
 *
 * This overload infers the test from the **calling thread** stack. Inside [runSpectreTest], use the
 * [TestInfo] overload instead — the body runs on a worker dispatcher that has no JUnit frame.
 */
public fun assertMatchesGold(
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
) {
    val (testClassName, testMethodName) = inferTestIdentity()
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = testClassName,
        testMethodName = testMethodName,
        tolerance = tolerance,
    )
}

/**
 * JUnit 5 overload. Safe inside [runSpectreTest] because identity comes from [testInfo], not the
 * worker stack.
 */
public fun assertMatchesGold(
    testInfo: TestInfo,
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
) {
    val (testClassName, testMethodName) = identityFromTestInfo(testInfo)
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = testClassName,
        testMethodName = testMethodName,
        tolerance = tolerance,
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
    scaleKey: String = currentScaleKey(),
    updateEnabled: Boolean = ScreenshotUpdateMode.isEnabled(),
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
) {
    val goldFile = ScreenshotGoldPaths.goldFile(goldRoot, testClassName, name, osKey, scaleKey)
    if (updateEnabled) {
        Files.createDirectories(goldFile.parent)
        check(ImageIO.write(image, "png", goldFile.toFile())) {
            "Failed to write PNG gold: $goldFile"
        }
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
    if (result.matches) return
    val reportDir =
        ScreenshotGoldPaths.reportDirectory(reportsRoot, testClassName, testMethodName, name)
    Files.createDirectories(reportDir)
    check(ImageIO.write(image, "png", reportDir.resolve("actual.png").toFile())) {
        "Failed to write actual.png under $reportDir"
    }
    result.diff?.let {
        check(ImageIO.write(it, "png", reportDir.resolve("diff.png").toFile())) {
            "Failed to write diff.png under $reportDir"
        }
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

internal fun currentScaleKey(): String {
    if (GraphicsEnvironment.isHeadless()) return ScreenshotGoldPaths.scaleKey(1.0, 1.0)
    val transform =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
    return ScreenshotGoldPaths.scaleKey(transform.scaleX, transform.scaleY)
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

private fun testIdentityFromFrame(frame: StackTraceElement): Pair<String, String>? {
    val className = frame.className
    if (className.startsWith("dev.sebastiano.spectre.testing.ScreenshotGold")) return null
    val cls = runCatching { Class.forName(className) }.getOrNull() ?: return null
    val method = cls.declaredMethods.firstOrNull { it.name == frame.methodName } ?: return null
    val isJunit5 =
        method.annotations.any { it.annotationClass.java.name == "org.junit.jupiter.api.Test" }
    val isJunit4 = method.annotations.any { it.annotationClass.java.name == "org.junit.Test" }
    return if (isJunit5 || isJunit4) className to frame.methodName else null
}

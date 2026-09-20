@file:JvmName("ScreenshotGoldJunit5")

package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import org.junit.jupiter.api.TestInfo

/**
 * JUnit 5 facade for [assertMatchesGold]. Isolated from the name-only `ScreenshotGoldKt` overloads
 * so JUnit 4-only Java callers do not need `junit-jupiter-api` on the compile classpath.
 *
 * Safe inside [runSpectreTest] because identity comes from [testInfo], not the worker stack.
 * [scaleKey] follows the same capture-display default as the name-only overload.
 *
 * `@ParameterizedTest` and `@RepeatedTest` invocations are keyed from [testInfo]'s display name
 * unless [invocationKey] is supplied, so two invocations cannot share or overwrite one gold.
 */
public fun assertMatchesGold(
    testInfo: TestInfo,
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
    scaleKey: String = currentScaleKey(image),
    invocationKey: String? = null,
) {
    val (testClassName, testMethodName) = identityFromTestInfo(testInfo)
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = testClassName,
        testMethodName = testMethodName,
        tolerance = tolerance,
        scaleKey = scaleKey,
        invocationKey =
            resolveInvocationKey(
                testClassName,
                testMethodName,
                invocationKey ?: invocationKeyFromTestInfo(testInfo),
            ),
    )
}

internal fun identityFromTestInfo(testInfo: TestInfo): Pair<String, String> {
    val fromClass = testInfo.testClass.map { it.name }.orElse(null)
    val fromMethod = testInfo.testMethod.map { it.name }.orElse(null)
    if (fromClass != null && fromMethod != null) return fromClass to fromMethod
    val inferred = inferTestIdentity()
    return (fromClass ?: inferred.first) to (fromMethod ?: inferred.second)
}

internal fun invocationKeyFromTestInfo(testInfo: TestInfo): String? {
    val method = testInfo.testMethod.orElse(null) ?: return null
    if (!method.isJunitTestTemplate()) return null
    return testInfo.displayName.trim().takeIf { it.isNotEmpty() }
}

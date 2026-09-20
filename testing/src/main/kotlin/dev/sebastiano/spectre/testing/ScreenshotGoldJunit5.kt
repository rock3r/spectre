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
 * only when that name is unique per invocation (JUnit's `[index] …` or `repetition N of M`).
 * Constant custom names such as `@ParameterizedTest(name = "theme")` require [invocationKey].
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
    val display = testInfo.displayName.trim().takeIf { it.isNotEmpty() } ?: return null
    return display.takeIf(::looksUniqueInvocationLabel)
}

internal fun looksUniqueInvocationLabel(display: String): Boolean {
    // JUnit parameterized default: "[{index}] {argumentsWithNames}" → "[1] dark"
    if (PARAMETERIZED_INVOCATION_LABEL.containsMatchIn(display)) return true
    // RepeatedTest default / short form: "repetition 1 of 2" or "repetition 1"
    if (REPEATED_INVOCATION_LABEL.containsMatchIn(display)) return true
    return false
}

private val PARAMETERIZED_INVOCATION_LABEL = Regex("""^\[\d+]""")
private val REPEATED_INVOCATION_LABEL =
    Regex("""(?:^| )repetition \d+(?: of \d+)?$""", RegexOption.IGNORE_CASE)

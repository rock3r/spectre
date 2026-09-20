@file:JvmName("ScreenshotGoldJunit5")

package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.ParameterizedTest

/**
 * JUnit 5 facade for [assertMatchesGold]. Isolated from the name-only `ScreenshotGoldKt` overloads
 * so JUnit 4-only Java callers do not need `junit-jupiter-api` on the compile classpath.
 *
 * Safe inside [runSpectreTest] because identity comes from [testInfo], not the worker stack.
 * [scaleKey] follows the same capture-display default as the name-only overload.
 *
 * `@ParameterizedTest` and `@RepeatedTest` invocations are keyed from [testInfo]'s display name
 * only when the annotation `name` pattern varies per invocation *and* the resolved display looks
 * unique (`[index] …` or `repetition N of M`). JUnit 5.14's omitted/`{default_display_name}`
 * pattern counts as varying. Constant custom names — including ones that merely resemble JUnit's
 * default, such as `@ParameterizedTest(name = "[1] theme")` — require [invocationKey].
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
    val fromMethod = testInfo.testMethod.map(::junitMethodIdentity).orElse(null)
    if (fromClass != null && fromMethod != null) return fromClass to fromMethod
    val inferred = inferTestIdentity()
    return (fromClass ?: inferred.first) to (fromMethod ?: inferred.second)
}

internal fun invocationKeyFromTestInfo(testInfo: TestInfo): String? {
    val method = testInfo.testMethod.orElse(null) ?: return null
    if (!method.isJunitTestTemplate()) return null
    // A constant custom name can still look like JUnit's default (`[1] theme`).
    // Only trust the display name when the annotation pattern itself varies.
    if (!method.hasVaryingInvocationNamePattern()) return null
    val display = testInfo.displayName.trim().takeIf { it.isNotEmpty() } ?: return null
    return display.takeIf(::looksUniqueInvocationLabel)
}

internal fun java.lang.reflect.Method.hasVaryingInvocationNamePattern(): Boolean {
    val parameterized = getAnnotation(ParameterizedTest::class.java)
    if (parameterized != null) {
        return patternVariesPerInvocation(parameterized.name)
    }
    val repeated = getAnnotation(RepeatedTest::class.java)
    if (repeated != null) {
        return patternVariesPerInvocation(repeated.name)
    }
    return annotations.any { annotationHasVaryingInvocationPattern(it) }
}

/** Blank or `{default_display_name}` is JUnit's default varying pattern. */
private fun patternVariesPerInvocation(pattern: String): Boolean =
    pattern.isBlank() || VARYING_INVOCATION_PLACEHOLDER.containsMatchIn(pattern)

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
private val VARYING_INVOCATION_PLACEHOLDER =
    Regex(
        """\{index\}|\{arguments(?:WithNames)?\}|""" +
            """\{argumentSetName(?:OrArgumentsWithNames)?\}|""" +
            """\{currentRepetition\}|\{default_display_name\}|\{\d+\}"""
    )

private fun annotationHasVaryingInvocationPattern(
    annotation: Annotation,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    val type = annotation.annotationClass.java
    if (!visited.add(type.name)) return false
    when (type.name) {
        "org.junit.jupiter.params.ParameterizedTest",
        "org.junit.jupiter.api.RepeatedTest" -> {
            val pattern = runCatching {
                type.getMethod("name").invoke(annotation) as String
            }
                .getOrNull()
            return pattern == null || patternVariesPerInvocation(pattern)
        }
    }
    if (type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return false
    return type.annotations.any { meta -> annotationHasVaryingInvocationPattern(meta, visited) }
}

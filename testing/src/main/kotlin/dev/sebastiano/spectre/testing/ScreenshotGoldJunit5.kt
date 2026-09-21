@file:JvmName("ScreenshotGoldJunit5")

package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.TestInfo

/**
 * JUnit 5 facade for [assertMatchesGold]. Isolated from the name-only `ScreenshotGoldKt` overloads
 * so JUnit 4-only Java callers do not need `junit-jupiter-api` on the compile classpath.
 *
 * Safe inside [runSpectreTest] because identity comes from [testInfo], not the worker stack.
 * [scaleKey] follows the same capture-display default as the name-only overload.
 *
 * `@ParameterizedTest` and `@RepeatedTest` invocations are keyed from [testInfo]'s display name
 * only when the annotation `name` pattern includes a true invocation index (`{index}` or
 * `{currentRepetition}`) *and* the resolved display looks unique (`[index] …` or `repetition N of
 * M`). JUnit 5.14's omitted/`{default_display_name}` pattern counts because it includes `{index}`.
 * Argument placeholders such as `{0}` or `{arguments}` are not unique when values repeat. Display
 * names that include `Any.toString()` identity-hash text (`Foo@4a12bc`) keep only the stable
 * `[index]` or `repetition N of M` token. Constant custom names — including ones that merely
 * resemble JUnit's default, such as `@ParameterizedTest(name = "[1] theme")` — require
 * [invocationKey]. Ordinary `@Test` methods on a JUnit 5 `@ParameterizedClass` or `@ClassTemplate`
 * also require [invocationKey]: [testInfo] exposes the method display name, not the class
 * invocation. A method-level `@ParameterizedTest` / `@RepeatedTest` inside a class template still
 * requires [invocationKey] — each outer argument set repeats the same `[1]` / `repetition 1` index.
 */
public fun assertMatchesGold(
    testInfo: TestInfo,
    name: String,
    image: BufferedImage,
    tolerance: ScreenshotTolerance = ScreenshotTolerance.Strict,
    scaleKey: String = currentScaleKey(image),
    invocationKey: String? = null,
) {
    val identity = identityFromTestInfo(testInfo)
    assertMatchesGold(
        name = name,
        image = image,
        testClassName = identity.testClassName,
        testMethodName = identity.testMethodName,
        tolerance = tolerance,
        scaleKey = scaleKey,
        invocationKey =
            resolveInvocationKey(
                identity.testClassName,
                identity.testMethodName,
                invocationKey,
                derivedInvocationKey = invocationKeyFromTestInfo(testInfo),
                testClass = identity.testClass,
            ),
    )
}

internal fun identityFromTestInfo(testInfo: TestInfo): GoldTestIdentity {
    val fromClass = testInfo.testClass.orElse(null)
    val fromMethod = testInfo.testMethod.map(::junitMethodIdentity).orElse(null)
    if (fromClass != null && fromMethod != null) return GoldTestIdentity(fromClass, fromMethod)
    val inferred = inferTestIdentity()
    return GoldTestIdentity(
        testClass = fromClass ?: inferred.testClass,
        testMethodName = fromMethod ?: inferred.testMethodName,
    )
}

internal fun invocationKeyFromTestInfo(testInfo: TestInfo): String? {
    val method = testInfo.testMethod.orElse(null) ?: return null
    if (!method.isJunitTestTemplate()) return null
    // A constant custom name can still look like JUnit's default (`[1] theme`).
    // Only trust the display name when the annotation pattern itself varies.
    if (!method.hasVaryingInvocationNamePattern()) return null
    val display = testInfo.displayName.trim().takeIf { it.isNotEmpty() } ?: return null
    return stableInvocationKeyFromDisplay(display)
}

internal fun stableInvocationKeyFromDisplay(display: String): String? {
    if (!looksUniqueInvocationLabel(display)) return null
    if (!containsIdentityHashText(display)) return display
    return parameterizedInvocationIndex(display) ?: repeatedInvocationIndex(display)
}

internal fun java.lang.reflect.Method.hasVaryingInvocationNamePattern(): Boolean {
    // RepeatedTest lives in junit-jupiter-api. Do not resolve ParameterizedTest::class —
    // that class is in junit-jupiter-params, which is optional at runtime.
    val repeated = getAnnotation(RepeatedTest::class.java)
    if (repeated != null) return repeatedPatternVariesPerInvocation(repeated.name)
    return annotations.any { annotationHasVaryingInvocationPattern(it) }
}

/**
 * Blank is JUnit's RepeatedTest default (`repetition {currentRepetition} of {totalRepetitions}`).
 * `{index}` and `{default_display_name}` are parameterized tokens and stay literal on RepeatedTest.
 */
private fun repeatedPatternVariesPerInvocation(pattern: String): Boolean =
    pattern.isBlank() || REPEATED_UNIQUE_PLACEHOLDER.containsMatchIn(pattern)

/**
 * Blank or `{default_display_name}` is JUnit's parameterized default, which includes `{index}`.
 * Argument placeholders (`{0}`, `{arguments}`) can repeat and are not unique.
 */
private fun parameterizedPatternVariesPerInvocation(pattern: String): Boolean =
    pattern.isBlank() || PARAMETERIZED_UNIQUE_PLACEHOLDER.containsMatchIn(pattern)

internal fun looksUniqueInvocationLabel(display: String): Boolean {
    // JUnit parameterized default: "[{index}] {argumentsWithNames}" → "[1] dark"
    if (PARAMETERIZED_INVOCATION_LABEL.containsMatchIn(display)) return true
    // RepeatedTest default / short form: "repetition 1 of 2" or "repetition 1"
    if (REPEATED_INVOCATION_LABEL.containsMatchIn(display)) return true
    return false
}

internal fun containsIdentityHashText(display: String): Boolean =
    IDENTITY_HASH_TEXT.containsMatchIn(display)

private fun parameterizedInvocationIndex(display: String): String? =
    PARAMETERIZED_INVOCATION_LABEL.find(display)?.value

private fun repeatedInvocationIndex(display: String): String? =
    REPEATED_INVOCATION_LABEL.find(display)?.value?.trim()

private val PARAMETERIZED_INVOCATION_LABEL = Regex("""^\[\d+]""")
private val REPEATED_INVOCATION_LABEL =
    Regex("""(?:^| )repetition \d+(?: of \d+)?$""", RegexOption.IGNORE_CASE)
// Object.toString() / default Any.toString(): ClassName@hex, arrays, nested types.
// Detect `@<hex>` without assuming the last class-name character is `\w`, `.`, or
// `$` — backtick Kotlin names may end in punctuation (`Theme-@4a12bc`). `[^\s@]+`
// also covers Unicode names (`Δοκιμή@4a12bc`, `[LΔοκιμή;@7f31245a`). `(?U)` makes
// `\s` Unicode-aware; Kotlin RegexOption has no UNICODE_CHARACTER_CLASS.
private val IDENTITY_HASH_TEXT =
    Regex("""(?U)(?:[^\s@]+|\[+[ZBCSIJFD]|\[+L[^\s@]+;)@[0-9a-fA-F]+""")
private val REPEATED_UNIQUE_PLACEHOLDER = Regex("""\{currentRepetition\}""")
private val PARAMETERIZED_UNIQUE_PLACEHOLDER = Regex("""\{index\}|\{default_display_name\}""")

private fun annotationHasVaryingInvocationPattern(
    annotation: Annotation,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    val type = annotation.annotationClass.java
    if (!visited.add(type.name)) return false
    when (type.name) {
        "org.junit.jupiter.api.RepeatedTest" -> {
            val pattern = runCatching {
                type.getMethod("name").invoke(annotation) as String
            }
                .getOrNull()
            return pattern == null || repeatedPatternVariesPerInvocation(pattern)
        }
        "org.junit.jupiter.params.ParameterizedTest" -> {
            val pattern = runCatching {
                type.getMethod("name").invoke(annotation) as String
            }
                .getOrNull()
            return pattern == null || parameterizedPatternVariesPerInvocation(pattern)
        }
    }
    if (type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return false
    return type.annotations.any { meta -> annotationHasVaryingInvocationPattern(meta, visited) }
}

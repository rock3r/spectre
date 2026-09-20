package dev.sebastiano.spectre.testing

internal fun inferTestIdentity(): Pair<String, String> =
    Thread.currentThread().stackTrace.firstNotNullOfOrNull(::testIdentityFromFrame)
        ?: error(
            "assertMatchesGold could not infer the calling test method; pass TestInfo explicitly"
        )

internal fun testIdentityFromFrame(frame: StackTraceElement): Pair<String, String>? {
    val className = frame.className
    // Skip gold facade frames, not other types whose names happen to start with
    // ScreenshotGold (e.g. ScreenshotGoldAssertTest).
    if (isGoldFacadeClass(className)) {
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

internal fun java.lang.reflect.Method.isJunitTestTemplate(): Boolean = annotations.any {
    isJunitTemplateAnnotation(it.annotationClass.java)
}

internal fun resolveInvocationKey(
    testClassName: String,
    testMethodName: String,
    invocationKey: String?,
): String? {
    val trimmed = invocationKey?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmed != null) return trimmed
    if (isJunitTestTemplateMethod(testClassName, testMethodName)) {
        error(
            "assertMatchesGold on a parameterized or repeated test requires invocationKey " +
                "or the TestInfo facade so each invocation gets its own gold"
        )
    }
    return null
}

private fun isJunitTestTemplateMethod(testClassName: String, testMethodName: String): Boolean {
    val cls = runCatching { Class.forName(testClassName) }.getOrNull() ?: return false
    return cls.declaredMethods.any { it.name == testMethodName && it.isJunitTestTemplate() }
}

private fun isGoldFacadeClass(className: String): Boolean = GOLD_FACADE_CLASSES.any { facade ->
    className == facade || className.startsWith(facade + "$")
}

private val GOLD_FACADE_CLASSES =
    setOf(
        "dev.sebastiano.spectre.testing.ScreenshotGoldKt",
        "dev.sebastiano.spectre.testing.ScreenshotGoldJunit5",
        "dev.sebastiano.spectre.testing.ScreenshotGoldIdentityKt",
    )

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

private fun isJunitTemplateAnnotation(
    annotationType: Class<out Annotation>,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    val name = annotationType.name
    if (!visited.add(name)) return false
    when (name) {
        "org.junit.jupiter.api.TestTemplate",
        "org.junit.jupiter.api.TestFactory" -> return true
    }
    if (name.startsWith("java.") || name.startsWith("kotlin.")) return false
    return annotationType.annotations.any { meta ->
        isJunitTemplateAnnotation(meta.annotationClass.java, visited)
    }
}

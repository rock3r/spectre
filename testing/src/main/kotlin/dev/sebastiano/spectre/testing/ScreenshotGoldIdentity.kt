package dev.sebastiano.spectre.testing

import java.lang.StackWalker
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal fun inferTestIdentity(): Pair<String, String> =
    StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
        frames.iterator().asSequence().firstNotNullOfOrNull(::testIdentityFromWalkerFrame)
    }
        ?: error(
            "assertMatchesGold could not infer the calling test method; pass TestInfo explicitly"
        )

internal fun testIdentityFromWalkerFrame(frame: StackWalker.StackFrame): Pair<String, String>? {
    val cls = frame.declaringClass
    if (isGoldFacadeClass(cls.name)) return null
    val method =
        resolveJunitTestMethod(cls.declaredMethods, frame.methodName, frame.methodType)
            ?: return null
    return identityFromResolved(cls, method)
}

internal fun testIdentityFromFrame(frame: StackTraceElement): Pair<String, String>? {
    val className = frame.className
    // Skip gold facade frames, not other types whose names happen to start with
    // ScreenshotGold (e.g. ScreenshotGoldAssertTest).
    if (isGoldFacadeClass(className)) return null
    val cls = runCatching { Class.forName(className) }.getOrNull() ?: return null
    val method = resolveJunitTestMethod(cls.declaredMethods, frame.methodName) ?: return null
    return identityFromResolved(cls, method)
}

internal fun junitMethodIdentity(method: Method): String {
    if (method.parameterCount == 0) return method.name
    return buildString {
        append(method.name)
        append('(')
        append(method.parameterTypes.joinToString(",") { it.name })
        append(')')
    }
}

internal fun resolveJunitTestMethod(
    methods: Array<Method>,
    methodName: String,
    methodType: MethodType? = null,
): Method? {
    val candidates = methods.filter { it.name == methodName && it.isJunitTestMethod() }
    if (candidates.isEmpty()) return null
    if (methodType != null) {
        return candidates.firstOrNull { methodMatchesType(it, methodType) }
    }
    return candidates.singleOrNull()
}

/**
 * True for JUnit 4 `@Test`, JUnit 5 `@Test` / `@TestTemplate` / `@TestFactory`, the platform
 * `@Testable` meta-annotation, and composed annotations that meta-annotate those (including
 * `@ParameterizedTest` and `@RepeatedTest`).
 */
internal fun Method.isJunitTestMethod(): Boolean = annotations.any {
    isJunitTestAnnotation(it.annotationClass.java)
}

internal fun Method.isJunitTestTemplate(): Boolean = annotations.any {
    isJunitTemplateAnnotation(it.annotationClass.java)
}

internal fun resolveInvocationKey(
    testClassName: String,
    testMethodName: String,
    invocationKey: String?,
): String? {
    val present = invocationKey?.takeIf { it.isNotBlank() }
    if (present != null) return present
    if (requiresExplicitInvocationKey(testClassName, testMethodName)) {
        error(
            "assertMatchesGold on a parameterized or repeated test requires invocationKey " +
                "or a unique TestInfo display name so each invocation gets its own gold"
        )
    }
    return null
}

private fun identityFromResolved(cls: Class<*>, method: Method): Pair<String, String> {
    // Non-final concrete hosts can be subclassed; the stack frame names the declaring
    // class, so two children would share a gold without TestInfo.
    if (cls.isInterface || !Modifier.isFinal(cls.modifiers)) {
        error(
            "assertMatchesGold cannot infer the concrete test class from ${cls.name}; " +
                "pass TestInfo so inherited tests key golds by the executing class"
        )
    }
    return cls.name to junitMethodIdentity(method)
}

private fun requiresExplicitInvocationKey(testClassName: String, testMethodName: String): Boolean {
    val cls = runCatching { Class.forName(testClassName) }.getOrNull() ?: return false
    if (isJunit4ParameterizedHost(cls)) return true
    return cls.declaredMethods.any { method ->
        method.isJunitTestTemplate() &&
            (junitMethodIdentity(method) == testMethodName || method.name == testMethodName)
    }
}

/**
 * JUnit 4 `@RunWith(Parameterized)` executes each parameter set as an ordinary `@Test`. Detect that
 * runner (including subclasses and `@Inherited` declarations) without resolving `Parameterized` at
 * class-load time so JUnit 5-only consumers stay intact.
 */
private fun isJunit4ParameterizedHost(cls: Class<*>): Boolean {
    // getAnnotations() includes @Inherited @RunWith from a superclass.
    val runWith =
        cls.annotations.firstOrNull { it.annotationClass.java.name == JUNIT4_RUN_WITH }
            ?: return false
    val runner =
        runCatching {
            runWith.annotationClass.java.getMethod("value").invoke(runWith) as? Class<*>
        }
            .getOrNull() ?: return false
    return generateSequence(runner) { current ->
            current.superclass?.takeUnless { it == Any::class.java }
        }
        .any { it.name == JUNIT4_PARAMETERIZED_RUNNER }
}

private fun methodMatchesType(method: Method, methodType: MethodType): Boolean =
    method.returnType == methodType.returnType() &&
        method.parameterTypes.contentEquals(methodType.parameterArray())

private fun isGoldFacadeClass(className: String): Boolean = GOLD_FACADE_CLASSES.any { facade ->
    className == facade || className.startsWith(facade + "$")
}

private const val JUNIT4_RUN_WITH = "org.junit.runner.RunWith"
private const val JUNIT4_PARAMETERIZED_RUNNER = "org.junit.runners.Parameterized"

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

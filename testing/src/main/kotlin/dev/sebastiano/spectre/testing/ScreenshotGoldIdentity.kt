package dev.sebastiano.spectre.testing

import java.lang.StackWalker
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal fun inferTestIdentity(executingClass: Class<*>? = null): GoldTestIdentity =
    StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
        frames.iterator().asSequence().firstNotNullOfOrNull { frame ->
            testIdentityFromWalkerFrame(frame, executingClass)
        }
    }
        ?: error(
            "assertMatchesGold could not infer the calling test method; pass TestInfo explicitly"
        )

internal fun testIdentityFromWalkerFrame(
    frame: StackWalker.StackFrame,
    executingClass: Class<*>? = null,
): GoldTestIdentity? {
    val cls = frame.declaringClass
    if (
        GOLD_FACADE_CLASSES.any { facade ->
            cls.name == facade || cls.name.startsWith(facade + "$")
        }
    ) {
        return null
    }
    val method =
        resolveJunitTestMethod(
            emptyArray(),
            frame.methodName,
            frame.methodType,
            hierarchyRoot = cls,
        ) ?: return null
    return identityFromResolved(cls, method, executingClass)
}

internal fun testIdentityFromFrame(frame: StackTraceElement): GoldTestIdentity? {
    val className = frame.className
    // Skip gold facade frames, not other types whose names happen to start with
    // ScreenshotGold (e.g. ScreenshotGoldAssertTest).
    if (
        GOLD_FACADE_CLASSES.any { facade ->
            className == facade || className.startsWith(facade + "$")
        }
    ) {
        return null
    }
    val cls = loadTestClass(className) ?: return null
    val method =
        resolveJunitTestMethod(emptyArray(), frame.methodName, hierarchyRoot = cls) ?: return null
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
    hierarchyRoot: Class<*>? = null,
): Method? {
    val declaredSets: Sequence<Array<Method>> =
        if (hierarchyRoot == null) {
            sequenceOf(methods)
        } else {
            generateSequence(hierarchyRoot) { current ->
                    current.superclass?.takeUnless { it == Any::class.java }
                }
                .flatMap { host ->
                    sequenceOf(host.declaredMethods) +
                        host.interfaces.asSequence().map { it.declaredMethods }
                }
        }
    for (declared in declaredSets) {
        val candidates = declared.filter { it.name == methodName && it.isJunitTestMethod() }
        if (candidates.isEmpty()) continue
        val match =
            if (methodType != null) {
                candidates.firstOrNull { methodMatchesType(it, methodType) }
            } else {
                candidates.singleOrNull()
            }
        if (match != null) return match
    }
    return null
}

/**
 * True for JUnit 4 `@Test`, JUnit 5 `@Test` / `@TestTemplate` / `@TestFactory`, the platform
 * `@Testable` meta-annotation, and composed annotations that meta-annotate those (including
 * `@ParameterizedTest` and `@RepeatedTest`).
 */
internal fun Method.isJunitTestMethod(): Boolean = annotations.any {
    annotationMetaNamed(it.annotationClass.java, JUNIT_TEST_ANNOTATION_NAMES)
}

internal fun Method.isJunitTestTemplate(): Boolean = annotations.any {
    annotationMetaNamed(it.annotationClass.java, JUNIT_TEMPLATE_ANNOTATION_NAMES)
}

internal fun resolveInvocationKey(
    testClassName: String,
    testMethodName: String,
    invocationKey: String?,
    derivedInvocationKey: String? = null,
    testClass: Class<*>? = null,
): String? {
    val explicit = invocationKey?.takeIf { it.isNotBlank() }
    if (explicit != null) return explicit
    val cls = testClass ?: loadTestClass(testClassName)
    // Class-template hosts repeat method-level indexes ([1], repetition 1) once per outer
    // argument set. A TestInfo-derived method key is not unique across those invocations.
    if (cls != null && isJunit5ClassTemplateHost(cls)) {
        error(
            "assertMatchesGold on a parameterized or repeated test requires invocationKey " +
                "or a unique TestInfo display name so each invocation gets its own gold"
        )
    }
    val derived = derivedInvocationKey?.takeIf { it.isNotBlank() }
    if (derived != null) return derived
    if (cls != null && requiresExplicitInvocationKey(cls, testMethodName)) {
        error(
            "assertMatchesGold on a parameterized or repeated test requires invocationKey " +
                "or a unique TestInfo display name so each invocation gets its own gold"
        )
    }
    return null
}

private fun identityFromResolved(
    cls: Class<*>,
    method: Method,
    executingClass: Class<*>? = null,
): GoldTestIdentity {
    val host = executingClass ?: cls
    // Abstract/interface hosts and inherited methods (declaring class != executing class)
    // would share a gold across subclasses. Ordinary non-final Java classes used directly
    // are fine — Java test classes are non-final by default. JUnit 4 callers can pass the
    // executing class; JUnit 5 callers can pass TestInfo.
    val abstractOrInterface = host.isInterface || Modifier.isAbstract(host.modifiers)
    val inherited = method.declaringClass != host
    if (executingClass == null && (abstractOrInterface || inherited)) {
        error(
            "assertMatchesGold cannot infer the concrete test class from ${cls.name}; " +
                "pass TestInfo or the executing test class so inherited tests key golds " +
                "by the running class"
        )
    }
    if (executingClass != null && abstractOrInterface) {
        error(
            "assertMatchesGold cannot infer the concrete test class from ${host.name}; " +
                "pass TestInfo or the executing test class so inherited tests key golds " +
                "by the running class"
        )
    }
    return GoldTestIdentity(host, junitMethodIdentity(method))
}

internal data class GoldTestIdentity(val testClass: Class<*>, val testMethodName: String) {
    val testClassName: String
        get() = testClass.name
}

private fun requiresExplicitInvocationKey(cls: Class<*>, testMethodName: String): Boolean {
    if (isJunit4ParameterizedHost(cls)) return true
    if (isJunit5ClassTemplateHost(cls)) return true
    return cls.declaredMethods.any { method ->
        method.isJunitTestTemplate() &&
            (junitMethodIdentity(method) == testMethodName || method.name == testMethodName)
    }
}

/**
 * Reloads [className] with the context or supplied loader before Spectre's defining loader. Plugin
 * and child test loaders are often invisible to one-argument `Class.forName`.
 */
private fun loadTestClass(className: String, hint: ClassLoader? = null): Class<*>? {
    val loaders = listOfNotNull(hint, Thread.currentThread().contextClassLoader).distinct()
    for (loader in loaders) {
        runCatching { Class.forName(className, false, loader) }
            .getOrNull()
            ?.let {
                return it
            }
    }
    return runCatching { Class.forName(className) }.getOrNull()
}

/**
 * JUnit 5.14 `@ParameterizedClass` (and `@ClassTemplate`) re-runs ordinary `@Test` methods once per
 * class invocation. Detect the class-level template — including meta-annotations, `@Inherited`
 * declarations, and enclosing parameterized hosts — without resolving `ParameterizedClass` so
 * consumers that omit `junit-jupiter-params` stay intact.
 */
private fun isJunit5ClassTemplateHost(cls: Class<*>): Boolean =
    generateSequence(cls) { current ->
            current.enclosingClass?.takeUnless { it == Any::class.java }
        }
        .any { host ->
            host.annotations.any {
                annotationMetaNamed(it.annotationClass.java, JUNIT_CLASS_TEMPLATE_ANNOTATION_NAMES)
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

private const val JUNIT4_RUN_WITH = "org.junit.runner.RunWith"
private const val JUNIT4_PARAMETERIZED_RUNNER = "org.junit.runners.Parameterized"

private val GOLD_FACADE_CLASSES =
    setOf(
        "dev.sebastiano.spectre.testing.ScreenshotGoldKt",
        "dev.sebastiano.spectre.testing.ScreenshotGoldJunit5",
        "dev.sebastiano.spectre.testing.ScreenshotGoldIdentityKt",
    )

private val JUNIT_TEST_ANNOTATION_NAMES =
    setOf(
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.api.TestTemplate",
        "org.junit.jupiter.api.TestFactory",
        "org.junit.platform.commons.annotation.Testable",
        "org.junit.Test",
    )

private val JUNIT_TEMPLATE_ANNOTATION_NAMES =
    setOf("org.junit.jupiter.api.TestTemplate", "org.junit.jupiter.api.TestFactory")

private val JUNIT_CLASS_TEMPLATE_ANNOTATION_NAMES = setOf("org.junit.jupiter.api.ClassTemplate")

private fun annotationMetaNamed(
    annotationType: Class<out Annotation>,
    names: Set<String>,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    val name = annotationType.name
    if (!visited.add(name)) return false
    if (name in names) return true
    if (name.startsWith("java.") || name.startsWith("kotlin.")) return false
    return annotationType.annotations.any { meta ->
        annotationMetaNamed(meta.annotationClass.java, names, visited)
    }
}

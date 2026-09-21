package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ScreenshotGoldJunit5Test {

    @Test
    fun `name-only ScreenshotGoldKt facade has no TestInfo descriptors`() {
        val methods = Class.forName("dev.sebastiano.spectre.testing.ScreenshotGoldKt").methods
        methods.forEach { method ->
            assertTrue(
                method.parameterTypes.none { it.name == "org.junit.jupiter.api.TestInfo" },
                method.toString(),
            )
        }
    }

    @Test
    fun `name-only ScreenshotGoldKt facade exposes a two-argument Java overload`() {
        val method =
            Class.forName("dev.sebastiano.spectre.testing.ScreenshotGoldKt").methods.singleOrNull {
                candidate ->
                candidate.name == "assertMatchesGold" &&
                    candidate.parameterTypes.map { it.name } ==
                        listOf("java.lang.String", "java.awt.image.BufferedImage")
            }
        assertTrue(method != null, "ScreenshotGoldKt.assertMatchesGold(name, image)")
    }

    @Test
    fun `name-only ScreenshotGoldKt facade exposes a Class test-identity Java overload`() {
        val method =
            Class.forName("dev.sebastiano.spectre.testing.ScreenshotGoldKt").methods.singleOrNull {
                candidate ->
                candidate.name == "assertMatchesGold" &&
                    candidate.parameterTypes.map { it.name } ==
                        listOf(
                            "java.lang.Class",
                            "java.lang.String",
                            "java.awt.image.BufferedImage",
                        )
            }
        assertTrue(method != null, "ScreenshotGoldKt.assertMatchesGold(testClass, name, image)")
    }

    @Test
    fun `JUnit 5 gold facade isolates TestInfo overloads`() {
        val facade = Class.forName("dev.sebastiano.spectre.testing.ScreenshotGoldJunit5")
        assertTrue(
            facade.methods.any { method ->
                method.parameterTypes.any { it.name == "org.junit.jupiter.api.TestInfo" }
            }
        )
    }

    @Test
    fun `JUnit 5 gold facade class file does not mention ParameterizedTest`() {
        val resource =
            requireNotNull(
                Class.forName("dev.sebastiano.spectre.testing.ScreenshotGoldJunit5")
                    .getResource("ScreenshotGoldJunit5.class")
            ) {
                "ScreenshotGoldJunit5.class"
            }
        val bytes = resource.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertFalse(
            pool.contains("org/junit/jupiter/params/ParameterizedTest"),
            "facade must not load junit-jupiter-params",
        )
    }

    @Test
    fun `TestInfo supplies an invocation key for template methods`() {
        val method = parameterizedMethod()
        val pattern = method.getAnnotation(ParameterizedTest::class.java)!!.name
        assertTrue(
            method.hasVaryingInvocationNamePattern(),
            "default ParameterizedTest name must vary, was '$pattern'",
        )
        assertEquals("[1] dark", invocationKeyFromTestInfo(fakeTestInfo("[1] dark", method)))
    }

    @Test
    fun `identity-hash display names keep only the stable invocation index`() {
        val method = parameterizedMethod()
        assertEquals(
            "[1]",
            invocationKeyFromTestInfo(fakeTestInfo("[1] value=Foo@4a12bc", method)),
        )
        assertEquals(
            "[2]",
            invocationKeyFromTestInfo(fakeTestInfo("[2] [Ldev.example.Theme;@7f31245a", method)),
        )
        assertEquals(
            "repetition 1 of 2",
            invocationKeyFromTestInfo(
                fakeTestInfo("Foo@4a12bc repetition 1 of 2", repeatedMethod())
            ),
        )
    }

    @Test
    fun `identity-hash display without a stable index requires invocationKey`() {
        val method = parameterizedMethod()
        val info = fakeTestInfo("value=Foo@4a12bc", method)
        assertNull(invocationKeyFromTestInfo(info))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    ScreenshotGoldJunit5Test::class.java.name,
                    "ParameterizedTest methods are recognized for gold identity",
                    invocationKey = invocationKeyFromTestInfo(info),
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `TestInfo repeated-test display name is a unique invocation key`() {
        val info = fakeTestInfo("repetition 1 of 2", repeatedMethod())
        assertEquals("repetition 1 of 2", invocationKeyFromTestInfo(info))
    }

    @Test
    fun `TestInfo constant display name is not treated as a unique invocation key`() {
        val info = fakeTestInfo("theme", parameterizedMethod())
        assertNull(invocationKeyFromTestInfo(info))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    ScreenshotGoldJunit5Test::class.java.name,
                    "ParameterizedTest methods are recognized for gold identity",
                    invocationKey = invocationKeyFromTestInfo(info),
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `TestInfo does not invent an invocation key for plain tests`() {
        val info =
            fakeTestInfo(
                "name-only ScreenshotGoldKt facade has no TestInfo descriptors",
                javaClass.getDeclaredMethod(
                    "name-only ScreenshotGoldKt facade has no TestInfo descriptors"
                ),
            )
        assertNull(invocationKeyFromTestInfo(info))
    }

    @Test
    fun `plain test methods do not require an invocation key`() {
        assertNull(
            resolveInvocationKey(
                ScreenshotGoldJunit5Test::class.java.name,
                "name-only ScreenshotGoldKt facade has no TestInfo descriptors",
                invocationKey = null,
            )
        )
        assertNull(
            resolveInvocationKey(
                ScreenshotGoldJunit5Test::class.java.name,
                "name-only ScreenshotGoldKt facade has no TestInfo descriptors",
                invocationKey = "   ",
            )
        )
        assertEquals(
            " foo ",
            resolveInvocationKey(
                ScreenshotGoldJunit5Test::class.java.name,
                "name-only ScreenshotGoldKt facade has no TestInfo descriptors",
                invocationKey = " foo ",
            ),
        )
    }

    @Test
    fun `template methods require an invocation key when TestInfo is absent`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    ScreenshotGoldJunit5Test::class.java.name,
                    "ParameterizedTest methods are recognized for gold identity",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @ParameterizedTest(name = "[{0}] theme")
    @ValueSource(ints = [1])
    fun `argument placeholder display that looks unique still requires invocationKey`(value: Int) {
        assertEquals(1, value)
        val method =
            javaClass.declaredMethods.single {
                it.name ==
                    "argument placeholder display that looks unique still requires invocationKey"
            }
        assertFalse(method.hasVaryingInvocationNamePattern())
        val info = fakeTestInfo("[1] theme", method)
        assertNull(invocationKeyFromTestInfo(info))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    ScreenshotGoldJunit5Test::class.java.name,
                    method.name,
                    invocationKey = invocationKeyFromTestInfo(info),
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @ParameterizedTest(name = "[{index}] theme")
    @ValueSource(ints = [1])
    fun `index placeholder display remains a unique invocation key`(value: Int) {
        assertEquals(1, value)
        val method =
            javaClass.declaredMethods.single {
                it.name == "index placeholder display remains a unique invocation key"
            }
        assertTrue(method.hasVaryingInvocationNamePattern())
        assertEquals("[1] theme", invocationKeyFromTestInfo(fakeTestInfo("[1] theme", method)))
    }

    @ParameterizedTest(name = "[1] theme")
    @ValueSource(ints = [1])
    fun `constant display that looks unique still requires invocationKey`(value: Int) {
        assertEquals(1, value)
        val method =
            javaClass.declaredMethods.single {
                it.name == "constant display that looks unique still requires invocationKey"
            }
        val info = fakeTestInfo("[1] theme", method)
        assertNull(invocationKeyFromTestInfo(info))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    ScreenshotGoldJunit5Test::class.java.name,
                    method.name,
                    invocationKey = invocationKeyFromTestInfo(info),
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @ParameterizedTest
    @ValueSource(ints = [1])
    fun `name-only gold assert requires invocationKey on parameterized tests`(value: Int) {
        assertEquals(1, value)
        val error =
            assertFailsWith<IllegalStateException> {
                assertMatchesGold(name = "main-window", image = solid(1, 1, 0xFFFFFF))
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `TestInfo identity ignores the calling thread when class and method are present`() {
        val info =
            fakeTestInfo(
                "probe()",
                javaClass.getDeclaredMethod(
                    "template methods require an invocation key when TestInfo is absent"
                ),
            )
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val identity = worker.submit<GoldTestIdentity> { identityFromTestInfo(info) }.get()
            assertEquals(ScreenshotGoldJunit5Test::class.java.name, identity.testClassName)
            assertEquals(
                "template methods require an invocation key when TestInfo is absent",
                identity.testMethodName,
            )
        } finally {
            worker.shutdownNow()
        }
    }

    @RepeatedTest(name = "[1] {index}", value = 1)
    fun `RepeatedTest index placeholder is not a unique invocation key`() {
        val method =
            javaClass.declaredMethods.single {
                it.name == "RepeatedTest index placeholder is not a unique invocation key"
            }
        assertFalse(method.hasVaryingInvocationNamePattern())
        val info = fakeTestInfo("[1] {index}", method)
        assertNull(invocationKeyFromTestInfo(info))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    ScreenshotGoldJunit5Test::class.java.name,
                    method.name,
                    invocationKey = invocationKeyFromTestInfo(info),
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @RepeatedTest(name = "repetition {currentRepetition} of {totalRepetitions}", value = 1)
    fun `RepeatedTest currentRepetition placeholder remains a unique invocation key`() {
        val method =
            javaClass.declaredMethods.single {
                it.name ==
                    "RepeatedTest currentRepetition placeholder remains a unique invocation key"
            }
        assertTrue(method.hasVaryingInvocationNamePattern())
        assertEquals(
            "repetition 1 of 1",
            invocationKeyFromTestInfo(fakeTestInfo("repetition 1 of 1", method)),
        )
    }

    @RepeatedTest(1)
    fun `RepeatedTest methods are recognized for gold identity`() {
        val identity = inferTestIdentity()
        assertEquals(ScreenshotGoldJunit5Test::class.java.name, identity.testClassName)
        assertEquals(
            "RepeatedTest methods are recognized for gold identity",
            identity.testMethodName,
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [1])
    fun `ParameterizedTest methods are recognized for gold identity`(value: Int) {
        assertEquals(1, value)
        val identity = inferTestIdentity()
        assertEquals(ScreenshotGoldJunit5Test::class.java.name, identity.testClassName)
        assertEquals(
            "ParameterizedTest methods are recognized for gold identity(int)",
            identity.testMethodName,
        )
    }

    @ComposedGoldJunit5Test
    fun `composed Test meta-annotations are recognized for gold identity`() {
        val identity = inferTestIdentity()
        assertEquals(ScreenshotGoldJunit5Test::class.java.name, identity.testClassName)
        assertEquals(
            "composed Test meta-annotations are recognized for gold identity",
            identity.testMethodName,
        )
    }

    @Test
    fun `unannotated overload first does not hide an annotated same-name test`() {
        val cls = GoldJunit5OverloadHost::class.java
        val unannotated = cls.getDeclaredMethod("probe")
        val annotated = cls.getDeclaredMethod("probe", Int::class.java)
        assertFalse(unannotated.isJunitTestMethod())
        assertTrue(annotated.isJunitTestMethod())
        assertEquals(annotated, resolveJunitTestMethod(arrayOf(unannotated, annotated), "probe"))
        assertEquals(annotated, resolveJunitTestMethod(arrayOf(annotated, unannotated), "probe"))
        assertEquals("probe(int)", junitMethodIdentity(annotated))
        val frame = StackTraceElement(cls.name, "probe", "GoldJunit5OverloadHost.kt", 1)
        val identity = testIdentityFromFrame(frame)
        assertEquals(cls, identity?.testClass)
        assertEquals("probe(int)", identity?.testMethodName)
    }

    private fun parameterizedMethod(): java.lang.reflect.Method =
        javaClass.declaredMethods.single {
            it.name == "ParameterizedTest methods are recognized for gold identity"
        }

    private fun repeatedMethod(): java.lang.reflect.Method =
        javaClass.declaredMethods.single {
            it.name == "RepeatedTest methods are recognized for gold identity"
        }

    private fun fakeTestInfo(
        displayName: String,
        method: java.lang.reflect.Method,
    ): TestInfo =
        object : TestInfo {
            override fun getDisplayName(): String = displayName

            override fun getTags(): Set<String> = emptySet()

            override fun getTestClass(): java.util.Optional<Class<*>> =
                java.util.Optional.of(ScreenshotGoldJunit5Test::class.java)

            override fun getTestMethod(): java.util.Optional<java.lang.reflect.Method> =
                java.util.Optional.of(method)
        }

    private fun solid(width: Int, height: Int, rgb: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val packed = (0xFF shl 24) or (rgb and 0x00FFFFFF)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, packed)
            }
        }
        return image
    }
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
private annotation class ComposedGoldJunit5Test

/** Host for overload-identity tests; JUnit must not execute these as specs. */
@Disabled("reflective fixture for gold identity overload resolution")
internal class GoldJunit5OverloadHost {
    @Suppress("unused")
    fun probe() {
        error("unannotated overload")
    }

    @Test
    fun probe(ignored: Int) {
        error("annotated overload is only used reflectively")
    }
}

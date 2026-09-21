package dev.sebastiano.spectre.testing

import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.ClassTemplate
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.ValueSource

class ScreenshotGoldIdentityTest {

    @Test
    fun `annotated overloads with parameters keep distinct gold identities`() {
        val cls = GoldIdentitySignatureHost::class.java
        val noArg = cls.getDeclaredMethod("render")
        val withInfo = cls.getDeclaredMethod("render", TestInfo::class.java)
        assertEquals("render()", junitMethodIdentity(noArg))
        assertEquals("render(org.junit.jupiter.api.TestInfo)", junitMethodIdentity(withInfo))
        assertNotEquals(junitMethodIdentity(noArg), junitMethodIdentity(withInfo))
        assertNull(resolveJunitTestMethod(arrayOf(noArg, withInfo), "render"))
        assertEquals(
            noArg,
            resolveJunitTestMethod(
                arrayOf(noArg, withInfo),
                "render",
                MethodType.methodType(GoldTestIdentity::class.java),
            ),
        )
        assertEquals(
            withInfo,
            resolveJunitTestMethod(
                arrayOf(noArg, withInfo),
                "render",
                MethodType.methodType(GoldTestIdentity::class.java, TestInfo::class.java),
            ),
        )
    }

    @Test
    fun `zero-arg parenthesized names do not collide with typed overloads`() {
        val cls = GoldIdentityParenNameCollisionHost::class.java
        val named = cls.getDeclaredMethod("render(int)")
        val typed = cls.getDeclaredMethod("render", Int::class.java)
        assertEquals(0, named.parameterCount)
        assertEquals(1, typed.parameterCount)
        assertNotEquals(junitMethodIdentity(named), junitMethodIdentity(typed))
        assertEquals("render(int)()", junitMethodIdentity(named))
        assertEquals("render(int)", junitMethodIdentity(typed))
    }

    @Test
    fun `stack inference includes the executing overload signature`() {
        val host = GoldIdentitySignatureHost()
        val noArg = host.render()
        val withInfo =
            host.render(
                fakeTestInfo("render", host.javaClass, host.javaClass.getDeclaredMethod("render"))
            )
        assertEquals(GoldIdentitySignatureHost::class.java.name, noArg.testClassName)
        assertEquals("render()", noArg.testMethodName)
        assertEquals("render(org.junit.jupiter.api.TestInfo)", withInfo.testMethodName)
    }

    @Test
    fun `TestInfo identity includes the method signature`(testInfo: TestInfo) {
        val fromStack = inferTestIdentity()
        val fromInfo = identityFromTestInfo(testInfo)
        assertEquals(ScreenshotGoldIdentityTest::class.java.name, fromStack.testClassName)
        assertEquals(
            "TestInfo identity includes the method signature(org.junit.jupiter.api.TestInfo)",
            fromStack.testMethodName,
        )
        assertEquals(fromStack, fromInfo)
    }

    @Test
    fun `inherited tests fail closed without TestInfo`() {
        val frame =
            StackTraceElement(
                GoldIdentityInheritedBase::class.java.name,
                "inheritedProbe",
                "GoldIdentityInheritedBase.kt",
                1,
            )
        val error = assertFailsWith<IllegalStateException> { testIdentityFromFrame(frame) }
        assertTrue(error.message!!.contains("TestInfo"), error.message)
        assertTrue(error.message!!.contains("concrete"), error.message)
        val live =
            assertFailsWith<IllegalStateException> {
                GoldIdentityInheritedConcrete().inheritedProbe()
            }
        assertTrue(live.message!!.contains("TestInfo"), live.message)
    }

    @Test
    fun `inherited interface tests fail closed without TestInfo`() {
        val frame =
            StackTraceElement(
                GoldIdentityInheritedInterface::class.java.name,
                "inheritedDefault",
                "GoldIdentityInheritedInterface.kt",
                1,
            )
        val error = assertFailsWith<IllegalStateException> { testIdentityFromFrame(frame) }
        assertTrue(error.message!!.contains("TestInfo"), error.message)
    }

    @Test
    fun `template method names that contain parentheses still require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityParenTemplateHost::class.java.name,
                    "renders (dark)",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `JUnit 4 Parameterized hosts require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit4ParameterizedHost::class.java.name,
                    "name-only gold assert requires invocationKey",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
        assertEquals(
            "dark",
            resolveInvocationKey(
                GoldIdentityJunit4ParameterizedHost::class.java.name,
                "name-only gold assert requires invocationKey",
                invocationKey = "dark",
            ),
        )
    }

    @Test
    fun `plain JUnit 4 tests do not require an invocation key`() {
        assertNull(
            resolveInvocationKey(
                GoldIdentityJunit4PlainHost::class.java.name,
                "probe",
                invocationKey = null,
            )
        )
    }

    @Test
    fun `JUnit 5 ParameterizedClass hosts require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit5ParameterizedClassHost::class.java.name,
                    "name-only gold assert requires invocationKey",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
        assertEquals(
            "dark",
            resolveInvocationKey(
                GoldIdentityJunit5ParameterizedClassHost::class.java.name,
                "name-only gold assert requires invocationKey",
                invocationKey = "dark",
            ),
        )
    }

    @Test
    fun `JUnit 5 ClassTemplate hosts require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit5ClassTemplateHost::class.java.name,
                    "probe",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
        assertEquals(
            "dark",
            resolveInvocationKey(
                GoldIdentityJunit5ClassTemplateHost::class.java.name,
                "probe",
                invocationKey = "dark",
            ),
        )
    }

    @Test
    fun `composed ParameterizedClass hosts require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit5ComposedParameterizedClassHost::class.java.name,
                    "probe",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `nested hosts inside ParameterizedClass require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit5ParameterizedClassOuter.NestedHost::class.java.name,
                    "probe",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `inherited ParameterizedClass hosts require invocationKey`() {
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit5ParameterizedClassChild::class.java.name,
                    "probe",
                    invocationKey = null,
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `TestInfo method-level key is not enough for ParameterizedClass hosts`() {
        val method =
            GoldIdentityJunit5ParameterizedClassTemplateHost::class.java.declaredMethods.single {
                it.name == "nested parameterized"
            }
        val info =
            fakeTestInfo(
                "[1] dark",
                GoldIdentityJunit5ParameterizedClassTemplateHost::class.java,
                method,
            )
        assertEquals("[1] dark", invocationKeyFromTestInfo(info))
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFFFFFF.toInt())
        val error =
            assertFailsWith<IllegalStateException> {
                assertMatchesGold(testInfo = info, name = "main-window", image = image)
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
        assertEquals(
            "dark-[1]",
            resolveInvocationKey(
                GoldIdentityJunit5ParameterizedClassTemplateHost::class.java.name,
                method.name,
                invocationKey = "dark-[1]",
            ),
        )
    }

    @Test
    fun `child class loader ClassTemplate hosts still require invocationKey`() {
        IsolatedGoldHostLoader().use { isolated ->
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = isolated.loader
            try {
                val error =
                    assertFailsWith<IllegalStateException> {
                        resolveInvocationKey(
                            isolated.classTemplateHost.name,
                            "probe",
                            invocationKey = null,
                        )
                    }
                assertTrue(error.message!!.contains("invocationKey"), error.message)
                assertEquals(
                    "dark",
                    resolveInvocationKey(
                        isolated.classTemplateHost.name,
                        "probe",
                        invocationKey = "dark",
                    ),
                )
            } finally {
                Thread.currentThread().contextClassLoader = previous
            }
        }
    }

    @Test
    fun `child class loader parameterized methods still require invocationKey`() {
        IsolatedGoldHostLoader().use { isolated ->
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = isolated.loader
            try {
                val error =
                    assertFailsWith<IllegalStateException> {
                        resolveInvocationKey(
                            isolated.parameterizedMethodHost.name,
                            "renders",
                            invocationKey = null,
                        )
                    }
                assertTrue(error.message!!.contains("invocationKey"), error.message)
            } finally {
                Thread.currentThread().contextClassLoader = previous
            }
        }
    }

    @Test
    fun `TestInfo from a child class loader ClassTemplate still requires invocationKey`() {
        IsolatedGoldHostLoader().use { isolated ->
            val method = isolated.classTemplateHost.getDeclaredMethod("probe")
            val info =
                fakeTestInfo(
                    "probe()",
                    isolated.classTemplateHost,
                    method,
                )
            assertNull(invocationKeyFromTestInfo(info))
            val image =
                java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, 0xFFFFFFFF.toInt())
            val error =
                assertFailsWith<IllegalStateException> {
                    assertMatchesGold(testInfo = info, name = "main-window", image = image)
                }
            assertTrue(error.message!!.contains("invocationKey"), error.message)
        }
    }

    @Test
    fun `TestInfo cannot invent a key for ParameterizedClass ordinary tests`() {
        val method =
            GoldIdentityJunit5ParameterizedClassHost::class
                .java
                .getDeclaredMethod("name-only gold assert requires invocationKey")
        val info =
            fakeTestInfo(
                "name-only gold assert requires invocationKey()",
                GoldIdentityJunit5ParameterizedClassHost::class.java,
                method,
            )
        assertNull(invocationKeyFromTestInfo(info))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveInvocationKey(
                    GoldIdentityJunit5ParameterizedClassHost::class.java.name,
                    method.name,
                    invocationKey = invocationKeyFromTestInfo(info),
                )
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    @Test
    fun `non-final concrete Java host used directly still resolves`() {
        val cls = GoldIdentityJavaStyleHost::class.java
        assertFalse(Modifier.isFinal(cls.modifiers), cls.toString())
        assertFalse(Modifier.isAbstract(cls.modifiers), cls.toString())
        val frame = StackTraceElement(cls.name, "probe", "GoldIdentityJavaStyleHost.java", 1)
        val fromFrame = assertNotNull(testIdentityFromFrame(frame))
        assertEquals(cls.name, fromFrame.testClassName)
        assertEquals("probe()", fromFrame.testMethodName)
        val live = GoldIdentityJavaStyleHost().probe()
        assertEquals(cls.name, live.testClassName)
        assertEquals("probe()", live.testMethodName)
        val javaBase = GoldIdentityJavaBase::class.java
        val baseFrame =
            StackTraceElement(
                javaBase.name,
                "inheritedFromJavaBase",
                "GoldIdentityJavaBase.java",
                1,
            )
        val fromBase = assertNotNull(testIdentityFromFrame(baseFrame))
        assertEquals(javaBase.name, fromBase.testClassName)
        assertEquals("inheritedFromJavaBase()", fromBase.testMethodName)
        val liveBase = GoldIdentityJavaBase().inheritedFromJavaBase()
        assertEquals(javaBase.name, liveBase.testClassName)
    }

    @Test
    fun `explicit executing class keys inherited golds without TestInfo`() {
        val identity = GoldIdentityConcreteChildA().inheritedKeyedByExecutingClass()
        assertEquals(GoldIdentityConcreteChildA::class.java.name, identity.testClassName)
        assertEquals("inheritedKeyedByExecutingClass()", identity.testMethodName)
    }

    @Test
    fun `inherited method on a subclass frame fails closed without TestInfo`() {
        val kotlinChild =
            StackTraceElement(
                GoldIdentityConcreteChildA::class.java.name,
                "inheritedFromConcrete",
                "GoldIdentityConcreteChildA.kt",
                1,
            )
        val kotlinError =
            assertFailsWith<IllegalStateException> { testIdentityFromFrame(kotlinChild) }
        assertTrue(kotlinError.message!!.contains("TestInfo"), kotlinError.message)
        assertTrue(kotlinError.message!!.contains("concrete"), kotlinError.message)
        val javaChild =
            StackTraceElement(
                GoldIdentityJavaChild::class.java.name,
                "inheritedFromJavaBase",
                "GoldIdentityJavaChild.java",
                1,
            )
        val javaError = assertFailsWith<IllegalStateException> { testIdentityFromFrame(javaChild) }
        assertTrue(javaError.message!!.contains("TestInfo"), javaError.message)
    }

    @Test
    fun `TestInfo keys inherited tests by the concrete class`() {
        val method = GoldIdentityInheritedBase::class.java.getDeclaredMethod("inheritedProbe")
        val info =
            fakeTestInfo("inheritedProbe()", GoldIdentityInheritedConcrete::class.java, method)
        val identity = identityFromTestInfo(info)
        assertEquals(GoldIdentityInheritedConcrete::class.java.name, identity.testClassName)
        assertEquals("inheritedProbe()", identity.testMethodName)
        val fromConcreteBase =
            identityFromTestInfo(
                fakeTestInfo(
                    "inheritedFromConcrete()",
                    GoldIdentityConcreteChildA::class.java,
                    GoldIdentityConcreteBase::class.java.getDeclaredMethod("inheritedFromConcrete"),
                )
            )
        assertEquals(GoldIdentityConcreteChildA::class.java.name, fromConcreteBase.testClassName)
        assertEquals("inheritedFromConcrete()", fromConcreteBase.testMethodName)
    }

    private fun fakeTestInfo(
        displayName: String,
        testClass: Class<*>,
        testMethod: Method,
    ): TestInfo =
        object : TestInfo {
            override fun getDisplayName(): String = displayName

            override fun getTags(): Set<String> = emptySet()

            override fun getTestClass(): Optional<Class<*>> = Optional.of(testClass)

            override fun getTestMethod(): Optional<Method> = Optional.of(testMethod)
        }
}

/** Host for same-name annotated overloads; JUnit must not execute these as specs. */
@Disabled("reflective fixture for gold identity signature resolution")
internal class GoldIdentitySignatureHost {
    @Test fun render(): GoldTestIdentity = inferTestIdentity()

    @Test fun render(ignored: TestInfo): GoldTestIdentity = inferTestIdentity()
}

@Disabled("reflective fixture for parenthesized zero-arg gold identity")
internal class GoldIdentityParenNameCollisionHost {
    @Suppress("unused") fun `render(int)`(): GoldTestIdentity = inferTestIdentity()

    @Suppress("unused") fun render(value: Int): GoldTestIdentity = inferTestIdentity()
}

internal abstract class GoldIdentityInheritedBase {
    abstract val inheritedGoldAnchor: String

    @Test fun inheritedProbe(): GoldTestIdentity = inferTestIdentity()
}

@Disabled("reflective fixture for inherited gold identity")
internal class GoldIdentityInheritedConcrete : GoldIdentityInheritedBase() {
    override val inheritedGoldAnchor: String = "concrete"
}

internal interface GoldIdentityInheritedInterface {
    @Test fun inheritedDefault(): GoldTestIdentity = inferTestIdentity()
}

@Disabled("reflective fixture for inherited gold identity")
internal class GoldIdentityInheritedImpl : GoldIdentityInheritedInterface

@Disabled("reflective fixture for parenthesized template method names")
internal class GoldIdentityParenTemplateHost {
    @RepeatedTest(1)
    fun `renders (dark)`() {
        error("template fixture")
    }
}

@Disabled("reflective fixture for inherited gold identity")
internal open class GoldIdentityConcreteBase {
    @Test fun inheritedFromConcrete(): GoldTestIdentity = inferTestIdentity()

    @Test fun inheritedKeyedByExecutingClass(): GoldTestIdentity = inferTestIdentity(javaClass)
}

@Disabled("reflective fixture for inherited gold identity")
internal class GoldIdentityConcreteChildA : GoldIdentityConcreteBase()

@Disabled("reflective fixture for inherited gold identity")
internal class GoldIdentityConcreteChildB : GoldIdentityConcreteBase()

@Disabled("reflective fixture for JUnit 4 parameterized gold identity")
@org.junit.Ignore("reflective fixture for JUnit 4 parameterized gold identity")
internal class GoldIdentityJunit4PlainHost {
    @org.junit.Test
    fun probe() {
        error("plain JUnit 4 fixture")
    }
}

/**
 * Vintage-executed JUnit 4 Parameterized host. Each parameter set runs an ordinary `@Test`, so gold
 * identity must fail closed without an explicit `invocationKey`.
 */
@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
internal class GoldIdentityJunit4ParameterizedHost(private val themeIndex: Int) {
    @org.junit.Test
    fun `name-only gold assert requires invocationKey`() {
        assertEquals(1, themeIndex)
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFFFFFF.toInt())
        val error =
            assertFailsWith<IllegalStateException> {
                assertMatchesGold(name = "main-window", image = image)
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }

    companion object {
        @JvmStatic
        @org.junit.runners.Parameterized.Parameters
        fun data(): Collection<Array<Any>> = listOf(arrayOf(1), arrayOf(1))
    }
}

/**
 * JUnit 5.14 `@ParameterizedClass` host. Each constructor argument set runs the same ordinary
 * `@Test`, so gold identity must fail closed without an explicit `invocationKey`.
 */
@ParameterizedClass
@ValueSource(ints = [1, 1])
internal class GoldIdentityJunit5ParameterizedClassHost(private val themeIndex: Int) {
    @Test
    fun `name-only gold assert requires invocationKey`() {
        assertEquals(1, themeIndex)
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFFFFFF.toInt())
        val error =
            assertFailsWith<IllegalStateException> {
                assertMatchesGold(name = "main-window", image = image)
            }
        assertTrue(error.message!!.contains("invocationKey"), error.message)
    }
}

@Disabled("reflective fixture for ParameterizedClass + ParameterizedTest gold identity")
@ParameterizedClass
@ValueSource(ints = [1])
internal class GoldIdentityJunit5ParameterizedClassTemplateHost {
    @org.junit.jupiter.params.ParameterizedTest
    @ValueSource(ints = [1])
    fun `nested parameterized`(value: Int) {
        assertEquals(1, value)
        error("parameterized-class template fixture")
    }
}

@Disabled("reflective fixture for JUnit 5 ClassTemplate gold identity")
@ClassTemplate
internal class GoldIdentityJunit5ClassTemplateHost {
    @Test
    fun probe() {
        error("class-template fixture")
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedClass
private annotation class ComposedGoldParameterizedClass

@Disabled("reflective fixture for composed ParameterizedClass gold identity")
@ComposedGoldParameterizedClass
@ValueSource(ints = [1])
internal class GoldIdentityJunit5ComposedParameterizedClassHost {
    @Test
    fun probe() {
        error("composed parameterized-class fixture")
    }
}

@Disabled("reflective fixture for nested ParameterizedClass gold identity")
@ParameterizedClass
@ValueSource(ints = [1])
internal class GoldIdentityJunit5ParameterizedClassOuter {
    @Disabled("reflective fixture for nested ParameterizedClass gold identity")
    class NestedHost {
        @Test
        fun probe() {
            error("nested parameterized-class fixture")
        }
    }
}

@Disabled("reflective fixture for inherited ParameterizedClass gold identity")
@ParameterizedClass
@ValueSource(ints = [1])
internal open class GoldIdentityJunit5ParameterizedClassBase {
    @Test
    fun probe() {
        error("inherited parameterized-class fixture")
    }
}

@Disabled("reflective fixture for inherited ParameterizedClass gold identity")
internal class GoldIdentityJunit5ParameterizedClassChild :
    GoldIdentityJunit5ParameterizedClassBase()

/**
 * Compiles ClassTemplate / ParameterizedTest hosts that Spectre's defining loader cannot see, then
 * loads them through a child [java.net.URLClassLoader]. One-argument `Class.forName` from
 * spectre-testing therefore fails unless identity resolution keeps the discovered class or the
 * context/child loader.
 */
private class IsolatedGoldHostLoader : AutoCloseable {
    private val work = java.nio.file.Files.createTempDirectory("spectre-gold-isolated")
    val loader: java.net.URLClassLoader
    val classTemplateHost: Class<*>
    val parameterizedMethodHost: Class<*>

    init {
        val compiler =
            javax.tools.ToolProvider.getSystemJavaCompiler()
                ?: error("JDK JavaCompiler is required to isolate gold-identity test hosts")
        val srcDir = work.resolve("src")
        java.nio.file.Files.createDirectories(srcDir)
        val source = srcDir.resolve("IsolatedGoldHosts.java")
        java.nio.file.Files.writeString(
            source,
            """
            package isolated.gold;
            import org.junit.jupiter.api.ClassTemplate;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;

            @ClassTemplate
            final class IsolatedClassTemplateHost {
                @Test public void probe() {}
            }

            final class IsolatedParameterizedMethodHost {
                @ParameterizedTest
                @ValueSource(ints = {1})
                public void renders(int value) {}
            }
            """
                .trimIndent(),
        )
        val classpath =
            listOf(
                    org.junit.jupiter.api.ClassTemplate::class.java,
                    org.junit.jupiter.params.ParameterizedTest::class.java,
                )
                .map { type ->
                    java.nio.file.Path.of(type.protectionDomain.codeSource.location.toURI())
                        .toString()
                }
                .distinct()
                .joinToString(java.io.File.pathSeparator)
        compiler.getStandardFileManager(null, null, null).use { files ->
            val units = files.getJavaFileObjects(source.toFile())
            val compiled =
                compiler
                    .getTask(
                        null,
                        files,
                        null,
                        listOf("-classpath", classpath, "-d", work.toString()),
                        null,
                        units,
                    )
                    .call()
            check(compiled == true) { "failed to compile isolated gold-identity hosts" }
        }
        loader =
            java.net.URLClassLoader(
                arrayOf(work.toUri().toURL()),
                ScreenshotGoldIdentityTest::class.java.classLoader,
            )
        classTemplateHost = Class.forName("isolated.gold.IsolatedClassTemplateHost", true, loader)
        parameterizedMethodHost =
            Class.forName("isolated.gold.IsolatedParameterizedMethodHost", true, loader)
        check(runCatching { Class.forName(classTemplateHost.name) }.getOrNull() == null) {
            "isolated ClassTemplate host must be invisible to Spectre's defining loader"
        }
    }

    override fun close() {
        loader.close()
        val paths = java.nio.file.Files.walk(work).use { it.toList() }
        paths.sortedByDescending { it.nameCount }.forEach { java.nio.file.Files.deleteIfExists(it) }
    }
}

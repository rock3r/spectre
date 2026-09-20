package dev.sebastiano.spectre.testing

import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

class ScreenshotGoldIdentityTest {

    @Test
    fun `annotated overloads with parameters keep distinct gold identities`() {
        val cls = GoldIdentitySignatureHost::class.java
        val noArg = cls.getDeclaredMethod("render")
        val withInfo = cls.getDeclaredMethod("render", TestInfo::class.java)
        assertEquals("render", junitMethodIdentity(noArg))
        assertEquals("render(org.junit.jupiter.api.TestInfo)", junitMethodIdentity(withInfo))
        assertNotEquals(junitMethodIdentity(noArg), junitMethodIdentity(withInfo))
        assertNull(resolveJunitTestMethod(arrayOf(noArg, withInfo), "render"))
        assertEquals(
            noArg,
            resolveJunitTestMethod(
                arrayOf(noArg, withInfo),
                "render",
                MethodType.methodType(Pair::class.java),
            ),
        )
        assertEquals(
            withInfo,
            resolveJunitTestMethod(
                arrayOf(noArg, withInfo),
                "render",
                MethodType.methodType(Pair::class.java, TestInfo::class.java),
            ),
        )
    }

    @Test
    fun `stack inference includes the executing overload signature`() {
        val host = GoldIdentitySignatureHost()
        val noArg = host.render()
        val withInfo =
            host.render(
                fakeTestInfo("render", host.javaClass, host.javaClass.getDeclaredMethod("render"))
            )
        assertEquals(GoldIdentitySignatureHost::class.java.name, noArg.first)
        assertEquals("render", noArg.second)
        assertEquals("render(org.junit.jupiter.api.TestInfo)", withInfo.second)
    }

    @Test
    fun `TestInfo identity includes the method signature`(testInfo: TestInfo) {
        val fromStack = inferTestIdentity()
        val fromInfo = identityFromTestInfo(testInfo)
        assertEquals(ScreenshotGoldIdentityTest::class.java.name, fromStack.first)
        assertEquals(
            "TestInfo identity includes the method signature(org.junit.jupiter.api.TestInfo)",
            fromStack.second,
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
    fun `inherited tests from a concrete base fail closed without TestInfo`() {
        val frame =
            StackTraceElement(
                GoldIdentityConcreteBase::class.java.name,
                "inheritedFromConcrete",
                "GoldIdentityConcreteBase.kt",
                1,
            )
        val error = assertFailsWith<IllegalStateException> { testIdentityFromFrame(frame) }
        assertTrue(error.message!!.contains("TestInfo"), error.message)
        assertTrue(error.message!!.contains("concrete"), error.message)
        val fromA =
            assertFailsWith<IllegalStateException> {
                GoldIdentityConcreteChildA().inheritedFromConcrete()
            }
        val fromB =
            assertFailsWith<IllegalStateException> {
                GoldIdentityConcreteChildB().inheritedFromConcrete()
            }
        assertTrue(fromA.message!!.contains("TestInfo"), fromA.message)
        assertTrue(fromB.message!!.contains("TestInfo"), fromB.message)
    }

    @Test
    fun `TestInfo keys inherited tests by the concrete class`() {
        val method = GoldIdentityInheritedBase::class.java.getDeclaredMethod("inheritedProbe")
        val info =
            fakeTestInfo("inheritedProbe()", GoldIdentityInheritedConcrete::class.java, method)
        val identity = identityFromTestInfo(info)
        assertEquals(GoldIdentityInheritedConcrete::class.java.name, identity.first)
        assertEquals("inheritedProbe", identity.second)
        val fromConcreteBase =
            identityFromTestInfo(
                fakeTestInfo(
                    "inheritedFromConcrete()",
                    GoldIdentityConcreteChildA::class.java,
                    GoldIdentityConcreteBase::class.java.getDeclaredMethod("inheritedFromConcrete"),
                )
            )
        assertEquals(GoldIdentityConcreteChildA::class.java.name, fromConcreteBase.first)
        assertEquals("inheritedFromConcrete", fromConcreteBase.second)
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
    @Test fun render(): Pair<String, String> = inferTestIdentity()

    @Test fun render(ignored: TestInfo): Pair<String, String> = inferTestIdentity()
}

internal abstract class GoldIdentityInheritedBase {
    abstract val inheritedGoldAnchor: String

    @Test fun inheritedProbe(): Pair<String, String> = inferTestIdentity()
}

@Disabled("reflective fixture for inherited gold identity")
internal class GoldIdentityInheritedConcrete : GoldIdentityInheritedBase() {
    override val inheritedGoldAnchor: String = "concrete"
}

internal interface GoldIdentityInheritedInterface {
    @Test fun inheritedDefault(): Pair<String, String> = inferTestIdentity()
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
    @Test fun inheritedFromConcrete(): Pair<String, String> = inferTestIdentity()
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

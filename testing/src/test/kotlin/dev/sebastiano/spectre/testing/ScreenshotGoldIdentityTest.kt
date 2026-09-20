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
    fun `TestInfo keys inherited tests by the concrete class`() {
        val method = GoldIdentityInheritedBase::class.java.getDeclaredMethod("inheritedProbe")
        val info =
            fakeTestInfo("inheritedProbe()", GoldIdentityInheritedConcrete::class.java, method)
        val identity = identityFromTestInfo(info)
        assertEquals(GoldIdentityInheritedConcrete::class.java.name, identity.first)
        assertEquals("inheritedProbe", identity.second)
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

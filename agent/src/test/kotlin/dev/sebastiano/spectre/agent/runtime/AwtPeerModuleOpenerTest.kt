package dev.sebastiano.spectre.agent.runtime

import java.lang.instrument.ClassDefinition
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test for [AwtPeerModuleOpener]: with a stub [Instrumentation] that records
 * [Instrumentation.redefineModule] calls, verify we request opens for the AWT peer packages to the
 * Spectre core consumer module.
 */
class AwtPeerModuleOpenerTest {

    @Test
    fun `openFor requests AWT peer package opens for the consumer module`() {
        val desktop = ModuleLayer.boot().findModule("java.desktop").orElse(null)
        // Skip on exotic JDKs without java.desktop (should not happen on Spectre's matrix).
        if (desktop == null) return

        val instrumentation = RecordingInstrumentation()
        val loader = AwtPeerModuleOpenerTest::class.java.classLoader
        AwtPeerModuleOpener.openFor(loader, instrumentation)

        assertTrue(
            instrumentation.redefineCalls.isNotEmpty(),
            "expected Instrumentation.redefineModule to be invoked",
        )
        val opens = instrumentation.redefineCalls.single().extraOpens
        // Platform-present packages only (e.g. no sun.awt.windows on macOS).
        assertTrue(opens.containsKey("sun.awt"), "must open sun.awt; got $opens")
        assertTrue(opens.containsKey("java.awt"), "must open java.awt; got $opens")
        // lwawt is present on macOS JDKs; if missing, do not fail the unit test.
        val consumers = opens.getValue("sun.awt")
        assertTrue(consumers.isNotEmpty(), "open must name at least one consumer module")
        assertTrue(
            opens.keys.none { it == "sun.awt.windows" && !desktop.packages.contains(it) },
            "must not request packages absent from java.desktop",
        )
    }

    private data class RedefineCall(val module: Module, val extraOpens: Map<String, Set<Module>>)

    /**
     * Minimal [Instrumentation] that records [redefineModule] arguments. All other methods throw so
     * accidental use fails loudly.
     */
    private class RecordingInstrumentation : Instrumentation {
        val redefineCalls = mutableListOf<RedefineCall>()

        override fun redefineModule(
            module: Module,
            extraReads: Set<Module>,
            extraExports: Map<String, Set<Module>>,
            extraOpens: Map<String, Set<Module>>,
            extraUses: Set<Class<*>>,
            extraProvides: Map<Class<*>, List<Class<*>>>,
        ) {
            redefineCalls += RedefineCall(module, extraOpens)
        }

        override fun addTransformer(transformer: ClassFileTransformer) = unsupported()

        override fun addTransformer(transformer: ClassFileTransformer, canRetransform: Boolean) =
            unsupported()

        override fun removeTransformer(transformer: ClassFileTransformer): Boolean = unsupported()

        override fun isRetransformClassesSupported(): Boolean = unsupported()

        override fun retransformClasses(vararg classes: Class<*>?) = unsupported()

        override fun isRedefineClassesSupported(): Boolean = unsupported()

        override fun redefineClasses(vararg definitions: ClassDefinition?) = unsupported()

        override fun isModifiableClass(theClass: Class<*>?): Boolean = unsupported()

        override fun getAllLoadedClasses(): Array<Class<*>> =
            arrayOf(
                // Pretend Spectre core is already loaded so collectConsumerModules finds it.
                Class.forName("dev.sebastiano.spectre.core.ComposeAutomator")
            )

        override fun getInitiatedClasses(loader: ClassLoader?): Array<Class<*>> = emptyArray()

        override fun getObjectSize(objectToSize: Any?): Long = unsupported()

        override fun appendToBootstrapClassLoaderSearch(jarfile: JarFile?) = unsupported()

        override fun appendToSystemClassLoaderSearch(jarfile: JarFile?) = unsupported()

        override fun isNativeMethodPrefixSupported(): Boolean = unsupported()

        override fun setNativeMethodPrefix(transformer: ClassFileTransformer?, prefix: String?) =
            unsupported()

        override fun isModifiableModule(module: Module?): Boolean = true

        private fun unsupported(): Nothing =
            error("RecordingInstrumentation only implements redefineModule/isModifiableModule")
    }
}

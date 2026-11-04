package dev.sebastiano.spectre.agent.runtime

import java.lang.instrument.ClassDefinition
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation

/**
 * Stub [Instrumentation] that returns a pre-baked array from [getAllLoadedClasses] and throws
 * [UnsupportedOperationException] from every other method.
 *
 * Used to keep `collectCandidateClassLoaders` exercised without booting a real agent or
 * `ByteBuddyAgent`. Each test supplies the array it needs.
 */
internal class StubInstrumentation(private val loadedClasses: Array<Class<*>>) : Instrumentation {
    override fun getAllLoadedClasses(): Array<Class<*>> = loadedClasses

    override fun addTransformer(transformer: ClassFileTransformer?): Unit = unsupported()

    override fun addTransformer(transformer: ClassFileTransformer?, canRetransform: Boolean): Unit =
        unsupported()

    override fun removeTransformer(transformer: ClassFileTransformer?): Boolean = unsupported()

    override fun isRetransformClassesSupported(): Boolean = false

    override fun retransformClasses(vararg classes: Class<*>?): Unit = unsupported()

    override fun isRedefineClassesSupported(): Boolean = false

    override fun redefineClasses(vararg definitions: ClassDefinition?): Unit = unsupported()

    override fun isModifiableClass(theClass: Class<*>?): Boolean = false

    override fun getInitiatedClasses(loader: ClassLoader?): Array<Class<*>> = unsupported()

    override fun getObjectSize(objectToSize: Any?): Long = unsupported()

    override fun appendToBootstrapClassLoaderSearch(jarfile: java.util.jar.JarFile?): Unit =
        unsupported()

    override fun appendToSystemClassLoaderSearch(jarfile: java.util.jar.JarFile?): Unit =
        unsupported()

    override fun isNativeMethodPrefixSupported(): Boolean = false

    override fun setNativeMethodPrefix(transformer: ClassFileTransformer?, prefix: String?): Unit =
        unsupported()

    override fun redefineModule(
        module: Module?,
        extraReads: MutableSet<Module>?,
        extraExports: MutableMap<String, MutableSet<Module>>?,
        extraOpens: MutableMap<String, MutableSet<Module>>?,
        extraUses: MutableSet<Class<*>>?,
        extraProvides: MutableMap<Class<*>, MutableList<Class<*>>>?,
    ): Unit = unsupported()

    override fun isModifiableModule(module: Module?): Boolean = false

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException(
            "StubInstrumentation only implements getAllLoadedClasses()"
        )
}

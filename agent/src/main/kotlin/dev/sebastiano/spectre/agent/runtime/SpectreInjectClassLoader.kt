package dev.sebastiano.spectre.agent.runtime

import java.net.URL
import java.net.URLClassLoader

/**
 * Child-first classloader for the #209 inject payload.
 *
 * Loads Spectre core, inject marker, relocated kotlinx, and other bundled non-Compose deps from the
 * inject jar first so they never collide with IDE-shipped kotlinx. Everything else (Compose, Kotlin
 * stdlib, JDK) delegates to [parent] — the target's Compose-capable loader.
 */
internal class SpectreInjectClassLoader(urls: Array<URL>, parent: ClassLoader) :
    URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loaded ->
                if (resolve) resolveClass(loaded)
                return loaded
            }
            if (loadFromInjectJar(name)) {
                try {
                    val fromInject = findClass(name)
                    if (resolve) resolveClass(fromInject)
                    return fromInject
                } catch (_: ClassNotFoundException) {
                    // Fall through to parent.
                }
            }
            val fromParent = parent.loadClass(name)
            if (resolve) resolveClass(fromParent)
            return fromParent
        }
    }

    internal companion object {
        /**
         * Packages owned by the inject jar. Must stay in sync with `:agent-inject-runtime` shadow
         * relocation prefixes.
         */
        fun loadFromInjectJar(name: String): Boolean =
            name.startsWith("dev.sebastiano.spectre.") ||
                name.startsWith("androidx.tracing.") ||
                name.startsWith("androidx.collection.") ||
                name.startsWith("androidx.annotation.") ||
                name.startsWith("okio.") ||
                name.startsWith("com.squareup.wire.") ||
                name.startsWith("com.squareup.okio.")
    }
}

package com.intellij.ide.plugins.cl

import java.net.URL
import java.net.URLClassLoader

/**
 * Test-only stand-in for IntelliJ's `com.intellij.ide.plugins.cl.PluginClassLoader`.
 *
 * The agent recognises "a plugin loader" by string-matching the loader's runtime class FQN against
 * `com.intellij.ide.plugins.cl.PluginClassLoader`. Declaring a class with that exact FQN lets the
 * agent's unit tests and the IntelliJ-shaped attach fixture (`LazyComposeFixtureMain.kt`) exercise
 * that matching without depending on the IntelliJ Platform. Production agents never see this class.
 *
 * Like IntelliJ's loader, it takes JDK and Kotlin standard library classes from the platform (its
 * parent) first, and loads every other class from its own classpath before asking its parent.
 * Sharing the Kotlin standard library matters: the agent passes `kotlin.coroutines.Continuation`
 * objects into the Spectre classes it injects behind this loader.
 */
public open class PluginClassLoader(urls: Array<URL> = emptyArray(), parent: ClassLoader? = null) :
    URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: loadOwnOrParent(name)
            if (resolve) resolveClass(loaded)
            loaded
        }

    private fun loadOwnOrParent(name: String): Class<*> =
        if (PLATFORM_PREFIXES.any(name::startsWith)) {
            runCatching { super.loadClass(name, false) }.getOrElse { findClass(name) }
        } else {
            runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
        }

    private companion object {
        val PLATFORM_PREFIXES = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.")
    }
}

@file:Suppress("PackageDirectoryMismatch")

package com.intellij.ide.plugins.cl

/**
 * Test-only stand-in for IntelliJ's `com.intellij.ide.plugins.cl.PluginClassLoader`.
 *
 * The agent's classloader-disambiguation rule (`selectClassLoader`) detects "the plugin loader" by
 * string-matching the loader's runtime class FQN against
 * `com.intellij.ide.plugins.cl.PluginClassLoader`. To exercise that branch in unit tests without
 * depending on the IntelliJ Platform, we declare a class with that exact FQN here in the test
 * sources. The agent never sees this class at runtime in production — it's purely for testing the
 * matching logic.
 */
internal open class PluginClassLoader(parent: ClassLoader? = null) : ClassLoader(parent)

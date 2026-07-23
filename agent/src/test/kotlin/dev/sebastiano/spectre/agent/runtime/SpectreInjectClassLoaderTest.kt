package dev.sebastiano.spectre.agent.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpectreInjectClassLoaderTest {

    @Test
    fun `loadFromInjectJar owns spectre and relocated packages but not Compose`() {
        assertTrue(
            SpectreInjectClassLoader.loadFromInjectJar(
                "dev.sebastiano.spectre.core.ComposeAutomator"
            )
        )
        assertTrue(
            SpectreInjectClassLoader.loadFromInjectJar(
                "dev.sebastiano.spectre.inject.relocated.kotlinx.coroutines.Dispatchers"
            )
        )
        assertTrue(SpectreInjectClassLoader.loadFromInjectJar("androidx.tracing.Trace"))
        assertEquals(
            false,
            SpectreInjectClassLoader.loadFromInjectJar("androidx.compose.ui.awt.ComposeWindow"),
        )
        assertEquals(false, SpectreInjectClassLoader.loadFromInjectJar("java.lang.String"))
    }

    @Test
    fun `inject classloader prefers jar for spectre packages and parent for others`() {
        val injectJar = writeJarWithTextResource()
        val parent = object : ClassLoader(ClassLoader.getSystemClassLoader()) {}
        val loader = SpectreInjectClassLoader(arrayOf(injectJar.toUri().toURL()), parent)

        // Resource from inject jar is visible via findResource (URLClassLoader).
        val resource = loader.findResource("inject-marker.txt")
        assertTrue(resource != null, "expected inject-marker.txt on inject jar")

        // JDK class always from parent hierarchy.
        val stringClass = loader.loadClass("java.lang.String")
        assertSame(java.lang.String::class.java, stringClass)
    }

    private fun writeJarWithTextResource(): Path {
        val path = Files.createTempFile("spectre-inject-test-", ".jar")
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(ZipEntry("inject-marker.txt"))
            jar.write("ok".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
        path.toFile().deleteOnExit()
        return path
    }
}

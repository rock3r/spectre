package dev.sebastiano.spectre.agent.runtime

import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InjectRuntimeLocatorTest {

    @Test
    fun `extractInjectJar returns null when resource missing`() {
        val emptyLoader = URLClassLoader(emptyArray(), null)
        assertNull(InjectRuntimeLocator.extractInjectJar(emptyLoader))
    }

    @Test
    fun `extractInjectJar copies nested resource to a readable temp file`() {
        val nested = Files.createTempFile("nested-inject-", ".jar")
        JarOutputStream(Files.newOutputStream(nested)).use { jar ->
            jar.putNextEntry(ZipEntry("marker.txt"))
            jar.write("inject-payload".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }

        val carrier = Files.createTempFile("agent-runtime-carrier-", ".jar")
        JarOutputStream(Files.newOutputStream(carrier)).use { jar ->
            jar.putNextEntry(ZipEntry(InjectRuntimeLocator.INJECT_RUNTIME_RESOURCE))
            jar.write(Files.readAllBytes(nested))
            jar.closeEntry()
        }

        val loader = URLClassLoader(arrayOf(carrier.toUri().toURL()), null)
        val extracted = InjectRuntimeLocator.extractInjectJar(loader)
        assertNotNull(extracted)
        assertTrue(Files.isRegularFile(extracted))
        assertTrue(Files.size(extracted) > 0L)
    }
}

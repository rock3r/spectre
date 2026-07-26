@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentJarResolutionTest {
    @Test
    fun `finds published agent runtime jar on classpath`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val runtimeJar = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0.jar"))
        val apiJar = Files.createFile(dir.resolve("spectre-agent-0.2.0.jar"))
        val classPath = listOf(apiJar, runtimeJar).joinToString(File.pathSeparator)

        assertEquals(runtimeJar, AgentJarResolution.findRuntimeJarOnClasspath(classPath))
    }

    @Test
    fun `skips auxiliary agent runtime jars on classpath`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val sourcesJar = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0-sources.jar"))
        val javadocJar = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0-javadoc.jar"))
        val runtimeJar = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0.jar"))
        val classPath = listOf(sourcesJar, javadocJar, runtimeJar).joinToString(File.pathSeparator)

        assertEquals(runtimeJar, AgentJarResolution.findRuntimeJarOnClasspath(classPath))
    }

    @Test
    fun `skips auxiliary agent runtime jars in directory fallback`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        Files.createFile(dir.resolve("agent-runtime-0.2.0-sources.jar"))
        Files.createFile(dir.resolve("agent-runtime-0.2.0-javadoc.jar"))
        val runtimeJar = Files.createFile(dir.resolve("agent-runtime-0.2.0.jar"))

        assertEquals(runtimeJar, AgentJarResolution.findRuntimeJarInDirectory(dir))
    }

    @Test
    fun `ignores non-runtime agent jar on classpath`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val apiJar = Files.createFile(dir.resolve("spectre-agent-0.2.0.jar"))

        assertEquals(null, AgentJarResolution.findRuntimeJarOnClasspath(apiJar.toString()))
    }

    @Test
    fun `fails closed when multiple runtime jars are on the classpath`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val older = Files.createFile(dir.resolve("spectre-agent-runtime-0.1.0.jar"))
        val newer = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0.jar"))

        // Reverse order of the two candidates so a "first on classpath" policy would
        // pick differently; fail-closed must name both regardless of order.
        val classPathA = listOf(older, newer).joinToString(File.pathSeparator)
        val classPathB = listOf(newer, older).joinToString(File.pathSeparator)

        val errorA =
            assertFailsWith<AmbiguousAgentRuntimeJarException> {
                AgentJarResolution.findRuntimeJarOnClasspath(classPathA)
            }
        val errorB =
            assertFailsWith<AmbiguousAgentRuntimeJarException> {
                AgentJarResolution.findRuntimeJarOnClasspath(classPathB)
            }

        assertSameCandidateSet(setOf(older, newer), errorA.candidates)
        assertSameCandidateSet(setOf(older, newer), errorB.candidates)
        assertEquals(errorA.candidates, errorB.candidates)
        assertTrue(errorA.message!!.contains(older.fileName.toString()))
        assertTrue(errorA.message!!.contains(newer.fileName.toString()))
        assertTrue(
            errorA.message!!.contains("AttachOptions") || errorA.message!!.contains("runtimeJar")
        )
    }

    @Test
    fun `treats duplicate classpath entries of the same jar as a single candidate`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val runtimeJar = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0.jar"))
        val classPath = listOf(runtimeJar, runtimeJar).joinToString(File.pathSeparator)

        assertEquals(
            runtimeJar.toAbsolutePath().normalize(),
            AgentJarResolution.findRuntimeJarOnClasspath(classPath),
        )
    }

    @Test
    fun `treats symlink and real path of the same jar as a single candidate`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val runtimeJar = Files.createFile(dir.resolve("spectre-agent-runtime-0.2.0.jar"))
        val link = dir.resolve("spectre-agent-runtime-link.jar")
        try {
            Files.createSymbolicLink(link, runtimeJar.fileName)
        } catch (_: UnsupportedOperationException) {
            return // platform without symlink support
        } catch (_: java.nio.file.FileSystemException) {
            return // sandbox / privilege may refuse symlink creation
        }

        val classPath = listOf(runtimeJar, link).joinToString(File.pathSeparator)
        val resolved = AgentJarResolution.findRuntimeJarOnClasspath(classPath)
        assertTrue(Files.isSameFile(runtimeJar, resolved!!))
    }

    @Test
    fun `preserves original classpath path that uses symlink parent and dot-dot`() {
        val root = Files.createTempDirectory("spectre-agent-jar-resolution")
        val realDir = Files.createDirectory(root.resolve("real"))
        val runtimeJar = Files.createFile(realDir.resolve("spectre-agent-runtime-0.2.0.jar"))
        val linkDir = root.resolve("link")
        try {
            Files.createSymbolicLink(linkDir, realDir.fileName)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }

        // link/../real/jar is a valid on-disk path via the symlink parent, but lexical
        // Path.normalize() would collapse it incorrectly on some layouts.
        val symlinkSensitive =
            linkDir.resolve("..").resolve("real").resolve(runtimeJar.fileName.toString())
        assertTrue(Files.isRegularFile(symlinkSensitive))

        val resolved = AgentJarResolution.findRuntimeJarOnClasspath(symlinkSensitive.toString())
        assertEquals(symlinkSensitive, resolved)
        assertTrue(Files.isSameFile(runtimeJar, resolved!!))
    }

    @Test
    fun `fails closed when multiple runtime jars are in a directory`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val first = Files.createFile(dir.resolve("agent-runtime-0.1.0.jar"))
        val second = Files.createFile(dir.resolve("agent-runtime-0.2.0.jar"))

        val error =
            assertFailsWith<AmbiguousAgentRuntimeJarException> {
                AgentJarResolution.findRuntimeJarInDirectory(dir)
            }

        assertSameCandidateSet(setOf(first, second), error.candidates)
        assertTrue(error.message!!.contains(first.fileName.toString()))
        assertTrue(error.message!!.contains(second.fileName.toString()))
    }

    @Test
    fun `default UDS path uses a per-attach private directory`() {
        val path = AttachOptions.defaultUdsPath(targetPid = 1234)

        assertEquals("agent.sock", path.fileName.toString())
        assertTrue(path.parent.fileName.toString().startsWith("sp-a-1234-"))
    }

    private fun assertSameCandidateSet(expected: Set<Path>, actual: List<Path>) {
        assertEquals(
            expected.map { it.toAbsolutePath().normalize() }.toSet(),
            actual.map { it.toAbsolutePath().normalize() }.toSet(),
        )
    }
}

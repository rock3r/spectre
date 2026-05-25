@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `ignores non-runtime agent jar on classpath`() {
        val dir = Files.createTempDirectory("spectre-agent-jar-resolution")
        val apiJar = Files.createFile(dir.resolve("spectre-agent-0.2.0.jar"))

        assertEquals(null, AgentJarResolution.findRuntimeJarOnClasspath(apiJar.toString()))
    }

    @Test
    fun `default UDS path uses a per-attach private directory`() {
        val path = AttachOptions.defaultUdsPath(targetPid = 1234)

        assertEquals("agent.sock", path.fileName.toString())
        assertTrue(path.parent.fileName.toString().startsWith("sp-a-1234-"))
    }
}

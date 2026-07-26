@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentJarResolveTest {
    @Test
    fun `detects spectre source checkout by monorepo markers`() {
        val root = Files.createTempDirectory("spectre-checkout")
        touchCheckoutMarkers(root)

        assertTrue(AgentJarResolution.isSpectreSourceCheckout(root))
        assertEquals(root, AgentJarResolution.findSpectreSourceCheckoutRoot(root))
        assertEquals(root, AgentJarResolution.findSpectreSourceCheckoutRoot(root.resolve("agent")))
    }

    @Test
    fun `generic directory is not a spectre source checkout`() {
        val generic = Files.createTempDirectory("not-spectre")
        Files.createDirectories(generic.resolve("agent-runtime/build/libs"))
        Files.createFile(generic.resolve("agent-runtime/build/libs/agent-runtime-0.2.0.jar"))

        assertFalse(AgentJarResolution.isSpectreSourceCheckout(generic))
        assertNull(AgentJarResolution.findSpectreSourceCheckoutRoot(generic))
    }

    @Test
    fun `repo fallback finds jar only under a spectre checkout`() {
        val root = Files.createTempDirectory("spectre-checkout")
        touchCheckoutMarkers(root)
        val libs = Files.createDirectories(root.resolve("agent-runtime/build/libs"))
        val jar = Files.createFile(libs.resolve("agent-runtime-0.2.0.jar"))

        assertEquals(jar, AgentJarResolution.findRuntimeJarInRepoFallback(root))
        assertEquals(jar, AgentJarResolution.findRuntimeJarInRepoFallback(root.resolve("cli")))
    }

    @Test
    fun `repo fallback is disabled for a generic cwd even with matching layout path`() {
        val generic = Files.createTempDirectory("consumer-app")
        val libs = Files.createDirectories(generic.resolve("agent-runtime/build/libs"))
        Files.createFile(libs.resolve("agent-runtime-0.2.0.jar"))

        assertNull(AgentJarResolution.findRuntimeJarInRepoFallback(generic))
    }

    @Test
    fun `resolve prefers explicit path then sysprop then classpath before repo fallback`() {
        val root = Files.createTempDirectory("spectre-checkout")
        touchCheckoutMarkers(root)
        val libs = Files.createDirectories(root.resolve("agent-runtime/build/libs"))
        val fallbackJar = Files.createFile(libs.resolve("agent-runtime-0.2.0.jar"))

        val explicit = Files.createTempFile("explicit-runtime", ".jar")
        val viaProp = Files.createTempFile("prop-runtime", ".jar")
        val viaClasspath =
            Files.createTempDirectory("cp").resolve("spectre-agent-runtime-0.2.0.jar")
        Files.createFile(viaClasspath)

        assertEquals(
            explicit,
            AgentJarResolution.resolveRuntimeJar(
                agentJarPath = explicit,
                runtimeJarSystemProperty = viaProp.toString(),
                classPath = viaClasspath.toString(),
                cwd = root,
            ),
        )
        assertEquals(
            viaProp,
            AgentJarResolution.resolveRuntimeJar(
                agentJarPath = null,
                runtimeJarSystemProperty = viaProp.toString(),
                classPath = viaClasspath.toString(),
                cwd = root,
            ),
        )
        assertEquals(
            viaClasspath,
            AgentJarResolution.resolveRuntimeJar(
                agentJarPath = null,
                runtimeJarSystemProperty = null,
                classPath = viaClasspath.toString(),
                cwd = root,
            ),
        )
        assertEquals(
            fallbackJar,
            AgentJarResolution.resolveRuntimeJar(
                agentJarPath = null,
                runtimeJarSystemProperty = null,
                classPath = "",
                cwd = root,
            ),
        )
    }

    @Test
    fun `resolve does not use generic cwd fallback when higher sources miss`() {
        val generic = Files.createTempDirectory("consumer-app")
        val libs = Files.createDirectories(generic.resolve("agent-runtime/build/libs"))
        Files.createFile(libs.resolve("agent-runtime-0.2.0.jar"))

        assertFailsWith<AgentJarNotFoundException> {
            AgentJarResolution.resolveRuntimeJar(
                agentJarPath = null,
                runtimeJarSystemProperty = null,
                classPath = "",
                cwd = generic,
            )
        }
    }

    @Test
    fun `layout alone without spectre identity is not a checkout`() {
        val root = Files.createTempDirectory("lookalike")
        Files.writeString(root.resolve("settings.gradle.kts"), """rootProject.name = "Other"""")
        Files.createDirectories(root.resolve("agent"))
        Files.createFile(root.resolve("agent/build.gradle.kts"))
        Files.createDirectories(root.resolve("agent-runtime"))
        Files.createFile(root.resolve("agent-runtime/build.gradle.kts"))
        val libs = Files.createDirectories(root.resolve("agent-runtime/build/libs"))
        Files.createFile(libs.resolve("agent-runtime-0.2.0.jar"))

        assertFalse(AgentJarResolution.isSpectreSourceCheckout(root))
        assertNull(AgentJarResolution.findRuntimeJarInRepoFallback(root))
    }

    @Test
    fun `commented spectre identity markers do not enable checkout fallback`() {
        val root = Files.createTempDirectory("commented-identity")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            """
            // rootProject.name = "Spectre"
            rootProject.name = "Other"
            """
                .trimIndent(),
        )
        Files.writeString(
            root.resolve("gradle.properties"),
            """
            # GROUP=dev.sebastiano.spectre
            GROUP=com.example.other
            """
                .trimIndent(),
        )
        Files.createDirectories(root.resolve("agent"))
        Files.createFile(root.resolve("agent/build.gradle.kts"))
        Files.createDirectories(root.resolve("agent-runtime"))
        Files.createFile(root.resolve("agent-runtime/build.gradle.kts"))
        val libs = Files.createDirectories(root.resolve("agent-runtime/build/libs"))
        Files.createFile(libs.resolve("agent-runtime-0.2.0.jar"))

        assertFalse(AgentJarResolution.isSpectreSourceCheckout(root))
        assertNull(AgentJarResolution.findRuntimeJarInRepoFallback(root))
    }

    private fun touchCheckoutMarkers(root: Path) {
        Files.writeString(root.resolve("settings.gradle.kts"), """rootProject.name = "Spectre"""")
        Files.createDirectories(root.resolve("agent"))
        Files.createFile(root.resolve("agent/build.gradle.kts"))
        Files.createDirectories(root.resolve("agent-runtime"))
        Files.createFile(root.resolve("agent-runtime/build.gradle.kts"))
    }
}

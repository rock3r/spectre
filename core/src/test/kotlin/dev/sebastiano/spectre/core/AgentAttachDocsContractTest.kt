package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins user-facing agent-attach guidance against the two-path bootstrap that actually ships:
 * preinstalled `spectre-core` is preferred, but attach still works via nested inject-runtime when
 * the target only has Compose.
 */
class AgentAttachDocsContractTest {

    @Test
    fun `agent skill does not require preinstalled spectre-core`() {
        val skill = read("skills/spectre/references/agent.md")
        assertFalse(
            skill.contains("must already have"),
            "Agent skill still claims the target must already have spectre-core on its classpath",
        )
        assertContains(skill, "inject-runtime.jar")
        assertContains(skill, "preinstalled")
        assertContains(skill, "ComposeAutomator")
    }

    @Test
    fun `user guide and install docs describe inject as the no-core path`() {
        val installation = read("docs/guide/installation.md")
        val troubleshooting = read("docs/guide/troubleshooting.md")
        val readme = read("README.md")

        assertContains(installation, "inject-runtime.jar")
        assertContains(troubleshooting, "inject")
        assertFalse(
            troubleshooting.contains("Ensure the app depends on\n`spectre-core`"),
            "Troubleshooting still treats missing spectre-core as the only AGENT_BOOTSTRAP cause",
        )
        assertFalse(
            readme.contains("Spectre-instrumented JVM"),
            "README still implies attach only works against a pre-instrumented target",
        )
        assertContains(readme, "inject")
    }

    @Test
    fun `published spectre-ui-automation skill matches current typing and wait APIs`() {
        val packaged = read("skill/SKILL.md")
        assertFalse(
            packaged.contains("clipboard-based"),
            "Packaged skill still describes typeText as clipboard-based",
        )
        assertContains(packaged, "pasteText")
        assertFalse(
            packaged.contains("EDT-safe"),
            "Packaged skill still calls waitForNode EDT-safe; all wait helpers reject the EDT",
        )
        assertContains(packaged, "runSpectreTest")
    }

    @Test
    fun `spectre skill evals require runSpectreTest not runBlocking`() {
        val evals = read("skills/spectre/evals/evals.json")
        assertContains(evals, "runSpectreTest")
        assertFalse(
            evals.contains("runBlocking { ... }"),
            "Skill evals still require runBlocking instead of runSpectreTest",
        )
        assertFalse(
            evals.contains("dev.sebastiano.spectre.core.synthetic"),
            "Skill evals still claim a non-existent synthetic extension import",
        )
    }

    private fun read(relative: String): String = root.resolve(relative).readText()

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(haystack.contains(needle), "Expected '$needle' in guidance, but it was missing")
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}

package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class DocsGuidanceContractTest {

    @Test
    fun `docs pin downstream setup troubleshooting guidance`() {
        val docs =
            listOf(
                    "docs/guide/getting-started.md",
                    "docs/guide/junit.md",
                    "docs/guide/troubleshooting.md",
                    "docs/guide/selectors.md",
                    "docs/guide/interactions.md",
                    "docs/guide/input-coordination.md",
                    "docs/guide/agent.md",
                    "docs/SECURITY.md",
                    "skills/spectre/SKILL.md",
                    "skills/spectre/references/input-coordination.md",
                )
                .joinToString(separator = "\n") { root.resolve(it).readText() }

        assertContains(docs, "apple.awt.UIElement=true")
        assertContains(docs, "pasteText")
        assertContains(docs, "java.awt.headless")
        assertContains(docs, "fun mySpec(): Unit = runSpectreTest")
        assertContains(docs, "printTree()` returns an empty string")
        assertContains(docs, "No Component provided")
        assertContains(docs, "RobotDriver.synthetic(rootWindow =")
        assertContains(docs, "ExperimentalSpectreInputCoordinationApi")
        assertContains(docs, "spectre-input-coordinator-server")
        assertContains(docs, "InputIsolationConfig.perTest()")
        assertContains(docs, "unsafeTakeover=true")
        assertContains(docs, "best-effort attempt")
        assertContains(docs, "does not rewrite the Windows ACL")
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(haystack.contains(needle), "Expected docs/skill guidance to contain: $needle")
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}

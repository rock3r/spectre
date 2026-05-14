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
                    "skills/spectre/SKILL.md",
                )
                .joinToString(separator = "\n") { root.resolve(it).readText() }

        assertContains(docs, "apple.awt.UIElement=true")
        assertContains(docs, "pasteText")
        assertContains(docs, "java.awt.headless")
        assertContains(docs, "fun mySpec(): Unit = runBlocking")
        assertContains(docs, "printTree()` returns an empty string")
        assertContains(docs, "No Component provided")
        assertContains(docs, "RobotDriver.synthetic(rootWindow =")
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(haystack.contains(needle), "Expected docs/skill guidance to contain: $needle")
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}

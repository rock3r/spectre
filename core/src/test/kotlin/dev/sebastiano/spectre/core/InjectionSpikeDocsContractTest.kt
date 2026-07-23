package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pins #209 spike docs required by acceptance criteria (audit + practicalities + decision). */
class InjectionSpikeDocsContractTest {

    @Test
    fun `spike docs cover audit practicalities decision and user recipe`() {
        for (name in
            listOf(
                "api-audit.md",
                "practicalities.md",
                "decision.md",
                "user-recipe.md",
                "inject-packaging.md",
            )) {
            val path = root.resolve("docs/spikes/209-injection/$name")
            assertTrue(path.exists(), "missing $path")
            assertTrue(path.readText().isNotBlank())
        }
        val decision = root.resolve("docs/spikes/209-injection/decision.md").readText()
        assertTrue(decision.contains("Instrumented-only"))
        assertTrue(decision.contains("Read-only injection"))
        assertTrue(decision.contains("STABILITY"))
        val practicalities = root.resolve("docs/spikes/209-injection/practicalities.md").readText()
        assertTrue(practicalities.contains("EnableDynamicAgentLoading"))
        assertTrue(practicalities.contains("adapter"))
        assertTrue(practicalities.contains("classloader") || practicalities.contains("Metaspace"))
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}

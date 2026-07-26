package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural contract for the #322 overlay multi-version adapter policy.
 *
 * Pins the spike decision so CI fails if the policy doc is removed or loses the 1.0 choice
 * (degrade-to-empty, no multi-version matrix).
 */
class OverlayAdapterPolicyContractTest {

    @Test
    fun `overlay adapter policy documents degrade-to-empty for 1_0`() {
        assertTrue(policyPath.exists(), "Missing #322 policy at $policyPath")
        val text = policyPath.readText()

        assertContains(text, "Degrade to empty")
        assertContains(text, "**No** for 1.0")
        assertContains(text, "OverlayLayerInspector")
        assertContains(text, "findOverlayLayerWindows")
        assertContains(text, "RecomposerInspector")
        assertContains(text, "**pinned** Compose Desktop")
        assertContains(text, "degrade-to-empty")
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(
            haystack.contains(needle),
            "Expected docs/spikes/209-injection/overlay-adapter-policy.md to contain: $needle",
        )
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
        val policyPath: Path = root.resolve("docs/spikes/209-injection/overlay-adapter-policy.md")
    }
}

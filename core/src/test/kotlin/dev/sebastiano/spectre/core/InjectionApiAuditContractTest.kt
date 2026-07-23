package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural contract for the #209 / #312 API-surface audit artifact.
 *
 * Pins the spike deliverable so CI fails if the audit is removed or stripped of the sections
 * required by the injection decision: separate read/input paths, public-vs-internal labels, and
 * input verbs that run on read-path geometry alone.
 */
class InjectionApiAuditContractTest {

    @Test
    fun `injection api audit documents read path input path and geometry-only verbs`() {
        assertTrue(auditPath.exists(), "Missing #209 API audit at $auditPath")
        val text = auditPath.readText()

        assertContains(text, "## 1. Read path")
        assertContains(text, "## 2. Input path")
        assertContains(text, "### 2.4 Input verbs that survive on read-path geometry alone")

        // Public-vs-internal vocabulary used in the audit tables.
        assertContains(text, "Public Compose")
        assertContains(text, "Compose internal")
        assertContains(text, "Public AWT")

        // Primary public read path called out by ARCHITECTURE spike constraint #1.
        assertContains(text, "semanticsOwners")
        assertContains(text, "ComposeWindow")
        assertContains(text, "ComposePanel")

        // Adapter-risk / internal popup path.
        assertContains(text, "OverlayLayerInspector")

        // Geometry-only Robot verbs that must remain listed for inject/read-only design.
        for (verb in
            listOf("click", "doubleClick", "longClick", "swipe", "scrollWheel", "typeText")) {
            assertContains(text, verb)
        }

        // Semantics-action path is explicitly *not* geometry-only.
        assertContains(text, "performSemanticsClick")
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(
            haystack.contains(needle),
            "Expected docs/spikes/209-injection/api-audit.md to contain: $needle",
        )
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
        val auditPath: Path = root.resolve("docs/spikes/209-injection/api-audit.md")
    }
}

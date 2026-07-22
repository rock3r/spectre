package dev.sebastiano.spectre.testing.contract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fail-closed gate for the multi-state capability matrix (#198).
 *
 * Every [CellState.Supported] cell must carry at least one [CapabilityEvidence] entry whose
 * [CapabilityEvidence.sourcePath] (and optional [CapabilityEvidence.workflowPath]) resolves to a
 * real file in the repository. This is the opposite of the silent-skip failure mode that let docs
 * claim Linux agent support while headless CI never executed the attach fixture.
 */
class CapabilityMatrixEvidenceTest {

    @Test
    fun `every supported cell has non-empty resolvable evidence`() {
        val supported = CapabilityMatrix.supportedCells()
        assertTrue(supported.isNotEmpty(), "Matrix must declare at least one Supported cell")

        val failures = mutableListOf<String>()
        for (cell in supported) {
            val label = "${cell.operation}/${cell.transport}/${cell.platform}"
            if (cell.evidence.isEmpty()) {
                failures += "$label: Supported but evidence is empty"
                continue
            }
            for (ev in cell.evidence) {
                val source = root.resolve(ev.sourcePath)
                if (!Files.isRegularFile(source)) {
                    failures +=
                        "$label evidence '${ev.id}': missing sourcePath ${ev.sourcePath} " +
                            "(resolved $source)"
                }
                val workflow = ev.workflowPath
                if (workflow != null) {
                    val wf = root.resolve(workflow)
                    if (!Files.isRegularFile(wf)) {
                        failures +=
                            "$label evidence '${ev.id}': missing workflowPath $workflow " +
                                "(resolved $wf)"
                    } else if (!workflowCoversEvidence(wf.toFile().readText(), ev)) {
                        failures +=
                            "$label evidence '${ev.id}': workflow $workflow does not reference " +
                                "the test class, module test task, or full ./gradlew check " +
                                "for ${ev.sourcePath}"
                    }
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Supported cells missing executable evidence:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * Ensures a cited workflow actually runs (or re-runs) the evidence test, not just that the YAML
     * file exists. Accepts: simple test class name, `:$module:test`, or a full `./gradlew check`
     * that includes the module.
     */
    private fun workflowCoversEvidence(
        workflowText: String,
        evidence: CapabilityEvidence,
    ): Boolean {
        val className = evidence.sourcePath.substringAfterLast('/').removeSuffix(".kt")
        if (className.isNotEmpty() && workflowText.contains(className)) return true

        val module = evidence.sourcePath.substringBefore('/')
        if (module.isNotEmpty() && workflowText.contains(":$module:test")) return true

        // Full check on CI includes all library modules' unit tests.
        if (workflowText.contains("./gradlew check") || workflowText.contains("gradlew check")) {
            return module in setOf("core", "testing", "server", "agent", "cli", "recording")
        }

        val hint = evidence.gradleTaskHint.orEmpty()
        if (hint.isNotEmpty()) {
            // Match distinctive tokens from the hint (e.g. :testing:test, AgentContractCorpusTest).
            val tokens = Regex(""":[\w-]+:\w+|[\w]+Test""").findAll(hint).map { it.value }.toList()
            if (tokens.any { it in workflowText }) return true
        }
        return false
    }

    @Test
    fun `unsupported-by-design cells carry a rationale`() {
        val bare =
            CapabilityMatrix.cells.filter {
                it.state == CellState.UnsupportedByDesign && it.rationale.isNullOrBlank()
            }
        assertTrue(
            bare.isEmpty(),
            "UnsupportedByDesign without rationale: " +
                bare.joinToString { "${it.operation}/${it.transport}/${it.platform}" },
        )
    }

    @Test
    fun `deliberate remote exclusions for idling and tracing are recorded`() {
        for (transport in listOf(AutomatorTransport.Http, AutomatorTransport.Agent)) {
            for (op in
                listOf(
                    AutomatorOperation.RegisterIdlingResource,
                    AutomatorOperation.WithTracing,
                    AutomatorOperation.WaitForIdle,
                )) {
                val cell =
                    CapabilityMatrix.cell(op, transport, PlatformPrerequisite.AnyJvm)
                        ?: error("Missing exclusion cell for $op on $transport")
                assertTrue(
                    cell.state == CellState.UnsupportedByDesign,
                    "Expected UnsupportedByDesign for $op/$transport, got ${cell.state}",
                )
            }
        }
    }

    @Test
    fun `published capability matrix guide pins contract-form decision and multi-state cells`() {
        val doc = root.resolve("docs/guide/capability-matrix.md").toFile().readText()
        assertTrue(doc.contains("contract-test corpus"), "Doc must state corpus contract form")
        assertTrue(doc.contains("Supported"), "Doc must describe Supported state")
        assertTrue(doc.contains("Unsupported by design"), "Doc must describe exclusions")
        assertTrue(doc.contains("Not yet CI-executed"), "Doc must describe not-yet state")
        assertTrue(doc.contains("CapabilityMatrix"), "Doc must point at the Kotlin source of truth")
        assertTrue(
            doc.contains("fail closed") || doc.contains("fail-closed"),
            "Doc must mention fail-closed evidence",
        )
    }

    private companion object {
        // Tests run with module dir as cwd (testing/), so repo root is parent.
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}

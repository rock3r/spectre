package dev.sebastiano.spectre.testing.contract

/**
 * Capability-matrix cells for [AutomatorOperation.FocusWindow] (#364).
 *
 * Kept out of [CapabilityMatrix]'s main object body so Detekt's LargeClass budget stays honest —
 * focus-window rows are a self-contained op family (agent wire, in-process validation, HTTP
 * exclusion).
 */
internal fun focusWindowCapabilityCells(
    agentLinuxXvfb: CapabilityEvidence,
    agentMacOs: CapabilityEvidence,
    agentAttachLegacyLinux: CapabilityEvidence,
    agentAttachLegacyMacOs: CapabilityEvidence,
): List<CapabilityCell> {
    val inProcessLinux =
        CapabilityEvidence(
            id = "in-process-focus-window-validation-linux",
            description =
                "sample-desktop NewInteractionsValidationTest exercises " +
                    "ComposeAutomator.focusWindow under Xvfb (fail-closed class " +
                    "list in validation-linux.yml)",
            sourcePath =
                "sample-desktop/src/validation/kotlin/dev/sebastiano/spectre/" +
                    "sample/validation/NewInteractionsValidationTest.kt",
            workflowPath = ".github/workflows/validation-linux.yml",
            gradleTaskHint =
                "./gradlew :sample-desktop:validationTest --tests " +
                    "\"*NewInteractionsValidationTest*\"",
        )
    return listOf(
        // Attach clients can raise the target window before keyboard input.
        // Evidence: AgentContractCorpus focus-window scenario + AgentAttachIntegrationTest.
        CapabilityCell(
            operation = AutomatorOperation.FocusWindow,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.LinuxXvfb,
            state = CellState.Supported,
            evidence = listOf(agentLinuxXvfb, agentAttachLegacyLinux),
        ),
        CapabilityCell(
            operation = AutomatorOperation.FocusWindow,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.MacOsDesktop,
            state = CellState.Supported,
            evidence = listOf(agentMacOs, agentAttachLegacyMacOs),
        ),
        // In-process focusWindow needs a live AWT window (toFront/requestFocus) — not AnyJvm.
        CapabilityCell(
            operation = AutomatorOperation.FocusWindow,
            transport = AutomatorTransport.InProcess,
            platform = PlatformPrerequisite.LinuxXvfb,
            state = CellState.Supported,
            evidence = listOf(inProcessLinux),
        ),
        CapabilityCell(
            operation = AutomatorOperation.FocusWindow,
            transport = AutomatorTransport.InProcess,
            platform = PlatformPrerequisite.MacOsDesktop,
            state = CellState.NotYetCiExecuted,
            rationale =
                "In-process focusWindow is display-backed; no fail-closed macOS " +
                    "sample-desktop validation workflow claims it yet (Linux Xvfb is Supported).",
        ),
        CapabilityCell(
            operation = AutomatorOperation.FocusWindow,
            transport = AutomatorTransport.Http,
            platform = PlatformPrerequisite.AnyJvm,
            state = CellState.UnsupportedByDesign,
            rationale =
                "HTTP focusWindow is out of scope for #364 (AttachedAutomator wire only). " +
                    "Callers needing remote activation over HTTP can file a follow-up.",
        ),
    )
}

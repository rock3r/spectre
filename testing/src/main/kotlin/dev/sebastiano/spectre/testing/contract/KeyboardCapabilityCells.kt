package dev.sebastiano.spectre.testing.contract

/**
 * Capability-matrix cells for the Robot-backed keyboard operations on the agent transport.
 *
 * Kept out of [CapabilityMatrix]'s main object body so Detekt's LargeClass budget stays honest, the
 * same way [focusWindowCapabilityCells] is — and because these rows share one story.
 * [AutomatorOperation.TypeText] and [AutomatorOperation.PressKey] both need the fixture window to
 * own OS keyboard focus, so both sit behind [RealKeyboardGate] off CI (#449) and both carry the
 * macOS CI soft-skip that keeps them out of Supported.
 */
internal fun agentTypeTextCapabilityCells(
    agentAttachLegacyLinux: CapabilityEvidence,
    agentAttachLegacyMacOs: CapabilityEvidence,
): List<CapabilityCell> =
    listOf(
        CapabilityCell(
            operation = AutomatorOperation.TypeText,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.LinuxXvfb,
            state = CellState.Experimental,
            evidence = listOf(agentAttachLegacyLinux),
            rationale =
                "AgentAttachIntegrationTest exercises typeText against the fixture, but CI may " +
                    "soft-skip on OS keyboard focus loss after Compose focus is proven. Off CI " +
                    "the subpath does not run at all unless RealKeyboardGate is opted in with " +
                    "-Pspectre.agent.realKeyboard=true. Not a Supported cell until typeText is " +
                    "fail-closed without silent skip.",
        ),
        CapabilityCell(
            operation = AutomatorOperation.TypeText,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.MacOsDesktop,
            state = CellState.Experimental,
            evidence = listOf(agentAttachLegacyMacOs),
            rationale =
                "Same CI focus-loss soft-skip and same RealKeyboardGate opt-in off CI as Linux " +
                    "Xvfb; attach/click remain Supported via the contract corpus. Full keyboard " +
                    "parity is experimental on CI.",
        ),
        CapabilityCell(
            operation = AutomatorOperation.TypeText,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.WindowsDesktop,
            state = CellState.NotYetCiExecuted,
        ),
    )

/**
 * PressKey rows for the agent transport.
 *
 * Supported on Linux Xvfb only: hosted macOS runners (especially JBR) often fail OS keyboard focus
 * after click the same way typeText does, so the corpus soft-skips there after retries — never on
 * Linux. Both platforms' evidence is CI evidence, because `press-key-tab-after-focus` runs by
 * default only when [RealKeyboardGate] is on (`CI=true`); a local run records
 * [RealKeyboardGate.SKIPPED_DETAIL] unless it opts in.
 */
internal fun agentPressKeyCapabilityCells(
    agentLinuxXvfb: CapabilityEvidence,
    agentMacOs: CapabilityEvidence,
): List<CapabilityCell> =
    listOf(
        CapabilityCell(
            operation = AutomatorOperation.PressKey,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.LinuxXvfb,
            state = CellState.Supported,
            evidence = listOf(agentLinuxXvfb),
        ),
        CapabilityCell(
            operation = AutomatorOperation.PressKey,
            transport = AutomatorTransport.Agent,
            platform = PlatformPrerequisite.MacOsDesktop,
            state = CellState.Experimental,
            evidence = listOf(agentMacOs),
            rationale =
                "AgentContractCorpus exercises pressKey after click; hosted macOS (JBR and " +
                    "sometimes Temurin) may soft-skip on OS keyboard focus loss after retries on " +
                    "macOS CI only (same class as typeText). Linux stays fail-closed. Off CI the " +
                    "scenario is skipped outright by RealKeyboardGate unless " +
                    "-Pspectre.agent.realKeyboard=true is passed. Not Supported until fail-closed " +
                    "without skip.",
        ),
    )

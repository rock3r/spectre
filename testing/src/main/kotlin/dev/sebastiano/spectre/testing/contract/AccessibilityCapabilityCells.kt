package dev.sebastiano.spectre.testing.contract

/** Native accessibility-source cells kept separate from the Compose transport matrix body. */
internal fun accessibilityCapabilityCells(): List<CapabilityCell> =
    listOf(
            PlatformPrerequisite.MacOsDesktop,
            PlatformPrerequisite.WindowsDesktop,
            PlatformPrerequisite.LinuxDesktop,
        )
        .map { platform ->
            CapabilityCell(
                operation = AutomatorOperation.AccessibilityTree,
                transport = AutomatorTransport.Cli,
                platform = platform,
                state = CellState.NotYetCiExecuted,
                rationale =
                    "Native reader and AccessKit fixture are wired, but this cell is not yet " +
                        "independently CI-executed; use the per-OS hard release-smoke result.",
            )
        }

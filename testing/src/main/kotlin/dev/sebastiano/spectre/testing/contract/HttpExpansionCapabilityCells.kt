package dev.sebastiano.spectre.testing.contract

/**
 * HTTP data-only expansion cells (#96): nested `tree` / `printTree`, `clearAndTypeText`, and
 * node-targeted screenshot. Kept out of [CapabilityMatrix]'s main object body so Detekt's
 * LargeClass budget stays honest.
 */
internal fun httpExpansionCapabilityCells(
    httpTransportExpansion: CapabilityEvidence
): List<CapabilityCell> {
    val cells = mutableListOf<CapabilityCell>()
    for (op in listOf(AutomatorOperation.Tree, AutomatorOperation.PrintTree)) {
        cells +=
            CapabilityCell(
                operation = op,
                transport = AutomatorTransport.Http,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.NotYetCiExecuted,
                evidence = listOf(httpTransportExpansion),
                rationale =
                    "HTTP routes wired; headless tests cover empty envelopes only, matching " +
                        "FindBy* HTTP cells. Nested WindowTreeDto conversion needs a live UI " +
                        "for Supported.",
            )
    }
    cells +=
        CapabilityCell(
            operation = AutomatorOperation.ClearAndTypeText,
            transport = AutomatorTransport.Http,
            platform = PlatformPrerequisite.Headless,
            state = CellState.UnsupportedByDesign,
            rationale =
                "HTTP clearAndTypeText needs a live node and host Robot typing; headless " +
                    "hosts throw. Unknown-key 404 is not a Supported capture of the op.",
        )
    cells +=
        CapabilityCell(
            operation = AutomatorOperation.NodeScreenshot,
            transport = AutomatorTransport.Http,
            platform = PlatformPrerequisite.Headless,
            state = CellState.UnsupportedByDesign,
            rationale =
                "Node screenshot needs a live node and display-backed capture, matching " +
                    "HTTP Screenshot Headless. Malformed/unknown-key 4xx is not Supported.",
        )
    cells +=
        CapabilityCell(
            operation = AutomatorOperation.ClearAndTypeText,
            transport = AutomatorTransport.Http,
            platform = PlatformPrerequisite.AnyJvm,
            state = CellState.NotYetCiExecuted,
            rationale = "Display-backed HTTP clearAndTypeText fixture not yet on CI.",
        )
    cells +=
        CapabilityCell(
            operation = AutomatorOperation.NodeScreenshot,
            transport = AutomatorTransport.Http,
            platform = PlatformPrerequisite.AnyJvm,
            state = CellState.NotYetCiExecuted,
            rationale = "Display-backed HTTP node screenshot fixture not yet on CI.",
        )
    return cells
}

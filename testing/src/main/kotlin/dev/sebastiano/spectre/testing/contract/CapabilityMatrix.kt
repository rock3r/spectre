package dev.sebastiano.spectre.testing.contract

/**
 * Executable capability matrix for Spectre's three automator transports.
 *
 * This object is the **single source of truth** for cell states. The published guide page
 * (`docs/guide/capability-matrix.md`) and the fail-closed evidence test both pin against it.
 *
 * Cells are multi-state ([CellState]); only [CellState.Supported] requires non-empty
 * [CapabilityEvidence] that resolves to real files in the repository.
 */
public object CapabilityMatrix {

    private val inProcessHeadlessCorpus =
        CapabilityEvidence(
            id = "in-process-headless-corpus",
            description = "In-process contract corpus (headless Robot, empty window set OK)",
            sourcePath =
                "testing/src/test/kotlin/dev/sebastiano/spectre/testing/contract/InProcessContractCorpusTest.kt",
            workflowPath = ".github/workflows/ci.yml",
            gradleTaskHint = "./gradlew :testing:test --tests \"*InProcessContractCorpusTest*\"",
        )

    private val httpHeadlessCorpus =
        CapabilityEvidence(
            id = "http-headless-corpus",
            description = "HTTP client contract corpus over a real CIO engine (headless automator)",
            sourcePath =
                "server/src/test/kotlin/dev/sebastiano/spectre/server/HttpContractCorpusTest.kt",
            workflowPath = ".github/workflows/ci.yml",
            gradleTaskHint = "./gradlew :server:test --tests \"*HttpContractCorpusTest*\"",
        )

    private val agentLinuxXvfb =
        CapabilityEvidence(
            id = "agent-attach-linux-xvfb",
            description =
                "Agent attach → exercise → detach against agent-test-fixture under Xvfb " +
                    "(validation-linux verifies JUnit XML executed, not skipped)",
            sourcePath =
                "agent/src/test/kotlin/dev/sebastiano/spectre/agent/AgentContractCorpusTest.kt",
            workflowPath = ".github/workflows/validation-linux.yml",
            gradleTaskHint =
                "./gradlew :agent:test --tests \"*AgentContractCorpusTest*\" " +
                    "(also *AgentAttachIntegrationTest*)",
        )

    private val agentMacOs =
        CapabilityEvidence(
            id = "agent-attach-macos-check",
            description =
                "Agent contract corpus on the macOS check workflow with fail-closed JUnit XML " +
                    "verification (must execute, not assumption-skip)",
            sourcePath =
                "agent/src/test/kotlin/dev/sebastiano/spectre/agent/AgentContractCorpusTest.kt",
            workflowPath = ".github/workflows/macos-check.yml",
            gradleTaskHint = "./gradlew :agent:test --tests \"*AgentContractCorpusTest*\"",
        )

    private val agentAttachLegacyLinux =
        CapabilityEvidence(
            id = "agent-attach-integration-linux",
            description =
                "Full-cycle AgentAttachIntegrationTest under Xvfb (windows/nodes/click/type)",
            sourcePath =
                "agent/src/test/kotlin/dev/sebastiano/spectre/agent/AgentAttachIntegrationTest.kt",
            workflowPath = ".github/workflows/validation-linux.yml",
            gradleTaskHint = "./gradlew :agent:test --tests \"*AgentAttachIntegrationTest*\"",
        )

    private val agentAttachLegacyMacOs =
        CapabilityEvidence(
            id = "agent-attach-integration-macos",
            description =
                "Full-cycle AgentAttachIntegrationTest on macOS check (fail-closed JUnit XML)",
            sourcePath =
                "agent/src/test/kotlin/dev/sebastiano/spectre/agent/AgentAttachIntegrationTest.kt",
            workflowPath = ".github/workflows/macos-check.yml",
            gradleTaskHint = "./gradlew :agent:test --tests \"*AgentAttachIntegrationTest*\"",
        )

    /**
     * Full matrix. Prefer [cellsFor] / [requireSupportedEvidence] helpers over hand-scanning.
     *
     * Not every op × transport × platform triple is listed: omitted combinations default to
     * [CellState.NotYetCiExecuted] for remote growth work (#201–#203) or are covered by a coarser
     * platform row when the op is transport-global.
     */
    public val cells: List<CapabilityCell> = buildList {
        // --- Intersection ops already on all three clients ---
        for (op in
            listOf(
                AutomatorOperation.Windows,
                AutomatorOperation.AllNodes,
                AutomatorOperation.FindByTestTag,
            )) {
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.InProcess,
                    platform = PlatformPrerequisite.Headless,
                    state = CellState.Supported,
                    evidence = listOf(inProcessHeadlessCorpus),
                )
            )
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Http,
                    platform = PlatformPrerequisite.Headless,
                    state = CellState.Supported,
                    evidence = listOf(httpHeadlessCorpus),
                )
            )
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.LinuxXvfb,
                    state = CellState.Supported,
                    evidence = listOf(agentLinuxXvfb, agentAttachLegacyLinux),
                )
            )
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.MacOsDesktop,
                    state = CellState.Supported,
                    evidence = listOf(agentMacOs, agentAttachLegacyMacOs),
                )
            )
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.WindowsDesktop,
                    state = CellState.NotYetCiExecuted,
                    rationale =
                        "Agent UDS transport works on Windows 10 1803+; full attach+UI " +
                            "fixture CI is tracked with platform validation (#193 lineage).",
                )
            )
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.Headless,
                    state = CellState.UnsupportedByDesign,
                    rationale =
                        "Compose Desktop fixture and java.awt.Robot require a display; " +
                            "AgentAttachIntegrationTest assumption-skips when headless.",
                )
            )
        }

        // Click — in-process/HTTP headless cover error path; agent covers happy path on UI.
        add(
            CapabilityCell(
                operation = AutomatorOperation.Click,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.Headless,
                state = CellState.Supported,
                evidence = listOf(inProcessHeadlessCorpus),
                rationale = "Corpus exercises missing-node failure shape without a live UI.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Click,
                transport = AutomatorTransport.Http,
                platform = PlatformPrerequisite.Headless,
                state = CellState.Supported,
                evidence = listOf(httpHeadlessCorpus),
                rationale = "HTTP corpus asserts non-2xx for unknown node keys (404 path).",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Click,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.LinuxXvfb,
                state = CellState.Supported,
                evidence = listOf(agentLinuxXvfb, agentAttachLegacyLinux),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Click,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.MacOsDesktop,
                state = CellState.Supported,
                evidence = listOf(agentMacOs, agentAttachLegacyMacOs),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Click,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.WindowsDesktop,
                state = CellState.NotYetCiExecuted,
                rationale = "Windows interactive agent UI fixture not yet on CI.",
            )
        )

        // typeText — needs a live surface / non-throwing Robot; headless adapters throw.
        add(
            CapabilityCell(
                operation = AutomatorOperation.TypeText,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.Headless,
                state = CellState.UnsupportedByDesign,
                rationale =
                    "RobotDriver.headless() throws on key/clipboard paths; in-process " +
                        "typeText is exercised under display-backed validation, not headless CI.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.TypeText,
                transport = AutomatorTransport.Http,
                platform = PlatformPrerequisite.Headless,
                state = CellState.UnsupportedByDesign,
                rationale =
                    "HTTP typeText delegates to the host Robot; headless hosts throw. " +
                        "Display-backed HTTP typeText is not-yet-CI-executed (#203 lineage).",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.TypeText,
                transport = AutomatorTransport.Http,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.NotYetCiExecuted,
                rationale = "Display-backed HTTP typeText fixture not yet on CI.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.TypeText,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.LinuxXvfb,
                state = CellState.Experimental,
                evidence = listOf(agentAttachLegacyLinux),
                rationale =
                    "AgentAttachIntegrationTest exercises typeText against the fixture, but " +
                        "CI may soft-skip on OS keyboard focus loss after Compose focus is proven. " +
                        "Not a Supported cell until typeText is fail-closed without silent skip.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.TypeText,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.MacOsDesktop,
                state = CellState.Experimental,
                evidence = listOf(agentAttachLegacyMacOs),
                rationale =
                    "Same CI focus-loss soft-skip as Linux Xvfb; attach/click remain Supported " +
                        "via the contract corpus. Full keyboard parity is experimental on CI.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.TypeText,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.WindowsDesktop,
                state = CellState.NotYetCiExecuted,
            )
        )

        // Screenshot / capture — headless screen-capture adapters throw; agent has fixture path.
        add(
            CapabilityCell(
                operation = AutomatorOperation.Screenshot,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.Headless,
                state = CellState.UnsupportedByDesign,
                rationale =
                    "Headless screen-capture adapter throws; screenshot needs a display " +
                        "or synthetic path outside this matrix cell.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Screenshot,
                transport = AutomatorTransport.Http,
                platform = PlatformPrerequisite.Headless,
                state = CellState.UnsupportedByDesign,
                rationale = "HTTP screenshot uses host Robot screen capture; headless throws.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Screenshot,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.LinuxXvfb,
                state = CellState.Supported,
                evidence = listOf(agentLinuxXvfb, agentAttachLegacyLinux),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Screenshot,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.MacOsDesktop,
                state = CellState.Supported,
                evidence = listOf(agentMacOs, agentAttachLegacyMacOs),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Capture,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.Experimental,
                rationale = "Atomic capture is in-process; agent also exposes it.",
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Capture,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.LinuxXvfb,
                state = CellState.Supported,
                evidence = listOf(agentLinuxXvfb),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.Capture,
                transport = AutomatorTransport.Http,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.UnsupportedByDesign,
                rationale = "HTTP surface does not yet expose atomic capture.",
            )
        )

        // --- Deliberate exclusions (remote + live-JVM-object ops) ---
        for (transport in listOf(AutomatorTransport.Http, AutomatorTransport.Agent)) {
            for (op in
                listOf(
                    AutomatorOperation.WaitForIdle,
                    AutomatorOperation.RegisterIdlingResource,
                    AutomatorOperation.WithTracing,
                )) {
                add(
                    CapabilityCell(
                        operation = op,
                        transport = transport,
                        platform = PlatformPrerequisite.AnyJvm,
                        state = CellState.UnsupportedByDesign,
                        rationale =
                            "Requires live JVM objects (idling resources / tracer hooks) " +
                                "that cannot cross the transport boundary without a different design.",
                    )
                )
            }
        }

        // In-process owns the full wait/idling surface
        fun coreEvidence(id: String, file: String, description: String) =
            CapabilityEvidence(
                id = id,
                description = description,
                sourcePath = "core/src/test/kotlin/dev/sebastiano/spectre/core/$file",
                workflowPath = ".github/workflows/ci.yml",
                gradleTaskHint = "./gradlew :core:test --tests \"*${file.removeSuffix(".kt")}*\"",
            )
        add(
            CapabilityCell(
                operation = AutomatorOperation.WaitForIdle,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.Supported,
                evidence =
                    listOf(
                        coreEvidence(
                            "core-wait-for-idle",
                            "WaitForIdleTest.kt",
                            "Core waitForIdle unit tests",
                        )
                    ),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.WaitForVisualIdle,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.Supported,
                evidence =
                    listOf(
                        coreEvidence(
                            "core-wait-for-visual-idle",
                            "WaitForVisualIdleTest.kt",
                            "Core waitForVisualIdle unit tests",
                        )
                    ),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.WaitForNode,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.Supported,
                evidence =
                    listOf(
                        coreEvidence(
                            "core-wait-for-node",
                            "WaitForNodeTest.kt",
                            "Core waitForNode unit tests",
                        )
                    ),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.RegisterIdlingResource,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.Supported,
                evidence =
                    listOf(
                        coreEvidence(
                            "core-wait-for-idle-idling",
                            "WaitForIdleTest.kt",
                            "Idling resources exercised via waitForIdle tests",
                        )
                    ),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.WithTracing,
                transport = AutomatorTransport.InProcess,
                platform = PlatformPrerequisite.AnyJvm,
                state = CellState.Supported,
                evidence =
                    listOf(
                        coreEvidence(
                            "core-with-tracing",
                            "WithTracingTest.kt",
                            "Core withTracing unit tests",
                        )
                    ),
            )
        )

        // #201–#203 agent fixture-backed cells (AgentContractCorpusTest under Xvfb/macOS).
        // PressKey is Supported on Linux Xvfb only: macOS hosted runners (esp. JBR) often
        // fail OS keyboard focus after click the same way typeText does; corpus soft-skips
        // that scenario on CI after retries (PressKeyAfterFocus).
        for (op in
            listOf(
                AutomatorOperation.WaitForNode,
                AutomatorOperation.FindByText,
                AutomatorOperation.FindByRole,
                AutomatorOperation.FindByContentDescription,
                AutomatorOperation.DoubleClick,
                AutomatorOperation.Swipe,
                AutomatorOperation.ScrollWheel,
            )) {
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.LinuxXvfb,
                    state = CellState.Supported,
                    evidence = listOf(agentLinuxXvfb),
                )
            )
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.MacOsDesktop,
                    state = CellState.Supported,
                    evidence = listOf(agentMacOs),
                )
            )
        }
        add(
            CapabilityCell(
                operation = AutomatorOperation.PressKey,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.LinuxXvfb,
                state = CellState.Supported,
                evidence = listOf(agentLinuxXvfb),
            )
        )
        add(
            CapabilityCell(
                operation = AutomatorOperation.PressKey,
                transport = AutomatorTransport.Agent,
                platform = PlatformPrerequisite.MacOsDesktop,
                state = CellState.Experimental,
                evidence = listOf(agentMacOs),
                rationale =
                    "AgentContractCorpus exercises pressKey after click; hosted macOS (JBR and " +
                        "sometimes Temurin) may soft-skip on OS keyboard focus loss after retries " +
                        "(same class as typeText). Not Supported until fail-closed without skip.",
            )
        )
        // HTTP selector routes exist; headless HttpContractCorpusTest only proves entry points
        // with empty trees (expectsFixtureSemantics=false), not fixture-backed matches.
        for (op in
            listOf(
                AutomatorOperation.FindByText,
                AutomatorOperation.FindByRole,
                AutomatorOperation.FindByContentDescription,
            )) {
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Http,
                    platform = PlatformPrerequisite.AnyJvm,
                    state = CellState.NotYetCiExecuted,
                    rationale =
                        "HTTP routes wired; needs display-backed HTTP fixture corpus for Supported.",
                    evidence = listOf(httpHeadlessCorpus),
                )
            )
        }
        for (op in listOf(AutomatorOperation.LongClick, AutomatorOperation.WaitForVisualIdle)) {
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Agent,
                    platform = PlatformPrerequisite.AnyJvm,
                    state = CellState.NotYetCiExecuted,
                    rationale =
                        "Wired on agent; dedicated fixture scenario not yet in AgentContractCorpus.",
                )
            )
        }
        for (op in
            listOf(
                AutomatorOperation.DoubleClick,
                AutomatorOperation.LongClick,
                AutomatorOperation.Swipe,
                AutomatorOperation.ScrollWheel,
                AutomatorOperation.PressKey,
                AutomatorOperation.WaitForNode,
                AutomatorOperation.WaitForVisualIdle,
            )) {
            add(
                CapabilityCell(
                    operation = op,
                    transport = AutomatorTransport.Http,
                    platform = PlatformPrerequisite.AnyJvm,
                    state = CellState.NotYetCiExecuted,
                    rationale =
                        "HTTP routes exist (#201–#203); display-backed HTTP fixture corpus still open.",
                )
            )
        }
    }

    /** All cells currently claimed [CellState.Supported]. */
    public fun supportedCells(): List<CapabilityCell> = cells.filter {
        it.state == CellState.Supported
    }

    /** Lookup cells for a transport (any platform/op). */
    public fun cellsFor(transport: AutomatorTransport): List<CapabilityCell> = cells.filter {
        it.transport == transport
    }

    /**
     * Returns the cell for the triple, or `null` if the matrix has no explicit row (callers should
     * treat missing rows as [CellState.NotYetCiExecuted] unless a coarser row applies).
     */
    public fun cell(
        operation: AutomatorOperation,
        transport: AutomatorTransport,
        platform: PlatformPrerequisite,
    ): CapabilityCell? = cells.find {
        it.operation == operation && it.transport == transport && it.platform == platform
    }
}

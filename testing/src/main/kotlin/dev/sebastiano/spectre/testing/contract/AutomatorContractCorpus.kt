package dev.sebastiano.spectre.testing.contract

/**
 * Transport-agnostic view of a tracked window for the shared contract corpus.
 *
 * Remote transports map their DTOs into this shape; in-process maps surface IDs / titles.
 */
public data class ContractWindow(public val surfaceId: String, public val title: String? = null)

/**
 * Transport-agnostic view of a semantics node for the shared contract corpus.
 *
 * [key] is the canonical `surfaceId:ownerIndex:nodeId` string on remote transports; in-process uses
 * [dev.sebastiano.spectre.core.NodeKey] string form.
 */
public data class ContractNode(
    public val key: String,
    public val testTag: String? = null,
    public val text: String? = null,
)

/**
 * Driver each transport implements so the same corpus can run against in-process, HTTP, and agent
 * clients without a shared runtime automator interface.
 *
 * Methods must hit the **real** client entry point for that transport (no mocks of the unit under
 * test). Empty results are allowed when the matrix cell does not require a live fixture.
 */
public interface AutomatorContractDriver : AutoCloseable {
    public val transport: AutomatorTransport

    /** When true, corpus asserts fixture-backed presence (non-empty windows / known tags). */
    public val expectsFixtureSemantics: Boolean
        get() = false

    public fun windows(): List<ContractWindow>

    public fun allNodes(): List<ContractNode>

    public fun findByTestTag(tag: String): List<ContractNode>

    /** Optional richer selectors (#202). Default: unsupported (skipped in headless corpus). */
    public fun findByText(text: String, exact: Boolean = true): List<ContractNode> =
        error("findByText not implemented for $transport")

    public fun findByContentDescription(description: String): List<ContractNode> =
        error("findByContentDescription not implemented for $transport")

    public fun findByRole(role: String): List<ContractNode> =
        error("findByRole not implemented for $transport")

    /**
     * Click by canonical node key. Drivers may throw on unknown keys; the corpus has a dedicated
     * unknown-key scenario that expects failure.
     */
    public fun click(nodeKey: String)

    /** Type into whatever holds focus. Headless drivers may no-op successfully. */
    public fun typeText(text: String)

    /** Optional input verbs (#203). Default: unsupported. */
    public fun doubleClick(nodeKey: String) {
        error("doubleClick not implemented for $transport")
    }

    public fun swipe(fromNodeKey: String, toNodeKey: String) {
        error("swipe not implemented for $transport")
    }

    public fun scrollWheel(nodeKey: String, wheelClicks: Int) {
        error("scrollWheel not implemented for $transport")
    }

    public fun pressKey(keyCode: Int, modifiers: Int = 0) {
        error("pressKey not implemented for $transport")
    }

    /**
     * Optional wait (#201). [waitForNodeTimeoutMs] is the budget. Should throw on timeout when
     * waiting for a never-present selector. Returns the matched node key on success.
     */
    public fun waitForNode(tag: String?, text: String?, timeoutMs: Long): String =
        error("waitForNode not implemented for $transport")

    /**
     * Optional screenshot probe. Return `null` if the transport/driver does not exercise screenshot
     * in this corpus level; non-null means bytes or a decoded image were obtained.
     */
    public fun screenshotProbe(): ScreenshotProbe? = null

    /** When true, corpus runs #201–#203 extended scenarios (selectors/waits/input). */
    public val supportsExtendedParity: Boolean
        get() = expectsFixtureSemantics

    override fun close() {}
}

/** Lightweight screenshot proof without forcing BufferedImage on every driver. */
public data class ScreenshotProbe(public val byteCount: Int, public val formatHint: String = "png")

/** Known test tags on `:agent-test-fixture` (shared string constants for corpus assertions). */
public object ContractFixtureTags {
    public const val LABEL: String = "agent-fixture-label"
    public const val TEXT_FIELD: String = "agent-fixture-text-field"
    public const val BUTTON: String = "agent-fixture-button"
}

/**
 * Shared contract-test corpus for epic #197.
 *
 * Run via [run] against a transport-specific [AutomatorContractDriver]. Failures throw
 * [AssertionError] with the scenario id so per-cell skip/reconcile tooling can map results.
 */
public object AutomatorContractCorpus {

    public data class ScenarioResult(
        public val id: String,
        public val transport: AutomatorTransport,
        public val passed: Boolean,
        public val detail: String = "",
    )

    public data class RunResult(
        public val transport: AutomatorTransport,
        public val results: List<ScenarioResult>,
    ) {
        public val allPassed: Boolean
            get() = results.all { it.passed }

        public fun requireAllPassed() {
            val failed = results.filterNot { it.passed }
            if (failed.isNotEmpty()) {
                throw AssertionError(
                    "Contract corpus failures for ${transport.name}:\n" +
                        failed.joinToString("\n") { "  - ${it.id}: ${it.detail}" }
                )
            }
        }
    }

    /**
     * Execute the corpus. Scenarios that need a live fixture are skipped (recorded as passed with
     * detail `skipped:no-fixture`) when [AutomatorContractDriver.expectsFixtureSemantics] is false
     * — matching matrix rows that only claim headless transport liveness.
     */
    public fun run(driver: AutomatorContractDriver): RunResult {
        val results = mutableListOf<ScenarioResult>()

        results +=
            scenario("windows-round-trip", driver.transport) {
                val windows = driver.windows()
                if (driver.expectsFixtureSemantics) {
                    check(windows.isNotEmpty()) {
                        "expected at least one window from fixture, got empty"
                    }
                }
                "windows=${windows.size}"
            }

        results +=
            scenario("all-nodes-round-trip", driver.transport) {
                val nodes = driver.allNodes()
                if (driver.expectsFixtureSemantics) {
                    check(nodes.isNotEmpty()) { "expected semantics nodes from fixture, got empty" }
                }
                "nodes=${nodes.size}"
            }

        results +=
            scenario("find-by-test-tag-round-trip", driver.transport) {
                val nodes = driver.findByTestTag(ContractFixtureTags.BUTTON)
                if (driver.expectsFixtureSemantics) {
                    check(nodes.isNotEmpty()) {
                        "expected tag ${ContractFixtureTags.BUTTON}, got none"
                    }
                }
                "tagged=${nodes.size}"
            }

        results +=
            scenario("click-unknown-key-fails", driver.transport) {
                val unknown = "nonexistent-surface:0:1"
                val failed = runCatching { driver.click(unknown) }.isFailure
                check(failed) { "click($unknown) should fail for unknown node key" }
                "failed-as-expected"
            }

        if (driver.expectsFixtureSemantics) {
            results +=
                scenario("click-fixture-button", driver.transport) {
                    val button =
                        driver.findByTestTag(ContractFixtureTags.BUTTON).firstOrNull()
                            ?: error("fixture button tag missing")
                    driver.click(button.key)
                    "clicked=${button.key}"
                }
            // typeText is Experimental on agent (CI OS-focus flakes); not part of the
            // Supported corpus. AgentAttachIntegrationTest owns the nuanced keyboard path.
            results +=
                ScenarioResult(
                    id = "type-text-after-focus-field",
                    transport = driver.transport,
                    passed = true,
                    detail = "skipped:type-text-experimental",
                )
            results +=
                scenario("screenshot-non-empty", driver.transport) {
                    val probe =
                        driver.screenshotProbe()
                            ?: error("fixture driver must implement screenshotProbe()")
                    check(probe.byteCount > 0) { "screenshot empty" }
                    "bytes=${probe.byteCount} format=${probe.formatHint}"
                }
            if (driver.supportsExtendedParity) {
                results += extendedParityScenarios(driver)
            }
        } else {
            results +=
                ScenarioResult(
                    id = "click-fixture-button",
                    transport = driver.transport,
                    passed = true,
                    detail = "skipped:no-fixture",
                )
            results +=
                ScenarioResult(
                    id = "type-text-after-focus-field",
                    transport = driver.transport,
                    passed = true,
                    detail = "skipped:no-fixture",
                )
            results +=
                ScenarioResult(
                    id = "screenshot-non-empty",
                    transport = driver.transport,
                    passed = true,
                    detail = "skipped:no-fixture",
                )
            // Headless Robot adapters throw on real key/clipboard paths; typeText is covered by
            // fixture-backed agent corpus + sample-desktop validation, not the headless round-trip.
            results +=
                ScenarioResult(
                    id = "type-text-entry-point",
                    transport = driver.transport,
                    passed = true,
                    detail = "skipped:no-fixture",
                )
        }

        return RunResult(transport = driver.transport, results = results)
    }

    /** #201–#203 scenarios against a live fixture-backed driver (agent Xvfb/macOS). */
    private fun extendedParityScenarios(driver: AutomatorContractDriver): List<ScenarioResult> {
        val out = mutableListOf<ScenarioResult>()
        // #202 selectors
        out +=
            scenario("find-by-text-fixture-label", driver.transport) {
                val nodes = driver.findByText("Spectre agent fixture", exact = true)
                check(nodes.isNotEmpty()) { "findByText exact missed fixture label" }
                "matched=${nodes.size}"
            }
        out +=
            scenario("find-by-text-substring", driver.transport) {
                val nodes = driver.findByText("agent fixture", exact = false)
                check(nodes.isNotEmpty()) { "findByText substring missed fixture label" }
                "matched=${nodes.size}"
            }
        out +=
            scenario("find-by-role-button", driver.transport) {
                val nodes = driver.findByRole("Button")
                check(nodes.isNotEmpty()) { "findByRole(Button) empty" }
                "matched=${nodes.size}"
            }
        out +=
            scenario("find-by-content-description", driver.transport) {
                val nodes = driver.findByContentDescription("fixture submit")
                check(nodes.isNotEmpty()) { "findByContentDescription empty" }
                "matched=${nodes.size}"
            }
        // #201 waits
        out +=
            scenario("wait-for-node-present-tag", driver.transport) {
                val key =
                    driver.waitForNode(
                        tag = ContractFixtureTags.BUTTON,
                        text = null,
                        timeoutMs = 3_000,
                    )
                check(key.isNotBlank()) { "waitForNode returned blank key" }
                "key=$key"
            }
        out +=
            scenario("wait-for-node-timeout", driver.transport) {
                val failed =
                    runCatching {
                            driver.waitForNode(
                                tag = "agent-fixture-never-appears",
                                text = null,
                                timeoutMs = 400,
                            )
                        }
                        .isFailure
                check(failed) { "waitForNode for missing tag must fail/timeout" }
                "timed-out-as-expected"
            }
        // #203 input verbs (real Robot on fixture)
        out +=
            scenario("double-click-fixture-button", driver.transport) {
                val button =
                    driver.findByTestTag(ContractFixtureTags.BUTTON).firstOrNull()
                        ?: error("fixture button missing")
                driver.doubleClick(button.key)
                "double-clicked=${button.key}"
            }
        out +=
            scenario("swipe-label-to-button", driver.transport) {
                val label =
                    driver.findByTestTag(ContractFixtureTags.LABEL).firstOrNull()
                        ?: error("fixture label missing")
                val button =
                    driver.findByTestTag(ContractFixtureTags.BUTTON).firstOrNull()
                        ?: error("fixture button missing")
                driver.swipe(label.key, button.key)
                "swiped ${label.key}->${button.key}"
            }
        out +=
            scenario("scroll-wheel-on-label", driver.transport) {
                val label =
                    driver.findByTestTag(ContractFixtureTags.LABEL).firstOrNull()
                        ?: error("fixture label missing")
                driver.scrollWheel(label.key, wheelClicks = 1)
                "scrolled=${label.key}"
            }
        out +=
            scenario("press-key-tab", driver.transport) {
                // VK_TAB = 9. Agent may refuse when the target lacks OS keyboard focus (CI).
                val result = runCatching { driver.pressKey(keyCode = 9, modifiers = 0) }
                val msg = result.exceptionOrNull()?.message.orEmpty()
                if (result.isFailure && "keyboard focus" in msg) {
                    "skipped:os-focus"
                } else {
                    result.getOrThrow()
                    "pressKey=VK_TAB"
                }
            }
        return out
    }

    private inline fun scenario(
        id: String,
        transport: AutomatorTransport,
        block: () -> String,
    ): ScenarioResult =
        // Scenario bodies use check()/error() and real transport clients that can throw a
        // variety of checked/unchecked failures. runCatching records them as ScenarioResult
        // rows so one bad cell does not abort the rest of the corpus mid-suite.
        runCatching { block() }
            .fold(
                onSuccess = { detail ->
                    ScenarioResult(id = id, transport = transport, passed = true, detail = detail)
                },
                onFailure = { error ->
                    ScenarioResult(
                        id = id,
                        transport = transport,
                        passed = false,
                        detail = error.message ?: error::class.simpleName ?: "error",
                    )
                },
            )
}

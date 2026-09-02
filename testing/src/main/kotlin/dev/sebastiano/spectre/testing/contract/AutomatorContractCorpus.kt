package dev.sebastiano.spectre.testing.contract

import java.util.concurrent.TimeUnit

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
 *
 * Function count is intentionally above the default detekt budget: the corpus needs one method per
 * tracked public entry (selectors, input verbs, waits + timeout taxonomy) so Supported matrix cells
 * cannot soft-skip.
 */
@Suppress("TooManyFunctions")
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

    /** Optional window activation (#364). Default: unsupported. */
    public fun focusWindow(nodeKey: String) {
        error("focusWindow not implemented for $transport")
    }

    /**
     * Optional wait (#201). Should throw on timeout when waiting for a never-present selector.
     * Returns the matched node key on success.
     */
    public fun waitForNode(tag: String?, text: String?, timeoutMs: Long): String =
        error("waitForNode not implemented for $transport")

    /**
     * Stable error taxonomy name for a failed [waitForNode] (e.g. `"timeout"`). Implementations
     * that cannot surface a category should throw rather than invent one.
     */
    public fun waitForNodeFailureCategory(tag: String?, text: String?, timeoutMs: Long): String =
        error("waitForNodeFailureCategory not implemented for $transport")

    /**
     * Optional absence wait (#438). Must return once nothing matches [tag] / [text], and throw when
     * the selector is still present at [timeoutMs].
     */
    public fun waitUntilGone(tag: String?, text: String?, timeoutMs: Long) {
        error("waitUntilGone not implemented for $transport")
    }

    /**
     * Stable taxonomy name **and** message for a failed [waitUntilGone], the counterpart to
     * [waitForNodeFailureCategory].
     *
     * The message is part of the contract, not decoration: an absence wait that times out is only
     * actionable if it says which selector is still on screen, how long it waited, and how many
     * nodes matched. Implementations return what the transport actually delivered — never a
     * synthesised message — so a transport that flattens the diagnostics fails the corpus.
     */
    public fun waitUntilGoneFailure(
        tag: String?,
        text: String?,
        timeoutMs: Long,
    ): ContractWaitFailure = error("waitUntilGoneFailure not implemented for $transport")

    /**
     * Optional screenshot probe. Return `null` if the transport/driver does not exercise screenshot
     * in this corpus level; non-null means bytes or a decoded image were obtained.
     */
    public fun screenshotProbe(): ScreenshotProbe? = null

    /**
     * When true, corpus runs fixture-backed match/input scenarios that need a live Compose UI
     * (agent Xvfb/macOS). Selector entry-point scenarios always run on all transports.
     */
    public val supportsFixtureParity: Boolean
        get() = expectsFixtureSemantics

    /**
     * Binary-compatible alias for [supportsFixtureParity]. Prefer [supportsFixtureParity]; this
     * name remains so drivers compiled against older `testing` artifacts keep linking.
     */
    @Deprecated(
        message = "Renamed to supportsFixtureParity",
        replaceWith = ReplaceWith("supportsFixtureParity"),
    )
    public val supportsExtendedParity: Boolean
        get() = supportsFixtureParity

    /**
     * When true, [waitForNodeFailureCategory] is implemented (agent + in-process). HTTP has no wait
     * routes (#201 is agent-scoped).
     */
    public val supportsWaitTaxonomy: Boolean
        get() = false

    /**
     * When true, [waitUntilGone] and [waitUntilGoneFailure] are implemented (#438).
     *
     * Deliberately its own gate rather than folded into [supportsWaitTaxonomy]. That flag's
     * published contract promised only [waitForNodeFailureCategory], so a driver that already sets
     * it true — including one outside this repo — would suddenly be asked for an absence wait it
     * never claimed and fail the corpus on the default `error(...)` below. Defaulting to false
     * keeps upgrading `:testing` a no-op for those drivers, the same compatibility care the
     * deprecated [supportsExtendedParity] alias exists for.
     */
    public val supportsAbsenceWait: Boolean
        get() = false

    override fun close() {}
}

/**
 * What a transport actually reported for a failed wait: the #199-style taxonomy [category] and the
 * verbatim [message] the caller would see.
 */
public data class ContractWaitFailure(public val category: String, public val message: String)

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
    public fun run(driver: AutomatorContractDriver): RunResult =
        run(driver = driver, realKeyboardEnabled = RealKeyboardGate.isEnabled())

    /** Seam for tests: the real-keyboard gate is injected rather than read from the environment. */
    internal fun run(driver: AutomatorContractDriver, realKeyboardEnabled: Boolean): RunResult {
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
            if (driver.supportsFixtureParity) {
                results += fixtureParityScenarios(driver, realKeyboardEnabled)
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

        // Selector entry points + wait timeout taxonomy run on every transport (including
        // headless).
        results += crossTransportParityScenarios(driver)

        return RunResult(transport = driver.transport, results = results)
    }

    /**
     * #201–#202 scenarios that run on **all three** transports. Headless trees may be empty; the
     * contract is that entry points do not hang and wait timeout surfaces taxonomy `timeout`.
     */
    private fun crossTransportParityScenarios(
        driver: AutomatorContractDriver
    ): List<ScenarioResult> {
        val out = mutableListOf<ScenarioResult>()
        out +=
            scenario("find-by-text-entry", driver.transport) {
                val nodes = driver.findByText("no-such-label-xyz", exact = true)
                "matched=${nodes.size}"
            }
        out +=
            scenario("find-by-role-entry", driver.transport) {
                // Unknown role must fail closed as invalidSelector on agent/HTTP; in-process may
                // return empty for a non-matching role name when filtering by string.
                val result = runCatching { driver.findByRole("not-a-compose-role") }
                when {
                    result.isFailure -> "failed-as-expected"
                    result.getOrNull().orEmpty().isEmpty() -> "empty-as-expected"
                    else -> error("unknown role must not match nodes")
                }
            }
        out +=
            scenario("find-by-content-description-entry", driver.transport) {
                val nodes = driver.findByContentDescription("no-such-description-xyz")
                "matched=${nodes.size}"
            }
        if (driver.supportsWaitTaxonomy) {
            out +=
                scenario("wait-for-node-timeout-taxonomy", driver.transport) {
                    val category =
                        driver.waitForNodeFailureCategory(
                            tag = "agent-fixture-never-appears",
                            text = null,
                            timeoutMs = 400,
                        )
                    check(category == "timeout") {
                        "expected timeout taxonomy, got category=$category"
                    }
                    "category=$category"
                }
        }
        if (driver.supportsAbsenceWait) {
            // #438 absence wait, mirror image of the taxonomy scenario above: a selector that
            // matches nothing is already gone, so the wait must return rather than burn its
            // budget. Runs headless too — an empty tree is the strongest possible "nothing
            // matches".
            out +=
                scenario("wait-until-gone-absent-selector", driver.transport) {
                    val budgetMs = ABSENT_SELECTOR_BUDGET_MS
                    val startedAt = System.nanoTime()
                    driver.waitUntilGone(
                        tag = "agent-fixture-never-appears",
                        text = null,
                        timeoutMs = budgetMs,
                    )
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    check(elapsedMs < budgetMs) {
                        "waitUntilGone waited ${elapsedMs}ms for a selector that never matched; " +
                            "it should return on the first poll"
                    }
                    "elapsedMs=$elapsedMs"
                }
        }
        return out
    }

    /** Fixture-backed match + input scenarios (agent Xvfb/macOS). */
    private fun fixtureParityScenarios(
        driver: AutomatorContractDriver,
        realKeyboardEnabled: Boolean,
    ): List<ScenarioResult> {
        val out = mutableListOf<ScenarioResult>()
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
        out += fixtureWaitScenarios(driver)
        // #364: raise the window hosting a known node before further Robot input.
        out +=
            scenario("focus-window-fixture-button", driver.transport) {
                val button =
                    driver.findByTestTag(ContractFixtureTags.BUTTON).firstOrNull()
                        ?: error("fixture button missing")
                driver.focusWindow(button.key)
                "focused-window-for=${button.key}"
            }
        // #203 input verbs (real Robot on fixture) — no soft-skip: failures fail the scenario.
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
        // Real-keyboard paths are opt-in off CI (RealKeyboardGate, #449). The gate is checked
        // *before* the node lookup: a driver error resolving the text field would otherwise fail
        // the scenario on a host that was never going to run it, which is the opposite of keeping
        // `./gradlew check` runnable on a desktop in use.
        out +=
            if (!realKeyboardEnabled) {
                PressKeyAfterFocus.warnSkipped()
                ScenarioResult(
                    id = PressKeyAfterFocus.SCENARIO_ID,
                    transport = driver.transport,
                    passed = true,
                    detail = RealKeyboardGate.SKIPPED_DETAIL,
                )
            } else {
                scenario(PressKeyAfterFocus.SCENARIO_ID, driver.transport) {
                    // Focus the text field first so OS keyboard focus is on the target JVM.
                    // Retry click+pressKey: macOS JBR often needs a settle window after click
                    // (see PressKeyAfterFocus / matrix residuals on jbr-21/jbr-25 macos).
                    val field =
                        driver.findByTestTag(ContractFixtureTags.TEXT_FIELD).firstOrNull()
                            ?: error("fixture text field missing")
                    PressKeyAfterFocus.run(
                        driver = driver,
                        fieldKey = field.key,
                        keyCode = PressKeyAfterFocus.DEFAULT_KEY_CODE_TAB,
                        modifiers = 0,
                    )
                }
            }
        return out
    }

    /**
     * Fixture-backed wait scenarios: `waitForNode` finds a node that is there (#201), and
     * `waitUntilGone` refuses to call one that is still there gone (#438).
     *
     * Split out of [fixtureParityScenarios] so each stays readable — the wait pair asserts on
     * timing and failure diagnostics, the rest asserts on selectors and input verbs.
     */
    private fun fixtureWaitScenarios(driver: AutomatorContractDriver): List<ScenarioResult> {
        val out = mutableListOf<ScenarioResult>()
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
        // #438: the fixture button is permanently on screen, so an absence wait for it must time
        // out — and the diagnostics are the deliverable. Asserting the message (not just the
        // taxonomy) is what stops a transport from flattening "2 nodes matching tag=… are still
        // there" into a bare "timed out" on its way across the boundary.
        if (!driver.supportsAbsenceWait) return out
        out +=
            scenario("wait-until-gone-timeout-diagnostics", driver.transport) {
                val timeoutMs = 400L
                val failure =
                    driver.waitUntilGoneFailure(
                        tag = ContractFixtureTags.BUTTON,
                        text = null,
                        timeoutMs = timeoutMs,
                    )
                check(failure.category == "timeout") {
                    "expected timeout taxonomy, got category=${failure.category}"
                }
                val missing = buildList {
                    if (!failure.message.contains(ContractFixtureTags.BUTTON)) add("selector")
                    if (!failure.message.contains("${timeoutMs}ms")) add("timeout")
                    if (!STILL_PRESENT_COUNT.containsMatchIn(failure.message)) add("count")
                }
                check(missing.isEmpty()) {
                    "absence diagnostics lost ${missing.joinToString()} crossing the transport: " +
                        failure.message
                }
                "category=${failure.category}"
            }
        return out
    }

    /** Budget for the absent-selector scenario: the wait must return long before this. */
    private const val ABSENT_SELECTOR_BUDGET_MS: Long = 5_000

    /** Matches the "N node(s)" clause every `waitUntilGone` timeout must carry. */
    private val STILL_PRESENT_COUNT = Regex("""\d+ node\(s\)""")

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

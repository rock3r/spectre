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

    /**
     * Click by canonical node key. Drivers may throw on unknown keys; the corpus has a dedicated
     * unknown-key scenario that expects failure.
     */
    public fun click(nodeKey: String)

    /** Type into whatever holds focus. Headless drivers may no-op successfully. */
    public fun typeText(text: String)

    /**
     * Optional screenshot probe. Return `null` if the transport/driver does not exercise screenshot
     * in this corpus level; non-null means bytes or a decoded image were obtained.
     */
    public fun screenshotProbe(): ScreenshotProbe? = null

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
            results +=
                scenario("type-text-after-focus-field", driver.transport) {
                    val field =
                        driver.findByTestTag(ContractFixtureTags.TEXT_FIELD).firstOrNull()
                            ?: error("fixture text field tag missing")
                    driver.click(field.key)
                    driver.typeText("x")
                    "typed"
                }
            results +=
                scenario("screenshot-non-empty", driver.transport) {
                    val probe =
                        driver.screenshotProbe()
                            ?: error("fixture driver must implement screenshotProbe()")
                    check(probe.byteCount > 0) { "screenshot empty" }
                    "bytes=${probe.byteCount} format=${probe.formatHint}"
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

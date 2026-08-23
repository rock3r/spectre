package dev.sebastiano.spectre.testing.contract

/**
 * The single opt-in gate for every Robot-backed real-keyboard path in the test suites (#444, #449).
 *
 * Two suites send real OS key events at a spawned Compose fixture: the `typeText` subpath of
 * `AgentAttachIntegrationTest`, and the [PressKeyAfterFocus] scenario of [AutomatorContractCorpus].
 * Both only work while the fixture window owns OS keyboard focus, so anything else taking focus — a
 * terminal, an editor, a notification — fails them. `./gradlew check` is the documented pre-push
 * gate, and it must stay runnable on a machine someone is using.
 *
 * Defaults:
 * - **CI** (`CI=true` in the environment): enabled, so the keyboard paths keep their coverage.
 * - **Developer machines**: disabled. Everything else in both suites still runs; only the
 *   focus-dependent keyboard assertions are skipped, and each caller reports what it skipped.
 *
 * Opt in on an idle desktop with:
 * ```
 * ./gradlew check -Pspectre.agent.realKeyboard=true
 * ```
 *
 * **PowerShell:** quote the property so the shell does not treat `.agent…` as a separate task:
 * `./gradlew check "-Pspectre.agent.realKeyboard=true"`.
 *
 * Gradle forwards the `-P` form to test JVMs as `-D`[ENABLE_PROP] (see the `realKeyboardGate`
 * wiring in the `:agent`, `:server`, and `:testing` build scripts). Passing `false` turns the paths
 * off on CI too.
 *
 * When the paths *do* run, their assertions are unchanged: hosted macOS CI still tolerates a lost
 * OS focus handoff (that flakiness is not what this gate is about), Linux Xvfb stays fail-closed,
 * and a local opt-in run still fails loudly so a real keyboard regression is visible.
 */
public object RealKeyboardGate {
    /** System property read by [isEnabled] on the test JVM. */
    public const val ENABLE_PROP: String = "dev.sebastiano.spectre.agent.realKeyboard"

    /** Gradle property the build scripts forward to [ENABLE_PROP]. */
    public const val GRADLE_PROPERTY: String = "spectre.agent.realKeyboard"

    /** Corpus [AutomatorContractCorpus.ScenarioResult] detail recorded when the gate is off. */
    public const val SKIPPED_DETAIL: String = "skipped:real-keyboard-gate-off"

    /** Shared tail for skip messages, so every caller names the same two spellings. */
    public const val ENABLE_HINT: String =
        "CI runs it by default; on an idle desktop pass \"-P$GRADLE_PROPERTY=true\" " +
            "(or -D$ENABLE_PROP=true on the test JVM)."

    /**
     * Returns true when real-keyboard paths may run on this host.
     *
     * [property] wins in both directions when it parses as a boolean. Anything else (unset, blank,
     * or a typo such as `yes`) falls back to [ci], so a mistyped property cannot silently drop the
     * CI-side keyboard coverage.
     */
    public fun isEnabled(
        property: String? = System.getProperty(ENABLE_PROP),
        ci: String? = System.getenv("CI"),
    ): Boolean {
        val explicit = property?.trim()?.lowercase()?.toBooleanStrictOrNull()
        if (explicit != null) return explicit
        return ci.equals("true", ignoreCase = true)
    }
}

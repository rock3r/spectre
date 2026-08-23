package dev.sebastiano.spectre.agent

/**
 * Opt-in gate for the Robot-backed real-keyboard subpath of [AgentAttachIntegrationTest] (#444).
 *
 * The subpath clicks the fixture's text field and types into it with a real `java.awt.Robot`. That
 * only works while the fixture window owns OS keyboard focus, so anything else taking focus — a
 * terminal, an editor, a notification — fails the test. `./gradlew check` is the documented
 * pre-push gate, and it must stay runnable on a machine someone is using.
 *
 * Defaults:
 * - **CI** (`CI=true` in the environment): enabled, so the keyboard path keeps its coverage.
 * - **Developer machines**: disabled. The attach, `windows()`, `findByTestTag`, `click()`,
 *   window-identity and screenshot assertions all still run; only the click-to-focus and `typeText`
 *   assertions are skipped, and the test prints to stderr exactly what it skipped.
 *
 * Opt in on an idle desktop with:
 * ```
 * ./gradlew :agent:test --tests '*AgentAttachIntegrationTest*' -Pspectre.agent.realKeyboard=true
 * ```
 *
 * **PowerShell:** quote the property so the shell does not treat `.agent…` as a separate task:
 * `./gradlew :agent:test "-Pspectre.agent.realKeyboard=true" --tests '*…*'`
 *
 * or `-D[ENABLE_PROP]=true` on the test JVM (Gradle forwards the `-P` form via
 * [agent/build.gradle.kts]). Passing `false` turns the subpath off on CI too.
 *
 * When the subpath *does* run, its assertions are unchanged: CI still tolerates a lost OS focus
 * handoff (that flakiness is not what this gate is about), and a local opt-in run still fails
 * loudly so a real keyboard regression is visible.
 */
internal object RealKeyboardE2eGate {
    const val ENABLE_PROP: String = "dev.sebastiano.spectre.agent.realKeyboard"

    /**
     * Returns true when the real-keyboard subpath may run on this host.
     *
     * [property] wins in both directions when it parses as a boolean. Anything else (unset, blank,
     * or a typo such as `yes`) falls back to [ci], so a mistyped property cannot silently drop the
     * CI-side keyboard coverage.
     */
    fun isEnabled(
        property: String? = System.getProperty(ENABLE_PROP),
        ci: String? = System.getenv("CI"),
    ): Boolean {
        val explicit = property?.trim()?.lowercase()?.toBooleanStrictOrNull()
        if (explicit != null) return explicit
        return ci.equals("true", ignoreCase = true)
    }
}

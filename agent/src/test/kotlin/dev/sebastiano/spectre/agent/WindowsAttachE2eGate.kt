package dev.sebastiano.spectre.agent

/**
 * Opt-in gate for Robot-backed agent attach UI e2e on Windows.
 *
 * Hosted GitHub `windows-latest` runners do not provide a reliable interactive desktop for the
 * Compose fixture + `java.awt.Robot` path (see #194 close-out / historical re-disable in PR #244).
 * Non-UI Windows transport and ACL tests still run under `:agent:test` on every Windows CI job.
 * Physical desktops (e.g. Mattone) opt in with:
 * ```
 * ./gradlew :agent:test --tests '*AgentAttachIntegrationTest*' \
 *   -Pspectre.agent.attachE2e.allowWindows=true
 * ```
 *
 * **PowerShell:** quote the property so the shell does not treat `.agent…` as a separate task:
 * `./gradlew :agent:test "-Pspectre.agent.attachE2e.allowWindows=true" --tests '*…*'`
 *
 * or `-Ddev.sebastiano.spectre.agent.attachE2e.allowWindows=true` on the test JVM (Gradle forwards
 * the `-P` form via [agent/build.gradle.kts]).
 */
internal object WindowsAttachE2eGate {
    const val ALLOW_PROP: String = "dev.sebastiano.spectre.agent.attachE2e.allowWindows"

    /**
     * Returns true when attach UI e2e may run on this host.
     *
     * Non-Windows is always allowed (Linux/macOS use `@EnabledOnOs` + headless skip as usual).
     * Windows requires [ALLOW_PROP] to be exactly `"true"`.
     */
    fun isAllowed(
        osName: String = System.getProperty("os.name").orEmpty(),
        allowWindows: String? = System.getProperty(ALLOW_PROP),
    ): Boolean {
        if (!osName.startsWith("Windows", ignoreCase = true)) return true
        return allowWindows == "true"
    }
}

package dev.sebastiano.spectre.cli.hotreload

/**
 * Tested Compose Hot Reload version range for Spectre's reload-awareness (#211).
 *
 * Protocol messages are version-gated by HR itself; older servers simply omit newer message types.
 * Pin is documented for operators and release notes — keep in sync with the Gradle coordinate in
 * `:cli`.
 */
public object HotReloadVersions {
    /** Exact artifact version resolved by Spectre's CLI/daemon dependency. */
    public const val PINNED: String = "1.2.0-rc01"

    /** Inclusive lower bound of the supported 1.2 line (findings verified from alpha+211). */
    public const val MIN_SUPPORTED: String = "1.2.0-alpha+211"

    /** Inclusive upper bound of the currently tested range. */
    public const val MAX_TESTED: String = "1.2.0-rc01"

    /** System property / env key for the orchestration TCP port (HR's own key). */
    public const val ORCHESTRATION_PORT_PROPERTY: String = "compose.reload.orchestration.port"

    /** System property / env key for HR's pid file path. */
    public const val PID_FILE_PROPERTY: String = "compose.reload.pidFile"

    /** Property key inside the pid file for the orchestration port. */
    public const val PID_FILE_PORT_KEY: String = "orchestration.port"

    /** Property key inside the pid file for the application pid. */
    public const val PID_FILE_PID_KEY: String = "pid"
}

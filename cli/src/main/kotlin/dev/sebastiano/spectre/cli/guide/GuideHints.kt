package dev.sebastiano.spectre.cli.guide

import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode

/** The last paragraph of `spectre --help`. */
internal const val GUIDE_HELP_FOOTER: String =
    "New to spectre? Run \"spectre guide\" or \"spectre guide --search <topic>\"."

/** Printed after a usage error. */
internal const val GUIDE_USAGE_HINT: String =
    "Run \"spectre guide\" for the CLI workflow, or \"spectre guide --search <words>\" to look up an error."

/** A one-line pointer to the guide topic that explains a failure. */
internal fun guideHint(topic: String): String =
    "Hint: run \"spectre guide $topic\" or \"spectre guide --search <words>\"."

/** The guide topic that best explains a daemon error, for [guideHint]. */
internal fun guideTopicFor(code: DaemonErrorCode?): String =
    when (code) {
        DaemonErrorCode.AttachFailed -> "agent"
        // A session that vanished right after attach usually means the daemon was killed.
        DaemonErrorCode.SessionNotFound -> "traps"
        DaemonErrorCode.Timeout -> "synchronization"
        DaemonErrorCode.HotReloadUnavailable,
        DaemonErrorCode.ReloadFailed -> "hot-reload"
        // Focus refusals, fail-closed screenshots, and similar input or capture refusals.
        DaemonErrorCode.OperationFailed -> "traps"
        else -> "troubleshooting"
    }

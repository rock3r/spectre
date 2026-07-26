package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.capture.CaptureArtifactPaths
import java.nio.file.Path

/**
 * Shared failure-artifact orchestration for [ComposeAutomatorExtension] and [ComposeAutomatorRule].
 *
 * Captures only when [FailureArtifactsConfig.enabled] is true. Always best-effort: capture errors
 * must not replace the original test failure.
 */
internal object FailureArtifactHooks {

    const val REPORT_ENTRY_KEY: String = "spectre.failureArtifact"

    fun recordFailure(
        automator: ComposeAutomator,
        config: FailureArtifactsConfig,
        testClassName: String,
        testMethodName: String,
        publishReport: (key: String, value: String) -> Unit,
        capture: (ComposeAutomator, Path) -> List<CaptureArtifactPaths> =
            FailureArtifactCapture::captureAllWindows,
    ): List<CaptureArtifactPaths> {
        if (!config.enabled) return emptyList()
        return runCatching {
                val methodDir =
                    FailureArtifactPaths.methodDirectory(
                        testClassName = testClassName,
                        testMethodName = testMethodName,
                        config = config,
                    )
                val paths = capture(automator, methodDir)
                for (path in paths) {
                    publishReport(
                        REPORT_ENTRY_KEY,
                        path.directory.toAbsolutePath().normalize().toString(),
                    )
                }
                paths
            }
            .getOrDefault(emptyList())
    }
}

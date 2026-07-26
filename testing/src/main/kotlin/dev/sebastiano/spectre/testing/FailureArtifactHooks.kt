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

    /**
     * True when [throwable] is a JUnit assumption / abort rather than a test failure. Aborted tests
     * must not produce failure artifacts (expensive and misleading for platform-skip suites).
     *
     * Both JUnit 4 and JUnit 5 abort types are matched by class name so a JUnit-4-only consumer
     * never loads opentest4j (and vice versa) — production depends on each runner as `compileOnly`.
     */
    fun isNonFailureAbort(throwable: Throwable): Boolean {
        var type: Class<*>? = throwable.javaClass
        while (type != null) {
            when (type.name) {
                // JUnit 5 assumptions / abort (also lifecycle @BeforeEach/@AfterEach aborts).
                "org.opentest4j.TestAbortedException",
                // JUnit 4: public type extends the internal one; match both.
                "org.junit.AssumptionViolatedException",
                "org.junit.internal.AssumptionViolatedException" -> return true
            }
            type = type.superclass
        }
        return false
    }

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

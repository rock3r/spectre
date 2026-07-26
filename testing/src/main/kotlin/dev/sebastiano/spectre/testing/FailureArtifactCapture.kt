package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.capture.AtomicCapture
import dev.sebastiano.spectre.core.capture.CaptureArtifactPaths
import dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter
import java.nio.file.Path

/**
 * Writes one atomic capture directory per known window under a test-method report folder.
 *
 * Pure relative to the capture function: production callers pass `{ automator.capture(it) }`; unit
 * tests inject stubs so path + write behavior is covered without a live Compose UI.
 *
 * Capture failures for individual windows are swallowed so a flaky screenshot cannot mask the
 * original test failure. Callers still receive the successfully written paths.
 */
public object FailureArtifactCapture {

    public fun captureWindows(
        methodDirectory: Path,
        windowCount: Int,
        captureWindow: (windowIndex: Int) -> AtomicCapture,
    ): List<CaptureArtifactPaths> {
        if (windowCount <= 0) return emptyList()
        val written = ArrayList<CaptureArtifactPaths>(windowCount)
        for (index in 0 until windowCount) {
            val paths =
                runCatching {
                        val capture = captureWindow(index)
                        CaptureArtifactsWriter.write(
                            directory =
                                FailureArtifactPaths.windowDirectory(methodDirectory, index),
                            document = capture.document,
                            pngBytes = capture.pngBytes,
                        )
                    }
                    .getOrNull()
            if (paths != null) written += paths
        }
        return written
    }

    /**
     * Convenience for production: capture every currently tracked window via [ComposeAutomator].
     * Refreshes the window list first so indices match a live UI at failure time.
     *
     * Window discovery failures are swallowed (empty list) for the same reason as per-window
     * capture errors: the original test failure must remain the primary failure signal.
     */
    public fun captureAllWindows(
        automator: ComposeAutomator,
        methodDirectory: Path,
    ): List<CaptureArtifactPaths> =
        captureFromDiscovery(
            methodDirectory = methodDirectory,
            discoverWindowCount = {
                automator.refreshWindows()
                automator.surfaceIds().size
            },
            captureWindow = { index -> automator.capture(index) },
        )

    /**
     * Shared best-effort path for production and tests: if [discoverWindowCount] throws, returns an
     * empty list instead of propagating.
     */
    internal fun captureFromDiscovery(
        methodDirectory: Path,
        discoverWindowCount: () -> Int,
        captureWindow: (windowIndex: Int) -> AtomicCapture,
    ): List<CaptureArtifactPaths> {
        val count =
            runCatching(discoverWindowCount).getOrElse {
                return emptyList()
            }
        return captureWindows(
            methodDirectory = methodDirectory,
            windowCount = count,
            captureWindow = captureWindow,
        )
    }
}

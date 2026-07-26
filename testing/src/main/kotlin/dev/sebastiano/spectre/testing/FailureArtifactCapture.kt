package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.capture.AtomicCapture
import dev.sebastiano.spectre.core.capture.CaptureArtifactPaths
import dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.asSequence

/**
 * Writes one atomic capture directory per known window under a test-method report folder.
 *
 * Pure relative to the capture function: production callers pass `{ automator.capture(it) }`; unit
 * tests inject stubs so path + write behavior is covered without a live Compose UI.
 *
 * Capture failures for individual windows are swallowed so a flaky screenshot cannot mask the
 * original test failure. Callers still receive the successfully written paths. Partial directories
 * from a failed write are removed so CI globs never upload a half-written capture.
 */
public object FailureArtifactCapture {

    public fun captureWindows(
        methodDirectory: Path,
        windowCount: Int,
        captureWindow: (windowIndex: Int) -> AtomicCapture,
    ): List<CaptureArtifactPaths> {
        // Drop every prior window-* under this method dir (including higher indices than the
        // current windowCount) so CI cannot publish stale captures from a previous failure.
        clearStaleWindowDirectories(methodDirectory)
        if (windowCount <= 0) return emptyList()
        val written = ArrayList<CaptureArtifactPaths>(windowCount)
        for (index in 0 until windowCount) {
            val directory = FailureArtifactPaths.windowDirectory(methodDirectory, index)
            val paths =
                runCatching {
                        val capture = captureWindow(index)
                        writeCaptureAtomically(directory, capture)
                    }
                    .getOrNull()
            if (paths != null) written += paths
        }
        return written
    }

    private fun clearStaleWindowDirectories(methodDirectory: Path) {
        if (!methodDirectory.exists()) return
        runCatching {
            Files.list(methodDirectory).use { stream ->
                stream
                    .asSequence()
                    .filter {
                        Files.isDirectory(it) && it.fileName.toString().startsWith("window-")
                    }
                    .forEach { deleteRecursivelyQuietly(it) }
            }
        }
    }

    /**
     * Convenience for production: capture every currently tracked window via [ComposeAutomator].
     *
     * Snapshots [ComposeAutomator.surfaceIds] once, then re-resolves each surface by id before
     * capturing so a window that closes mid-loop does not shift later indices onto the wrong
     * surface (best-effort: a closed surface is skipped).
     *
     * Window discovery failures are swallowed (empty list) for the same reason as per-window
     * capture errors: the original test failure must remain the primary failure signal.
     */
    public fun captureAllWindows(
        automator: ComposeAutomator,
        methodDirectory: Path,
    ): List<CaptureArtifactPaths> {
        val surfaceIds =
            runCatching {
                    automator.refreshWindows()
                    automator.surfaceIds()
                }
                .getOrElse {
                    return emptyList()
                }
        return captureWindows(
            methodDirectory = methodDirectory,
            windowCount = surfaceIds.size,
            captureWindow = { index ->
                val surfaceId = surfaceIds[index]
                automator.refreshWindows()
                val liveIndex = automator.surfaceIds().indexOf(surfaceId)
                check(liveIndex >= 0) { "Window surface $surfaceId no longer tracked" }
                val capture = automator.capture(liveIndex)
                // capture() refreshes again; reject a drifted surface so we never write the wrong
                // window into window-N (skip this slot via runCatching instead).
                check(capture.document.window.surfaceId == surfaceId) {
                    "Capture drifted to surface ${capture.document.window.surfaceId}, " +
                        "wanted $surfaceId"
                }
                capture
            },
        )
    }

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

    private fun writeCaptureAtomically(
        directory: Path,
        capture: AtomicCapture,
    ): CaptureArtifactPaths {
        return try {
            CaptureArtifactsWriter.write(
                directory = directory,
                document = capture.document,
                pngBytes = capture.pngBytes,
            )
        } catch (error: IOException) {
            deleteRecursivelyQuietly(directory)
            throw error
        }
    }

    private fun deleteRecursivelyQuietly(directory: Path) {
        if (!directory.exists()) return
        runCatching {
            Files.walk(directory).use { stream ->
                stream
                    .asSequence()
                    .sortedByDescending { it.nameCount }
                    .forEach { path -> runCatching { Files.deleteIfExists(path) } }
            }
        }
    }
}

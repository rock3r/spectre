@file:OptIn(dev.sebastiano.spectre.core.InternalSpectreApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.WindowIdentitySnapshot
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingHandle
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.lang.ProcessHandle
import java.nio.file.Path

/**
 * Production [FailureVideoStarter]: routes through [AutoRecorder] (window-targeted when a unique
 * non-blank title is available, otherwise region using the surface/window bounds or primary
 * display). Best-effort — returns null when no backend can start so the test outcome is unaffected.
 */
internal object AutoFailureVideoStarter : FailureVideoStarter {

    override fun start(output: Path, automator: ComposeAutomator): RecordingHandle? =
        runCatching {
                val recorder = AutoRecorder()
                val identities =
                    runCatching { automator.windowIdentities() }.getOrDefault(emptyList())
                val primary = identities.firstOrNull { !it.isPopup }
                if (primary != null) {
                    startForIdentity(recorder, primary, identities, output)
                        ?: startRegionFallback(recorder, primary, output)
                } else {
                    startFullscreenRegion(recorder, output)
                }
            }
            .getOrNull()

    private fun startForIdentity(
        recorder: AutoRecorder,
        identity: WindowIdentitySnapshot,
        allIdentities: List<WindowIdentitySnapshot>,
        output: Path,
    ): RecordingHandle? {
        val title = identity.title?.takeIf { it.isNotBlank() } ?: return null
        // Helpers match by title among all same-PID windows (including popups). Only use
        // window-targeted mode when the title uniquely identifies the selected surface.
        val titleMatches = allIdentities.count { candidate ->
            candidate.title.orEmpty().contains(title) || candidate.title == title
        }
        if (titleMatches != 1) return null
        val crop =
            if (identity.cropRequired) {
                Rectangle(identity.surfaceBoundsInWindow)
            } else {
                null
            }
        return runCatching {
                recorder.startWindowByTitle(
                    title = title,
                    windowOwnerPid = ProcessHandle.current().pid(),
                    output = output,
                    cropInWindow = crop,
                    scaleX = identity.scaleX,
                    scaleY = identity.scaleY,
                )
            }
            .getOrNull()
    }

    private fun startRegionFallback(
        recorder: AutoRecorder,
        identity: WindowIdentitySnapshot,
        output: Path,
    ): RecordingHandle? {
        // Pass AWT user-space bounds unchanged — same contract as startFullscreenRegion and
        // other AutoRecorder.startRegion callers (locationOnScreen / GraphicsConfiguration.bounds).
        // Do not multiply by scaleX/scaleY; that double-scales on HiDPI.
        val bounds =
            identity.surfaceBoundsOnScreen.takeIf { it.width > 0 && it.height > 0 }
                ?: identity.windowBoundsOnScreen.takeIf { it.width > 0 && it.height > 0 }
                ?: return null
        return runCatching { recorder.startRegion(region = bounds, output = output) }.getOrNull()
    }

    private fun startFullscreenRegion(recorder: AutoRecorder, output: Path): RecordingHandle? {
        if (GraphicsEnvironment.isHeadless()) return null
        val bounds =
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .bounds
        if (bounds.width <= 0 || bounds.height <= 0) return null
        return runCatching { recorder.startRegion(region = bounds, output = output) }.getOrNull()
    }
}

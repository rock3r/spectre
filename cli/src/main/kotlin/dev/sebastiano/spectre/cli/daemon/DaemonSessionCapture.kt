package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
import dev.sebastiano.spectre.recording.AutoScreenshotter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Daemon-owned atomic capture for one attach session (#559).
 *
 * The injected runtime does not carry `spectre-recording`, so the target JVM cannot take a native
 * window still by itself. Before #559 it silently took a `java.awt.Robot` crop of the desktop
 * instead, which captured any window that covered the target. The daemon now takes the still with
 * its own native helper, matched to the target window by title and process id, and sends it to the
 * agent. The agent reads the semantics tree right after and pairs the two.
 *
 * On macOS this also puts Screen Recording permission on the daemon's signed helper, the same one
 * `spectre record` uses, instead of on the target's `java` binary.
 *
 * Platforms without an out-of-process window still (Linux) use the target's own native capture,
 * which fails closed when the target has no `spectre-recording`. A failed daemon still is reported
 * as an error; it is never replaced by another kind of capture.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal class DaemonSessionCapture(
    private val targetPid: Long,
    private val windowIdentities: (windowIndex: Int?) -> List<WindowIdentityDto>,
    private val captureWithWindowStill:
        (
            windowIndex: Int,
            windowStillPng: ByteArray,
            stillWindowBounds: RectDto,
        ) -> AtomicCaptureResult,
    private val inProcessCapture: (windowIndex: Int) -> AtomicCaptureResult,
    private val captureWindowByTitle: (title: String, windowOwnerPid: Long) -> BufferedImage =
        { title, windowOwnerPid ->
            AutoScreenshotter().captureWindowByTitle(title, windowOwnerPid)
        },
) {
    constructor(
        delegate: AttachedAutomator
    ) : this(
        targetPid = delegate.pid,
        windowIdentities = { index -> delegate.windowIdentities(index) },
        captureWithWindowStill = { index, png, bounds ->
            delegate.captureWithWindowStill(index, png, bounds)
        },
        inProcessCapture = { index -> delegate.capture(index) },
    )

    fun capture(windowIndex: Int): AtomicCaptureResult {
        val identity = requireIdentity(windowIndex)
        val title = requireUniqueTitle(identity)
        val still =
            try {
                captureWindowByTitle(title, targetPid)
            } catch (_: UnsupportedOperationException) {
                // No out-of-process window still on this platform. The target's own native
                // capture either works or fails closed; it never takes a desktop crop.
                return inProcessCapture(windowIndex)
            } catch (exception: IllegalStateException) {
                throw stillFailed(windowIndex, exception)
            } catch (exception: IllegalArgumentException) {
                throw stillFailed(windowIndex, exception)
            }
        return captureWithWindowStill(windowIndex, encodePng(still), identity.windowBoundsOnScreen)
    }

    private fun requireIdentity(windowIndex: Int): WindowIdentityDto =
        windowIdentities(windowIndex).firstOrNull()
            ?: throw IOException(
                "cannot capture windowIndex=$windowIndex: no tracked window has that index"
            )

    private fun requireUniqueTitle(identity: WindowIdentityDto): String {
        val title =
            identity.title?.takeIf { it.isNotBlank() }
                ?: throw IOException(
                    "cannot capture window ${identity.index}: it has a blank title. Spectre " +
                        "finds the window of an attached application by its title, so give " +
                        "the window a unique, non-blank title."
                )
        // Native helpers match by "title contains" among every window of the target process,
        // popups included, so any other window whose title contains this one is a collision.
        val collisions = windowIdentities(null).filter { it.title.orEmpty().contains(title) }
        if (collisions.size > 1) {
            throw IOException(
                "cannot capture window ${identity.index}: its title \"$title\" is ambiguous " +
                    "among windows [${collisions.joinToString { it.index.toString() }}] of the " +
                    "target. Give the window a unique title so the native capture helper selects " +
                    "the intended window."
            )
        }
        return title
    }

    private fun stillFailed(windowIndex: Int, cause: Exception): IOException =
        IOException(
            "native window capture failed for windowIndex=$windowIndex: " +
                (cause.message ?: cause.javaClass.simpleName) +
                ". Spectre does not substitute a desktop crop, because a crop can include other " +
                "windows that cover the target.",
            cause,
        )

    private fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", out)) {
            throw IOException("could not encode the native window still as PNG")
        }
        return out.toByteArray()
    }
}

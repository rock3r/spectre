package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.ExperimentalSpectreRecordingApi
import java.awt.Rectangle
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
 * A window without a title, such as a Swing `JWindow` popup (IntelliJ heavyweight popups), cannot
 * be found by title. The daemon finds it by its on-screen frame among the windows of the target
 * process instead (#605), and the target checks that the window has not moved since.
 *
 * Platforms without an out-of-process window still (Linux) use the target's own native capture,
 * which fails closed when the target has no `spectre-recording`. A failed daemon still is reported
 * as an error; it is never replaced by another kind of capture.
 */
@OptIn(ExperimentalSpectreAgentApi::class, ExperimentalSpectreRecordingApi::class)
internal class DaemonSessionCapture(
    private val targetPid: Long,
    private val windowIdentities: (windowIndex: Int?) -> List<WindowIdentityDto>,
    private val captureWithWindowStill:
        (
            windowIndex: Int,
            surfaceId: String,
            windowTitle: String,
            windowStillPng: ByteArray,
            stillWindowBounds: RectDto,
        ) -> AtomicCaptureResult,
    private val inProcessCapture: (windowIndex: Int) -> AtomicCaptureResult,
    private val captureWithUntitledWindowStill:
        (
            windowIndex: Int,
            surfaceId: String,
            windowStillPng: ByteArray,
            stillWindowBounds: RectDto,
        ) -> AtomicCaptureResult =
        { _, _, _, _ ->
            throw IOException("this session cannot capture a window without a title")
        },
    private val captureWindowByTitle: (title: String, windowOwnerPid: Long) -> BufferedImage =
        { title, windowOwnerPid ->
            AutoScreenshotter().captureWindowByTitle(title, windowOwnerPid)
        },
    private val captureWindowByFrame:
        (
            windowBounds: RectDto,
            screenBounds: RectDto,
            scaleX: Double,
            scaleY: Double,
            windowOwnerPid: Long,
        ) -> BufferedImage =
        { windowBounds, screenBounds, scaleX, scaleY, windowOwnerPid ->
            AutoScreenshotter()
                .captureWindowByFrame(
                    windowBounds.toRectangle(),
                    screenBounds.toRectangle(),
                    scaleX,
                    scaleY,
                    windowOwnerPid,
                )
        },
) {
    constructor(
        delegate: AttachedAutomator
    ) : this(
        targetPid = delegate.pid,
        windowIdentities = { index -> delegate.windowIdentities(index) },
        captureWithWindowStill = { index, surfaceId, title, png, bounds ->
            delegate.captureWithWindowStill(index, surfaceId, title, png, bounds)
        },
        inProcessCapture = { index -> delegate.capture(index) },
        captureWithUntitledWindowStill = { index, surfaceId, png, bounds ->
            delegate.captureWithUntitledWindowStill(index, surfaceId, png, bounds)
        },
    )

    fun capture(windowIndex: Int): AtomicCaptureResult {
        val identity = requireIdentity(windowIndex)
        if (identity.title == null) return captureUntitled(windowIndex, identity)
        val title = requireTitle(identity)
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
        return captureWithWindowStill(
            windowIndex,
            identity.surfaceId,
            title,
            encodePng(still),
            identity.windowBoundsOnScreen,
        )
    }

    /**
     * Captures a window that has no title (`WindowIdentityDto.title` is null only for hosts that
     * are not a `Frame` or `Dialog`) by its on-screen frame.
     */
    private fun captureUntitled(
        windowIndex: Int,
        identity: WindowIdentityDto,
    ): AtomicCaptureResult {
        val screenBounds =
            identity.screenBoundsOnScreen
                ?: throw IOException(
                    "cannot capture window $windowIndex: it has no title, and the spectre-core " +
                        "in the target application is too old to report the screen bounds " +
                        "Spectre needs to find it by its frame. Update the target's spectre-core " +
                        "dependency to the same version as the Spectre CLI."
                )
        val windowBounds = identity.windowBoundsOnScreen
        val still =
            try {
                captureWindowByFrame(
                    windowBounds,
                    screenBounds,
                    identity.scaleX,
                    identity.scaleY,
                    targetPid,
                )
            } catch (_: UnsupportedOperationException) {
                // Same as titled windows: no out-of-process still on this platform (Linux).
                return inProcessCapture(windowIndex)
            } catch (exception: IllegalStateException) {
                throw stillFailed(windowIndex, exception)
            } catch (exception: IllegalArgumentException) {
                throw stillFailed(windowIndex, exception)
            }
        return captureWithUntitledWindowStill(
            windowIndex,
            identity.surfaceId,
            encodePng(still),
            windowBounds,
        )
    }

    private fun requireIdentity(windowIndex: Int): WindowIdentityDto =
        windowIdentities(windowIndex).firstOrNull()
            ?: throw IOException(
                "cannot capture windowIndex=$windowIndex: no tracked window has that index"
            )

    // The target checks title uniqueness after the still: only it can see every native window of
    // its process, including plain Swing windows that Spectre does not track.
    private fun requireTitle(identity: WindowIdentityDto): String =
        identity.title?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "cannot capture window ${identity.index}: it has a blank title. Spectre finds " +
                    "the window of an attached application by its title, so give the window a " +
                    "unique, non-blank title."
            )

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

@OptIn(ExperimentalSpectreAgentApi::class)
private fun RectDto.toRectangle(): Rectangle = Rectangle(x, y, width, height)

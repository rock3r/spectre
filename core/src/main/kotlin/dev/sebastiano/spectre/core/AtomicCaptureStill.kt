@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.CaptureNodeSnapshot
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Why atomic [ComposeAutomator.capture] cannot take a native window still in this JVM, or null when
 * it can.
 *
 * Atomic capture never substitutes a `java.awt.Robot` desktop crop for a window still (#559). A
 * crop holds whatever is drawn on screen at the window's bounds, including other windows that cover
 * the target, so it would leak unrelated content into the capture artifact.
 */
internal fun atomicCaptureNativeStillUnavailableReason(
    classLoader: ClassLoader = AtomicCaptureStillMarker::class.java.classLoader,
    allowsPlatformCapture: Boolean,
    osName: String = System.getProperty("os.name").orEmpty(),
    getenv: (String) -> String? = System::getenv,
): String? =
    when {
        !allowsPlatformCapture ->
            "Atomic capture needs a native window still, but this RobotDriver does not permit " +
                "real screenshot capture (RobotDriver.headless()). $NO_DESKTOP_CROP_EXPLANATION"
        isNonInteractiveHostedWindows(osName, getenv) ->
            "Atomic capture needs a native window still, but GitHub-hosted Windows runners have " +
                "no interactive desktop for Windows Graphics Capture. Run the capture on an " +
                "interactive desktop. $NO_DESKTOP_CROP_EXPLANATION"
        nativeWindowCaptureFor(classLoader) == null ->
            "Atomic capture needs a native window still, but spectre-recording " +
                "($NATIVE_WINDOW_CAPTURE_BRIDGE) is not on this JVM's classpath. Add " +
                "spectre-recording and the helper artifact for this OS " +
                "(spectre-recording-macos, -windows, or -linux) to the runtime classpath. For an " +
                "attached application, run `spectre capture` through the Spectre daemon: the " +
                "daemon takes the window still itself. $NO_DESKTOP_CROP_EXPLANATION"
        else -> null
    }

/** Throws [UnsupportedOperationException] with [atomicCaptureNativeStillUnavailableReason]. */
internal fun requireAtomicCaptureNativeStill(
    classLoader: ClassLoader = AtomicCaptureStillMarker::class.java.classLoader,
    allowsPlatformCapture: Boolean,
    osName: String = System.getProperty("os.name").orEmpty(),
    getenv: (String) -> String? = System::getenv,
) {
    atomicCaptureNativeStillUnavailableReason(classLoader, allowsPlatformCapture, osName, getenv)
        ?.let { throw UnsupportedOperationException(it) }
}

/**
 * Maps a native still of a whole window, taken by another process, onto the Compose surface.
 *
 * The caller (the Spectre daemon on the attach path) takes [image] before the semantics snapshot,
 * at [stillWindowBounds]. If the window has moved or resized since then, the pixels and the tree
 * would describe different layouts, so this fails and the caller retries.
 */
@Suppress("LongParameterList") // Every geometry input is a seam the tests substitute.
internal fun externalWindowStill(
    image: BufferedImage,
    stillWindowBounds: Rectangle,
    liveWindowBounds: Rectangle,
    frameInsets: Insets,
    captureRegion: Rectangle,
    osName: String = System.getProperty("os.name").orEmpty(),
    isWayland: Boolean = isWaylandSession(),
): WindowCapture {
    check(stillWindowBounds == liveWindowBounds) {
        "The window moved or resized between the native window still ($stillWindowBounds) and " +
            "the semantics snapshot ($liveWindowBounds). Retry the capture once the window is " +
            "settled."
    }
    val imageBounds = nativeWindowCaptureBounds(osName, liveWindowBounds, frameInsets, isWayland)
    return windowStillForRegion(
        WindowCapture(normalizeNativeImage(image), imageBounds),
        captureRegion,
    )
}

/**
 * Capture region, density, node properties, and screen geometry for an atomic capture, frozen in
 * one EDT pass so the JSON and the pixels cannot describe different window layouts.
 */
internal data class PreCaptureSnapshot(
    val captureRegion: Rectangle,
    val windowBounds: Rectangle,
    val frameInsets: Insets,
    val densityScaleX: Double,
    val densityScaleY: Double,
    val nodeSnapshots: List<CaptureNodeSnapshot>,
)

/** Reads a [PreCaptureSnapshot] of [trackedWindow]. Call it on the EDT. */
internal fun readPreCaptureSnapshot(
    trackedWindow: TrackedWindow,
    semanticsReader: SemanticsReader,
): PreCaptureSnapshot {
    val transform = trackedWindow.window.graphicsConfiguration.defaultTransform
    return PreCaptureSnapshot(
        captureRegion = trackedWindow.composeSurfaceBoundsOnScreen,
        windowBounds = Rectangle(trackedWindow.window.bounds),
        frameInsets = windowInsets(trackedWindow.window),
        densityScaleX = transform.scaleX,
        densityScaleY = transform.scaleY,
        nodeSnapshots =
            semanticsReader.readAllNodes(listOf(trackedWindow)).map { node ->
                CaptureNodeSnapshot(
                    key = node.key.toString(),
                    testTag = node.testTag,
                    text = node.text,
                    texts = node.texts,
                    editableText = node.editableText,
                    contentDescription = node.contentDescription,
                    role = node.role?.toString(),
                    enabled = !node.isDisabled,
                    clickable = node.isClickable,
                    focused = node.isFocused,
                    selected = node.isSelected,
                    boundsScreen = node.bothBounds().onScreen,
                )
            },
    )
}

private const val NO_DESKTOP_CROP_EXPLANATION: String =
    "Spectre does not substitute a desktop crop, because a crop can include other windows that " +
        "cover the target."

/** Marker for classloader defaults. */
private object AtomicCaptureStillMarker

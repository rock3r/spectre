@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.CaptureNodeSnapshot
import java.awt.Dialog
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Window
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
 *
 * The caller finds the window by title, so the still may show another window of this process when
 * [titleCollisions] is not empty, or if the window index now resolves to a surface other than
 * [expectedSurfaceId]. Both also fail: the pixels would not match the tree.
 */
@Suppress("LongParameterList") // Every geometry input is a seam the tests substitute.
internal fun externalWindowStill(
    image: BufferedImage,
    stillWindowBounds: Rectangle,
    liveWindowBounds: Rectangle,
    frameInsets: Insets,
    captureRegion: Rectangle,
    titleCollisions: List<String>,
    expectedSurfaceId: String,
    liveSurfaceId: String,
    osName: String = System.getProperty("os.name").orEmpty(),
    isWayland: Boolean = isWaylandSession(),
): WindowCapture {
    check(expectedSurfaceId == liveSurfaceId) {
        "The window list changed after the native window still was taken: the still shows " +
            "surface $expectedSurfaceId, but the window index now resolves to $liveSurfaceId. " +
            "Retry the capture."
    }
    check(titleCollisions.isEmpty()) {
        "The native window still may show another window: the capture helper finds the target " +
            "by title, and other windows of this process have matching titles " +
            "(${titleCollisions.joinToString { "\"$it\"" }}). Give the window a unique title."
    }
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
 * Titles of other windows in this process that a native capture helper, searching by [targetTitle],
 * could select instead of the target.
 *
 * The macOS ScreenCaptureKit helper selects the first window whose title contains [targetTitle].
 * The Windows helper selects a window whose title equals [targetTitle]. [others] must cover every
 * native window of this process except the target, including plain Swing windows, so the check sees
 * the same candidates as the helper.
 */
internal fun remoteCaptureTitleCollisions(
    targetTitle: String,
    others: List<CaptureTitleCandidate>,
    osName: String = System.getProperty("os.name").orEmpty(),
): List<String> {
    // The Windows helper enumerates every top-level window of the process and compares titles
    // exactly. The macOS helper only considers on-screen windows and matches "title contains".
    val windows = osName.startsWith("Windows", ignoreCase = true)
    return others
        .filter { windows || it.isShowing }
        .mapNotNull { it.title }
        .filter { title -> if (windows) title == targetTitle else title.contains(targetTitle) }
}

/** Another window of this process, as a native capture helper could see it. */
internal data class CaptureTitleCandidate(val title: String?, val isShowing: Boolean)

/**
 * [remoteCaptureTitleCollisions] for [target] among this JVM's displayable windows. Call on EDT.
 */
internal fun remoteCaptureTitleCollisions(target: Window): List<String> {
    val targetTitle = target.nativeTitle()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val others =
        Window.getWindows()
            .filter { it !== target && it.isDisplayable }
            .map { CaptureTitleCandidate(it.nativeTitle(), it.isShowing) }
    return remoteCaptureTitleCollisions(targetTitle, others)
}

private fun Window.nativeTitle(): String? =
    when (this) {
        is Frame -> title
        is Dialog -> title
        else -> null
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

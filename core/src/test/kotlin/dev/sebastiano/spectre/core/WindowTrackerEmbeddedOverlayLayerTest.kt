@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.Window
import java.util.concurrent.TimeUnit
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/**
 * Live coverage for #563: an OnWindow popup (`compose.layers.type=WINDOW`, hosted by Compose's
 * internal `WindowComposeSceneLayer`) opened from a `ComposePanel` embedded in a plain Swing
 * `JFrame` / `JDialog` must surface as a popup surface in [WindowTracker], exactly once.
 *
 * The layer type is normally fixed at JVM start through the `compose.layers.type` system property.
 * These tests flip it for the panels they create through [withOnWindowLayerType] instead, so the
 * rest of the `:core:test` JVM keeps the default `OnSameCanvas` mode.
 */
class WindowTrackerEmbeddedOverlayLayerTest {

    private val windowsToDispose = mutableListOf<Window>()

    @AfterTest
    fun tearDown() {
        onEdt { windowsToDispose.forEach(Window::dispose) }
        windowsToDispose.clear()
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks OnWindow popup opened from a ComposePanel embedded in a JFrame`() {
        assumeLiveAwtAvailable()
        val frame = withOnWindowLayerType {
            onEdt {
                JFrame("Spectre #563 embedded frame").apply {
                    contentPane.add(composePanelWithPopup(), BorderLayout.CENTER)
                    size = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
                    windowsToDispose += this
                    isVisible = true
                }
            }
        }

        assertSinglePopupOverlaySurface(owner = frame)
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks OnWindow popup opened from a wrapped ComposePanel embedded in a JDialog`() {
        assumeLiveAwtAvailable()
        val dialog = withOnWindowLayerType {
            onEdt {
                // A plain JPanel wrapping the ComposePanel mirrors hosts such as Jewel's
                // `JewelComposePanelWrapper`, which never expose the ComposePanel directly.
                val wrapper = JPanel(BorderLayout()).apply { add(composePanelWithPopup()) }
                JDialog(null as Frame?, "Spectre #563 embedded dialog").apply {
                    contentPane.add(wrapper, BorderLayout.CENTER)
                    size = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
                    windowsToDispose += this
                    isVisible = true
                }
            }
        }

        assertSinglePopupOverlaySurface(owner = dialog)
    }

    private fun assertSinglePopupOverlaySurface(owner: Window) {
        val tracker = WindowTracker()
        val popupSurface = awaitPopupSurface(tracker, owner)
        assertNotNull(
            popupSurface,
            "Expected an overlay popup surface owned by ${owner.javaClass.simpleName}; tracked: " +
                tracker.trackedWindows.value.map { it.surfaceId },
        )
        assertTrue(popupSurface.isPopup, "Overlay layer surface must be reported as a popup")
        assertNull(popupSurface.composePanel, "Overlay layer windows have no ComposePanel")
        val popupTags = popupSurface.overlayTags()
        assertTrue(POPUP_TAG in popupTags, "Popup semantics must be readable, got $popupTags")

        val surfacesForLayerWindow =
            tracker.trackedWindows.value.filter { it.window === popupSurface.window }
        assertEquals(
            1,
            surfacesForLayerWindow.size,
            "The layer window must be tracked once, got " +
                surfacesForLayerWindow.map { it.surfaceId },
        )
    }

    private fun awaitPopupSurface(tracker: WindowTracker, owner: Window): TrackedWindow? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCOVERY_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            tracker.refresh()
            val match =
                tracker.trackedWindows.value.firstOrNull { tracked ->
                    tracked.window.owner === owner &&
                        tracked.overlaySemanticsOwners != null &&
                        POPUP_TAG in tracked.overlayTags()
                }
            if (match != null) return match
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun TrackedWindow.overlayTags(): Set<String> {
        val owners = overlaySemanticsOwners?.invoke().orEmpty()
        return onEdt { owners.flatMap(SemanticsOwner::testTags).toSet() }
    }

    private fun composePanelWithPopup(): ComposePanel =
        ComposePanel().apply {
            preferredSize = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
            setContent {
                Box(Modifier.size(HOST_CONTENT_DP.dp).testTag(HOST_TAG)) {
                    Popup { Box(Modifier.size(POPUP_SIZE_DP.dp).testTag(POPUP_TAG)) }
                }
            }
        }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val DISCOVERY_TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 50L
        const val HOST_SIZE_PX = 320
        const val HOST_CONTENT_DP = 200
        const val POPUP_SIZE_DP = 80
        const val HOST_TAG = "host"
        const val POPUP_TAG = "embedded-popup"
    }
}

private fun SemanticsOwner.testTags(): List<String> {
    val tags = mutableListOf<String>()
    fun visit(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.TestTag)?.let(tags::add)
        node.children.forEach(::visit)
    }
    visit(unmergedRootSemanticsNode)
    return tags
}

private fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

/**
 * Runs [block] with Compose Desktop's layer type forced to `OnWindow`, as if the JVM had started
 * with `-Dcompose.layers.type=WINDOW`. Compose reads the flag when a `ComposePanel` builds its
 * container (on `addNotify`), so [block] must show the host window before returning.
 *
 * `ComposeFeatureFlags.layerType` is a Kotlin-internal `FeatureFlag` with a nullable `override`
 * slot (the same hook Compose's own tests use through `withOverride`), so it is set reflectively
 * here and always cleared afterwards.
 */
private fun <T> withOnWindowLayerType(block: () -> T): T {
    val flags = Class.forName("androidx.compose.ui.ComposeFeatureFlags")
    val featureFlag = flags.getMethod("getLayerType").invoke(flags.getField("INSTANCE").get(null))
    val overrideField = featureFlag.javaClass.getDeclaredField("override")
    overrideField.isAccessible = true
    val onWindow =
        Class.forName("androidx.compose.ui.LayerType").enumConstants.single {
            (it as Enum<*>).name == "OnWindow"
        }
    overrideField.set(featureFlag, onWindow)
    try {
        return block()
    } finally {
        overrideField.set(featureFlag, null)
    }
}

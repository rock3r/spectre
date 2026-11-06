@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout

/**
 * Validation for #563: an OnWindow popup (`compose.layers.type=WINDOW`, hosted by Compose's
 * internal `WindowComposeSceneLayer`) opened from a `ComposePanel` embedded in a plain Swing
 * `JFrame` / `JDialog` must surface as a popup surface, exactly once, with readable semantics.
 *
 * Unlike [PopupLayerVariantsValidationTest], this class does not need its own Gradle task with
 * `-Dcompose.layers.type=WINDOW`: it forces the layer type for the panels it creates through
 * [withOnWindowLayerType]. `validationTest` forks one JVM per class, so the override never leaks.
 */
class EmbeddedPanelOnWindowPopupValidationTest {

    private val windowsToDispose = mutableListOf<Window>()

    @BeforeEach
    fun assumeDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        // Same upstream skiko crash as PopupLayerVariantsValidationTest (SKIKO-1132 / spectre#56):
        // the OnWindow layer kills the JVM on Windows + JBR.
        assumeFalse(
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
            "compose.layers.type=WINDOW currently crashes skiko on Windows (SKIKO-1132 / #56)",
        )
    }

    @AfterTest
    fun tearDown() {
        onEdt { windowsToDispose.forEach(Window::dispose) }
        windowsToDispose.clear()
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks OnWindow popup opened from a ComposePanel embedded in a JFrame`() {
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

        assertSinglePopupSurface(owner = frame)
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks OnWindow popup opened from a wrapped ComposePanel embedded in a JDialog`() {
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

        assertSinglePopupSurface(owner = dialog)
    }

    private fun assertSinglePopupSurface(owner: Window) {
        val automator = ComposeAutomator.inProcess()
        val popupSurface = awaitPopupSurface(automator, owner)
        assertNotNull(
            popupSurface,
            "Expected a popup surface owned by ${owner.javaClass.simpleName} holding " +
                "'$POPUP_TAG'; tracked: ${automator.surfaceIds()}",
        )
        assertTrue(popupSurface.isPopup, "The overlay layer surface must be reported as a popup")
        assertNull(popupSurface.composePanel, "Overlay layer windows have no ComposePanel")

        val surfacesForLayerWindow = automator.windows.filter { it.window === popupSurface.window }
        assertEquals(
            1,
            surfacesForLayerWindow.size,
            "The layer window must be tracked once, got ${surfacesForLayerWindow.map { it.surfaceId }}",
        )
        assertNotNull(
            automator.findOneByTestTag(HOST_TAG),
            "The embedded host panel's own content must stay discoverable",
        )
    }

    private fun awaitPopupSurface(automator: ComposeAutomator, owner: Window): TrackedWindow? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCOVERY_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            automator.refreshWindows()
            val popupNode = automator.findOneByTestTag(POPUP_TAG)
            val surface = automator.windows.firstOrNull { it.surfaceId == popupNode?.surfaceId }
            if (surface != null && surface.window.owner === owner) return surface
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun composePanelWithPopup(): ComposePanel =
        ComposePanel().apply {
            preferredSize = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
            setContent {
                // Open the popup one frame later. Composed during the panel's first composition
                // (inside addNotify), an OnWindow popup fails: Compose's container doesn't know
                // its window yet, so WindowComposeSceneLayer's requireNotNull(window) throws.
                var showPopup by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { showPopup = true }
                Box(Modifier.size(HOST_CONTENT_DP.dp).testTag(HOST_TAG)) {
                    if (showPopup) {
                        Popup { Box(Modifier.size(POPUP_SIZE_DP.dp).testTag(POPUP_TAG)) }
                    }
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
        const val HOST_TAG = "embedded-host"
        const val POPUP_TAG = "embedded-popup"
    }
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
 * slot (the hook behind Compose's own `withOverride`), so it is set reflectively here and always
 * cleared afterwards.
 */
internal fun <T> withOnWindowLayerType(block: () -> T): T {
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

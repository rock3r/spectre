package dev.sebastiano.spectre.core.perf

import androidx.compose.runtime.Recomposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow
import java.awt.Window

/**
 * Resolves the [Recomposer] that drives a Compose Desktop surface.
 *
 * The traversal mirrors `OverlayLayerInspector`: walk the private CMP host chain by field name, so
 * a renamed internal field degrades gracefully (returns `null`) instead of crashing the rest of the
 * automator. Same multi-version policy as overlays (#322): single pinned-Compose chain,
 * degrade-to-empty/null — no adapter matrix for 1.0
 * (`docs/spikes/209-injection/overlay-adapter-policy.md`). CMP 1.12 moved the live [Recomposer]
 * onto `ComposeSceneMediator.frameRecomposer`; the chain is:
 * ```
 * ComposeWindow.composePanel  (ComposeWindowPanel)
 *   ._composeContainer         (ComposeContainer)
 *   .mediator                  (ComposeSceneMediator)
 *   .frameRecomposer           (FrameRecomposer; `getFrameRecomposer()` if the field is missing)
 *   .recomposer                (Recomposer)
 * ```
 *
 * `ComposePanel` enters the same chain at `_composeContainer`, so the inspector accepts either a
 * `ComposeWindow` or a `ComposePanel` as the starting host. Overlay-popup recomposers are not
 * resolved by this MVP; they live behind a separate chain inside `WindowComposeSceneLayer` and will
 * be wired in a follow-up.
 */
@InternalSpectreApi
public object RecomposerInspector {

    private const val COMPOSE_PANEL_FIELD = "composePanel"
    private const val COMPOSE_CONTAINER_FIELD = "_composeContainer"
    private const val MEDIATOR_FIELD = "mediator"
    private const val FRAME_RECOMPOSER_FIELD = "frameRecomposer"
    private const val FRAME_RECOMPOSER_GETTER = "getFrameRecomposer"
    private const val RECOMPOSER_FIELD = "recomposer"
    private const val COMPOSITION_CONTEXT_GETTER = "getCompositionContext"

    /**
     * Returns the [Recomposer] for [trackedWindow]'s main surface, or `null` for surfaces this MVP
     * does not yet resolve (overlay popups) or hosts that don't match the expected internal shape.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    public fun findRecomposer(trackedWindow: TrackedWindow): Recomposer? {
        // Overlay popups currently report through `overlaySemanticsOwners`; their recomposers live
        // on the layer's own mediator, which we don't yet traverse. Returning null is the explicit
        // "not yet supported" signal — RecompositionMonitor skips those surfaces.
        if (trackedWindow.overlaySemanticsOwners != null) return null

        // Prefer the embedded ComposePanel when present (covers both ComposeWindow's internal panel
        // and stand-alone embedded panels). Fall back to the window itself for ComposeWindow.
        val composePanel: ComposePanel? = trackedWindow.composePanel
        if (composePanel != null) {
            return findRecomposerInHostChainBelowComposeContainer(composePanel)
        }
        val window: Window = trackedWindow.window
        if (window is ComposeWindow) {
            return findRecomposerInHostChain(window)
        }
        return null
    }

    /**
     * Walks the full chain starting from a host that owns a `composePanel` field (i.e. a
     * `ComposeWindow`). Internal so unit tests in the same module can drive it against a fake
     * hierarchy without widening the JVM-public surface — production callers go through
     * [findRecomposer], which encodes which host to start the walk from.
     */
    internal fun findRecomposerInHostChain(host: Any?): Recomposer? {
        if (host == null) return null
        val composePanel = readField(host, COMPOSE_PANEL_FIELD) ?: return null
        return findRecomposerInHostChainBelowComposeContainer(composePanel)
    }

    private fun findRecomposerInHostChainBelowComposeContainer(panelHost: Any): Recomposer? {
        val composeContainer = readField(panelHost, COMPOSE_CONTAINER_FIELD) ?: return null
        val mediator = readField(composeContainer, MEDIATOR_FIELD) ?: return null
        // CMP 1.12 stores the live Recomposer on FrameRecomposer. The field is private; the
        // getter is public and is the fallback when a host only exposes the accessor.
        val frameRecomposer =
            readField(mediator, FRAME_RECOMPOSER_FIELD)
                ?: invokeGetter(mediator, FRAME_RECOMPOSER_GETTER)
                ?: return null
        if (frameRecomposer is Recomposer) return frameRecomposer
        val recomposer =
            readField(frameRecomposer, RECOMPOSER_FIELD)
                ?: invokeGetter(frameRecomposer, COMPOSITION_CONTEXT_GETTER)
        return recomposer as? Recomposer
    }

    private fun invokeGetter(target: Any, methodName: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val method = cls.getDeclaredMethod(methodName)
                method.isAccessible = true
                return method.invoke(target)
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            } catch (_: ReflectiveOperationException) {
                return null
            }
        }
        return null
    }

    private fun readField(target: Any, fieldName: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: ReflectiveOperationException) {
                return null
            }
        }
        return null
    }
}

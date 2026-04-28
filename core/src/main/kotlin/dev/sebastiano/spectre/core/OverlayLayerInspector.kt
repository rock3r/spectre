package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsOwner
import java.awt.Window

/**
 * Surfaces the semantics of Compose Desktop "OnWindow" popups (the `compose.layers.type=WINDOW`
 * mode) from outside the `androidx.compose.ui` package.
 *
 * Compose builds OnWindow popups inside an internal `WindowComposeSceneLayer` whose `mediator` is
 * private and whose host `JLayeredPaneWithTransparencyHack` is not a `ComposePanel`, so the public
 * `ComposeWindow.semanticsOwners` accessor only returns the main-scene owners — popup owners are
 * unreachable through the public API. Reading them needs reflection across three private fields:
 * - `ComposeWindow.composePanel` (the `ComposeWindowPanel`)
 * - `ComposeWindowPanel._composeContainer` (the `ComposeContainer`)
 * - `ComposeContainer.layers` (the `MutableList<DesktopComposeSceneLayer>` populated by
 *   `attachLayer` / `detachLayer`) Each `WindowComposeSceneLayer` then exposes a private `mediator:
 *   ComposeSceneMediator?` whose `semanticsOwners` collection is what we want.
 *
 * Tracked as #39. The reflection is purposely concentrated here so a future change in Compose's
 * internals can be adapted in one place — `findOverlayLayerWindows` returns an empty list rather
 * than throwing if any field is missing or renamed, so the rest of the automator keeps working
 * against newer Compose versions while a contributor updates this file.
 */
internal object OverlayLayerInspector {

    /**
     * Returns one entry per overlay-layer popup (currently the OnWindow case) hosted by the given
     * [composeWindow]. Each entry pairs the popup's host AWT [Window] with a snapshot accessor for
     * the popup's semantics owners. The accessor is a function rather than a captured collection so
     * callers always observe the live state at read time.
     *
     * Returns an empty list if Compose's internals don't match the expected shape (e.g. the
     * `composePanel` / `_composeContainer` / `layers` chain has been renamed) — degrade gracefully
     * rather than crash the whole automator.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun findOverlayLayerWindows(composeWindow: ComposeWindow): List<OverlayLayerEntry> {
        val composePanel = readField(composeWindow, "composePanel") ?: return emptyList()
        val composeContainer = readField(composePanel, "_composeContainer") ?: return emptyList()
        val layers = readField(composeContainer, "layers") as? List<*> ?: return emptyList()
        return layers.mapNotNull { layer -> layer?.let(::overlayLayerEntryOrNull) }
    }

    private fun overlayLayerEntryOrNull(layer: Any): OverlayLayerEntry? {
        // Only WindowComposeSceneLayer carries a JDialog `layerWindow`; the other
        // `DesktopComposeSceneLayer` subclasses for OnSameCanvas / OnComponent don't expose a
        // separate window, so we filter by presence of the field name rather than by class
        // reference (the class is internal).
        val layerWindow = readField(layer, "layerWindow") as? Window ?: return null
        return OverlayLayerEntry(
            window = layerWindow,
            semanticsOwnersAccessor = { readSemanticsOwners(layer) },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSemanticsOwners(layer: Any): Collection<SemanticsOwner> {
        val mediator = readField(layer, "mediator") ?: return emptyList()
        // `ComposeSceneMediator.semanticsOwners` is a delegated property — `val semanticsOwners by
        // semanticsOwnerManager::semanticsOwners` — so it has no JVM backing field and reading via
        // `getDeclaredField` returns null. The getter method exists under the conventional name
        // (`getSemanticsOwners`) and works regardless of how the property is implemented.
        val owners =
            invokeGetter(mediator, "getSemanticsOwners") as? Collection<SemanticsOwner>
        return owners ?: emptyList()
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

    /**
     * Reflects [fieldName] from [target] (or any superclass), making it accessible. Returns the
     * field value or `null` if the field is missing, the access fails, or the value is `null`. This
     * is the only place that swallows `ReflectiveOperationException` — every other reflection call
     * site in this file goes through here so we degrade uniformly.
     */
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

/**
 * One overlay-layer popup discovered through reflection. The semantics owners are read lazily so
 * each call returns the live collection — Compose's mediator updates `semanticsOwners` as the
 * popup's content recomposes.
 */
internal data class OverlayLayerEntry(
    val window: Window,
    val semanticsOwnersAccessor: () -> Collection<SemanticsOwner>,
)

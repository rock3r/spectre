@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.FocusEvent
import javax.swing.SwingUtilities

// How SyntheticRobotAdapter finds Compose content in an AWT hierarchy: the component that should
// get synthetic keys, and the Skiko canvas that should take AWT focus on a synthetic press.

/**
 * The Compose surface that should get keys when AWT has no focus owner and nothing is under the
 * pointer: the surface whose semantics tree holds the focused node (an owned popup included), or
 * else the first Compose host in the highest-priority window.
 */
internal fun findComposeKeyEventTarget(windows: List<Window>): Component? {
    val candidates = windows.asReversed()
    return candidates.firstNotNullOfOrNull(::findFocusedComposeSurface)
        ?: candidates.firstNotNullOfOrNull { window -> findComposeHostIn(window) }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun findFocusedComposeSurface(window: Window): Component? {
    if (!window.isShowing) return null
    if (window is ComposeWindow && window.semanticsOwners.hasFocusedNode()) {
        return findComposeHostIn(window) ?: window
    }
    findComposePanels(window)
        .firstOrNull { it.semanticsOwners.hasFocusedNode() }
        ?.let {
            return it
        }
    // Compose's OnWindow popup layers live in their own owned dialog, outside any ComposePanel.
    val owner = window.owner as? ComposeWindow ?: return null
    val layer =
        OverlayLayerInspector.findOverlayLayerWindows(owner).firstOrNull { it.window === window }
    return window.takeIf { layer != null && layer.semanticsOwnersAccessor().hasFocusedNode() }
}

private fun Collection<SemanticsOwner>.hasFocusedNode(): Boolean = any {
    it.rootSemanticsNode.hasFocusedNode()
}

private fun SemanticsNode.hasFocusedNode(): Boolean =
    config.getOrNull(SemanticsProperties.Focused) == true || children.any { it.hasFocusedNode() }

private fun findComposeHostIn(component: Component): Component? {
    if (component.isComposeHostComponent()) return component
    if (component !is Container) return null
    return component.components.firstNotNullOfOrNull(::findComposeHostIn)
}

internal fun Component.composeHostAncestorOrSelf(): Component? {
    var current: Component? = this
    while (current != null) {
        if (current.isComposeHostComponent()) return current
        current = current.parent
    }
    return null
}

private fun Component.isComposeHostComponent(): Boolean = javaClass.isComposeHostClass()

/**
 * Whether this class is, or extends, a component that hosts Compose content: the Compose host
 * panels, or the Skiko layers Compose renders and listens for keys through. Compose uses anonymous
 * subclasses of the Skiko layers, so an exact class-name match would miss them.
 */
internal fun Class<*>.isComposeHostClass(): Boolean =
    extendsClassNamed(COMPOSE_PANEL_CLASS_NAMES) || extendsClassNamed(SKIKO_LAYER_CLASS_NAMES)

private fun Class<*>.extendsClassNamed(names: Set<String>): Boolean =
    generateSequence(this) { it.superclass }.any { it.name in names }

/**
 * Moves AWT focus to the Compose canvas under a synthetic press, as a real click does.
 *
 * Without it a Swing field that held focus keeps it after a synthetic click on Compose content:
 * Compose focuses its node, but `Window.focusOwner` still names the Swing field, so the next keys
 * go there. Only Compose canvases are focused here; Swing components request focus on press
 * themselves where their look and feel wants it, and a plain panel must not steal focus.
 */
internal fun Component.focusComposeCanvasOnPress() {
    val canvas =
        generateSequence(this) { it.parent }
            .firstOrNull { it.javaClass.extendsClassNamed(SKIKO_LAYER_CLASS_NAMES) } ?: return
    val keyTarget = canvas.findKeyListenerSelfOrDescendant() ?: canvas
    if (!keyTarget.isFocusable) return
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    if (focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, canvas)) return
    keyTarget.requestFocusInWindow(FocusEvent.Cause.MOUSE_EVENT)
}

/**
 * Returns this component if it has any registered AWT [java.awt.event.KeyListener]s, otherwise the
 * first such descendant in a depth-first walk, or `null` if none is found.
 *
 * Used to find the Skiko canvas under a `ComposeWindowPanel` (`ComposeSceneMediator` registers its
 * key listener on `contentComponent`, not on the outer panel). Walking by structural class name is
 * fragile across Compose versions; checking for a registered key listener is the behavioural
 * property we actually care about and keeps plain-Swing fixtures working too.
 */
internal fun Component.findKeyListenerSelfOrDescendant(): Component? {
    if (keyListeners.isNotEmpty()) return this
    return findKeyListenerDescendant()
}

private fun Component.findKeyListenerDescendant(): Component? {
    if (this !is Container) return null
    for (child in components) {
        if (child.keyListeners.isNotEmpty()) return child
        val nested = child.findKeyListenerDescendant()
        if (nested != null) return nested
    }
    return null
}

private val COMPOSE_PANEL_CLASS_NAMES: Set<String> =
    setOf("androidx.compose.ui.awt.ComposePanel", "androidx.compose.ui.awt.ComposeWindowPanel")
private val SKIKO_LAYER_CLASS_NAMES: Set<String> =
    setOf("org.jetbrains.skiko.SkiaLayer", "org.jetbrains.skiko.swing.SkiaSwingLayer")

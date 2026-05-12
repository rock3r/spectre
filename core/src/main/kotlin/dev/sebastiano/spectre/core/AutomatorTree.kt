@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role

/**
 * Read-only snapshot of every Spectre-visible Compose surface and the nodes in it.
 *
 * Produced by [ComposeAutomator.tree]; each [AutomatorWindow] in [windows] is a main window or
 * popup root with a stable [AutomatorWindow.surfaceId]. Use this as the starting point when you
 * need the full picture rather than a single match — most user-facing queries
 * ([ComposeAutomator.findByTestTag], etc.) go through this snapshot too.
 */
public class AutomatorTree internal constructor(private val windows: List<AutomatorWindow>) {

    public fun windows(): List<AutomatorWindow> = windows

    public fun window(windowIndex: Int): AutomatorWindow = windows[windowIndex]

    public fun allNodes(): List<AutomatorNode> = windows.flatMap(AutomatorWindow::allNodes)

    public fun roots(): List<AutomatorNode> = windows.flatMap(AutomatorWindow::roots)
}

/**
 * A single Compose surface — a main window or a popup root — within an [AutomatorTree] snapshot.
 *
 * Surfaces are stable across refreshes by [surfaceId]; [windowIndex] is the position inside the
 * tree at the time the snapshot was taken and is not a long-lived identifier. Popups ([isPopup] ==
 * `true`) come from the Compose popup layer and are listed alongside their hosting main window. The
 * `findBy*` methods filter the surface's nodes using the same matchers as the top-level
 * [ComposeAutomator] queries.
 */
public class AutomatorWindow
internal constructor(
    public val windowIndex: Int,
    internal val trackedWindow: TrackedWindow,
    private val nodes: List<AutomatorNode>,
) {

    public val surfaceId: String = trackedWindow.surfaceId
    public val isPopup: Boolean = trackedWindow.isPopup

    public fun allNodes(): List<AutomatorNode> = nodes

    public fun roots(): List<AutomatorNode> = nodes.filter { it.parent == null }

    public fun findByTestTag(tag: String): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasTestTag(tag)::matches)

    public fun findOneByTestTag(tag: String): AutomatorNode? = findByTestTag(tag).firstOrNull()

    public fun findByText(query: TextQuery): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasText(query)::matches)

    public fun findByText(text: String, exact: Boolean = true): List<AutomatorNode> =
        findByText(
            if (exact) {
                TextQuery.exact(text)
            } else {
                TextQuery.substring(text, ignoreCase = true)
            }
        )

    public fun findOneByText(query: TextQuery): AutomatorNode? = findByText(query).firstOrNull()

    public fun findOneByText(text: String, exact: Boolean = true): AutomatorNode? =
        findByText(text, exact).firstOrNull()

    public fun findByContentDescription(description: String): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasContentDescription(description)::matches)

    public fun findByRole(role: Role): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasRole(role)::matches)
}

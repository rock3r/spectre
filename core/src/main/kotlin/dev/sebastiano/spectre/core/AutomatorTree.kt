@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role

class AutomatorTree internal constructor(private val windows: List<AutomatorWindow>) {

    fun windows(): List<AutomatorWindow> = windows

    fun window(windowIndex: Int): AutomatorWindow = windows[windowIndex]

    fun allNodes(): List<AutomatorNode> = windows.flatMap(AutomatorWindow::allNodes)

    fun roots(): List<AutomatorNode> = windows.flatMap(AutomatorWindow::roots)
}

class AutomatorWindow
internal constructor(
    val windowIndex: Int,
    internal val trackedWindow: TrackedWindow,
    private val nodes: List<AutomatorNode>,
) {

    val surfaceId: String = trackedWindow.surfaceId
    val isPopup: Boolean = trackedWindow.isPopup

    fun allNodes(): List<AutomatorNode> = nodes

    fun roots(): List<AutomatorNode> = nodes.filter { it.parent == null }

    fun findByTestTag(tag: String): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasTestTag(tag)::matches)

    fun findOneByTestTag(tag: String): AutomatorNode? = findByTestTag(tag).firstOrNull()

    fun findByText(query: TextQuery): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasText(query)::matches)

    fun findByText(text: String, exact: Boolean = true): List<AutomatorNode> =
        findByText(
            if (exact) {
                TextQuery.exact(text)
            } else {
                TextQuery.substring(text, ignoreCase = true)
            }
        )

    fun findOneByText(query: TextQuery): AutomatorNode? = findByText(query).firstOrNull()

    fun findOneByText(text: String, exact: Boolean = true): AutomatorNode? =
        findByText(text, exact).firstOrNull()

    fun findByContentDescription(description: String): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasContentDescription(description)::matches)

    fun findByRole(role: Role): List<AutomatorNode> =
        nodes.filter(NodeMatchers.hasRole(role)::matches)
}

package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner

class SemanticsReader {

    fun readAllNodes(trackedWindows: List<TrackedWindow>): List<AutomatorNode> = readOnEdt {
        collectAllNodes(trackedWindows)
    }

    fun findByTestTag(tag: String, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        readAllNodes(trackedWindows).filter { it.testTag == tag }

    fun findByText(
        text: String,
        trackedWindows: List<TrackedWindow>,
        exact: Boolean = true,
    ): List<AutomatorNode> =
        readAllNodes(trackedWindows).filter { node ->
            val nodeText = node.text ?: return@filter false
            if (exact) nodeText == text else nodeText.contains(text, ignoreCase = true)
        }

    fun findByContentDescription(
        description: String,
        trackedWindows: List<TrackedWindow>,
    ): List<AutomatorNode> =
        readAllNodes(trackedWindows).filter { it.contentDescription == description }

    fun findByRole(role: Role, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        readAllNodes(trackedWindows).filter { it.role == role }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun collectAllNodes(trackedWindows: List<TrackedWindow>): List<AutomatorNode> {
        val nodes = mutableListOf<AutomatorNode>()
        for (trackedWindow in trackedWindows) {
            val owners = getOwnersForWindow(trackedWindow)
            for ((ownerIndex, owner) in owners.withIndex()) {
                traverseTree(owner.rootSemanticsNode, trackedWindow, ownerIndex, nodes)
            }
        }
        return nodes
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun getOwnersForWindow(trackedWindow: TrackedWindow): Collection<SemanticsOwner> {
        val window = trackedWindow.window
        if (window is ComposeWindow) return window.semanticsOwners

        // For non-ComposeWindow containers, find embedded ComposePanels
        return findComposePanels(window).flatMap { it.semanticsOwners }
    }

    private fun traverseTree(
        node: SemanticsNode,
        trackedWindow: TrackedWindow,
        ownerIndex: Int,
        result: MutableList<AutomatorNode>,
    ) {
        val key = NodeKey(trackedWindow.surfaceId, ownerIndex, node.id)
        result += AutomatorNode(key = key, semanticsNode = node, trackedWindow = trackedWindow)
        for (child in node.children) {
            traverseTree(child, trackedWindow, ownerIndex, result)
        }
    }
}

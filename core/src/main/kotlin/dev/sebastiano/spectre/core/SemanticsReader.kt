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
        readOnEdt {
            collectAllNodes(trackedWindows).filter { it.testTag == tag }
        }

    fun findByText(
        text: String,
        trackedWindows: List<TrackedWindow>,
        exact: Boolean = true,
    ): List<AutomatorNode> = readOnEdt {
        collectAllNodes(trackedWindows).filter { node ->
            node.texts.any { matchesText(it, text, exact) } ||
                matchesText(node.editableText, text, exact)
        }
    }

    fun findByContentDescription(
        description: String,
        trackedWindows: List<TrackedWindow>,
    ): List<AutomatorNode> = readOnEdt {
        collectAllNodes(trackedWindows).filter { node ->
            node.contentDescriptions.any { it == description }
        }
    }

    fun findByRole(role: Role, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        readOnEdt {
            collectAllNodes(trackedWindows).filter { it.role == role }
        }

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

        val panel = trackedWindow.composePanel ?: return emptyList()
        return panel.semanticsOwners
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

private fun matchesText(nodeText: String?, query: String, exact: Boolean): Boolean {
    if (nodeText == null) return false
    return if (exact) nodeText == query else nodeText.contains(query, ignoreCase = true)
}

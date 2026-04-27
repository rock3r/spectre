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

    fun findByText(query: TextQuery, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        readOnEdt {
            collectAllNodes(trackedWindows).filter { node ->
                node.texts.any(query::matches) || query.matches(node.editableText)
            }
        }

    fun findByText(
        text: String,
        trackedWindows: List<TrackedWindow>,
        exact: Boolean = true,
    ): List<AutomatorNode> =
        findByText(
            query =
                if (exact) {
                    TextQuery.exact(text)
                } else {
                    TextQuery.substring(text, ignoreCase = true)
                },
            trackedWindows = trackedWindows,
        )

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
        val entries = mutableListOf<NodeEntry>()
        for (trackedWindow in trackedWindows) {
            val owners = getOwnersForWindow(trackedWindow)
            for ((ownerIndex, owner) in owners.withIndex()) {
                traverseTree(
                    owner.rootSemanticsNode,
                    trackedWindow,
                    ownerIndex,
                    parentKey = null,
                    entries,
                )
            }
        }

        val parentKeyByNodeKey = entries.associate { it.key to it.parentKey }
        val nodeKeysByParentKey =
            entries.groupBy(keySelector = NodeEntry::parentKey, valueTransform = NodeEntry::key)
        val nodesByKey = mutableMapOf<NodeKey, AutomatorNode>()
        val relations =
            object : NodeRelations {
                override fun parentOf(key: NodeKey): AutomatorNode? =
                    parentKeyByNodeKey[key]?.let(nodesByKey::get)

                override fun childrenOf(key: NodeKey): List<AutomatorNode> =
                    nodeKeysByParentKey[key].orEmpty().mapNotNull(nodesByKey::get)
            }

        for (entry in entries) {
            nodesByKey[entry.key] =
                AutomatorNode(
                    key = entry.key,
                    semanticsNode = entry.node,
                    trackedWindow = entry.trackedWindow,
                    relations = relations,
                )
        }

        return entries.mapNotNull { entry -> nodesByKey[entry.key] }
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
        parentKey: NodeKey?,
        result: MutableList<NodeEntry>,
    ) {
        val key = NodeKey(trackedWindow.surfaceId, ownerIndex, node.id)
        result +=
            NodeEntry(key = key, node = node, trackedWindow = trackedWindow, parentKey = parentKey)
        for (child in node.children) {
            traverseTree(child, trackedWindow, ownerIndex, parentKey = key, result)
        }
    }
}

private data class NodeEntry(
    val key: NodeKey,
    val node: SemanticsNode,
    val trackedWindow: TrackedWindow,
    val parentKey: NodeKey?,
)

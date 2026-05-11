@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner

internal class SemanticsReader {

    fun readAllNodes(trackedWindows: List<TrackedWindow>): List<AutomatorNode> = readOnEdt {
        collectAllNodes(trackedWindows)
    }

    fun findByTestTag(tag: String, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        findMatching(trackedWindows, NodeMatchers.liveHasTestTag(tag))

    fun findByText(query: TextQuery, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        findMatching(trackedWindows, NodeMatchers.liveHasText(query))

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
    ): List<AutomatorNode> =
        findMatching(trackedWindows, NodeMatchers.liveHasContentDescription(description))

    fun findByRole(role: Role, trackedWindows: List<TrackedWindow>): List<AutomatorNode> =
        findMatching(trackedWindows, NodeMatchers.liveHasRole(role))

    /**
     * Walks the live semantics tree on the EDT, marking entries that satisfy [matcher] against the
     * raw [SemanticsNode]. Every traversed entry is still projected into an [AutomatorNode] so
     * parent/child relations remain valid for matched nodes; only matched ones are returned.
     */
    private fun findMatching(
        trackedWindows: List<TrackedWindow>,
        matcher: LiveNodeMatcher,
    ): List<AutomatorNode> = readOnEdt {
        val entries = collectEntries(trackedWindows)
        val nodes = projectEntriesToNodes(entries)
        entries.filter { matcher.matches(it.node) }.mapNotNull { nodes[it.key] }
    }

    private fun collectAllNodes(trackedWindows: List<TrackedWindow>): List<AutomatorNode> {
        val entries = collectEntries(trackedWindows)
        val nodes = projectEntriesToNodes(entries)
        return entries.mapNotNull { nodes[it.key] }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun collectEntries(trackedWindows: List<TrackedWindow>): List<NodeEntry> {
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
        return entries
    }

    private fun projectEntriesToNodes(entries: List<NodeEntry>): Map<NodeKey, AutomatorNode> {
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
        return nodesByKey
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun getOwnersForWindow(trackedWindow: TrackedWindow): Collection<SemanticsOwner> {
        // Reflective overlay-layer accessor wins when present — those tracked windows are the
        // OnWindow popup case (`compose.layers.type=WINDOW`) where neither the host JDialog is a
        // ComposeWindow nor its content tree contains a ComposePanel.
        trackedWindow.overlaySemanticsOwners?.let {
            return it()
        }
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

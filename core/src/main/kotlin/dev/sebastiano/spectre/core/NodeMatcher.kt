package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Internal predicate used by both the live ([SemanticsReader]) and snapshot ([AutomatorWindow])
 * lookup paths. Kept internal: the public selector surface stays the high-level `findByTestTag` /
 * `findByText` / `findByContentDescription` / `findByRole` functions on [ComposeAutomator].
 *
 * The two variants exist because the snapshot path has already projected each [SemanticsNode] into
 * an [AutomatorNode] (with eagerly-snapshotted properties) before matching, so it must match
 * against [AutomatorNode]; the live path matches against [SemanticsNode] directly to avoid an extra
 * allocation per traversal.
 */
internal fun interface SnapshotNodeMatcher {
    fun matches(node: AutomatorNode): Boolean
}

internal fun interface LiveNodeMatcher {
    fun matches(node: SemanticsNode): Boolean
}

internal infix fun SnapshotNodeMatcher.and(other: SnapshotNodeMatcher): SnapshotNodeMatcher =
    SnapshotNodeMatcher { node ->
        this@and.matches(node) && other.matches(node)
    }

internal infix fun LiveNodeMatcher.and(other: LiveNodeMatcher): LiveNodeMatcher =
    LiveNodeMatcher { node ->
        this@and.matches(node) && other.matches(node)
    }

internal object NodeMatchers {

    fun hasTestTag(tag: String): SnapshotNodeMatcher = SnapshotNodeMatcher { it.testTag == tag }

    fun hasText(query: TextQuery): SnapshotNodeMatcher = SnapshotNodeMatcher { node ->
        node.texts.any(query::matches) || query.matches(node.editableText)
    }

    fun hasContentDescription(description: String): SnapshotNodeMatcher =
        SnapshotNodeMatcher { node ->
            node.contentDescriptions.any { it == description }
        }

    fun hasRole(role: Role): SnapshotNodeMatcher = SnapshotNodeMatcher { it.role == role }

    fun liveHasTestTag(tag: String): LiveNodeMatcher = LiveNodeMatcher { node ->
        node.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    fun liveHasText(query: TextQuery): LiveNodeMatcher = LiveNodeMatcher { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
        texts.any(query::matches) || query.matches(editable)
    }

    fun liveHasContentDescription(description: String): LiveNodeMatcher = LiveNodeMatcher { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
            it == description
        }
    }

    fun liveHasRole(role: Role): LiveNodeMatcher = LiveNodeMatcher { node ->
        node.config.getOrNull(SemanticsProperties.Role) == role
    }
}

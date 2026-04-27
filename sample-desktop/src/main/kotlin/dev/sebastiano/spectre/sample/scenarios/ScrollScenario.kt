package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Scenario: scrollable list with many items.
 *
 * Renders a `LazyColumn` of [SCROLL_ITEM_COUNT] entries. Validation for #14 (nice-to-validate perf
 * checks) drives this to measure:
 * - tree-traversal cost on large semantics trees
 * - bounds drift while scrolling (the live `boundsInWindow` snapshot in `AutomatorNode` means click
 *   coordinates should follow the visible item even mid-scroll)
 * - LazyColumn add/remove churn across `refreshWindows()` calls
 *
 * Tags: `scroll.list`, `scroll.item.<index>` for each visible item.
 */
val ScrollScenario: Scenario =
    Scenario(
        title = "Scrollable list",
        testTag = "scenario.scroll",
        unblocks = "#14 large-tree perf; #8 bounds-drift validation while scrolling.",
        content = { ScrollContent() },
    )

private const val SCROLL_ITEM_COUNT: Int = 200

@Composable
private fun ScrollContent() {
    val state = rememberLazyListState()
    val items = remember { (0 until SCROLL_ITEM_COUNT).toList() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$SCROLL_ITEM_COUNT items. Scroll to exercise tree refresh + live bounds.")
            HorizontalDivider()
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().testTag("scroll.list"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items) { index ->
                    Text(
                        text = "Item #$index",
                        modifier =
                            Modifier.fillMaxWidth().padding(8.dp).testTag("scroll.item.$index"),
                    )
                }
            }
        }
    }
}

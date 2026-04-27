package dev.sebastiano.spectre.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.sebastiano.spectre.sample.scenarios.ALL_SCENARIOS
import dev.sebastiano.spectre.sample.scenarios.Scenario

/**
 * Sample harness shell.
 *
 * Renders a left-side picker listing every [Scenario] and a right-side pane showing the currently
 * selected scenario's content. The picker is a `LazyColumn` so the list itself is realistic for
 * bounds-drift testing. Each picker entry is a `TextButton` with the scenario's stable testTag, so
 * automation flows can navigate by tag.
 */
@Composable
fun App(modifier: Modifier = Modifier) {
    MaterialTheme {
        var selected by remember { mutableStateOf(ALL_SCENARIOS.first()) }
        Surface(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "Spectre sample",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("header"),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ScenarioPicker(
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.width(260.dp),
                    )
                    Card(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = selected.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("scenario.title"),
                            )
                            Text(
                                text = selected.unblocks,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("scenario.unblocks"),
                            )
                            HorizontalDivider()
                            selected.content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioPicker(
    selected: Scenario,
    onSelect: (Scenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        LazyColumn(modifier = Modifier.padding(8.dp).testTag("scenario.picker")) {
            items(ALL_SCENARIOS) { scenario ->
                TextButton(
                    onClick = { onSelect(scenario) },
                    modifier = Modifier.fillMaxWidth().testTag(scenario.testTag),
                ) {
                    Text(
                        text = if (scenario == selected) "▸ ${scenario.title}" else scenario.title,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

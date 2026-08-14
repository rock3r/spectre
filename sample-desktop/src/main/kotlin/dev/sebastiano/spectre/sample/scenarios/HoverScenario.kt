package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Scenario: hover target + park pad for pointer-move verbs (#433).
 *
 * `moveTo` / `moveBy` must enter the hover target without incrementing the click counter, then
 * leave it again. Tags: `hoverTarget`, `hoverPark`, `hoverStatus`, `hoverClicks`.
 */
val HoverScenario: Scenario =
    Scenario(
        title = "Pointer hover",
        testTag = "scenario.hover",
        unblocks = "#433 moveTo/moveBy hover without click or drag.",
        content = { HoverContent() },
    )

@Composable
private fun HoverContent() {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var clicks by remember { mutableIntStateOf(0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (hovered) "hovered" else "idle",
                modifier = Modifier.testTag("hoverStatus"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = "clicks:$clicks", modifier = Modifier.testTag("hoverClicks"))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(
                    modifier =
                        Modifier.size(160.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                clicks++
                            }
                            .testTag("hoverTarget"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("hover target")
                }
                Box(
                    modifier =
                        Modifier.size(160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("hoverPark"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("park")
                }
            }
        }
    }
}

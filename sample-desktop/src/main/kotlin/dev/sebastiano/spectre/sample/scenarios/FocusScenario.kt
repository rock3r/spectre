package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Scenario: explicit focus management.
 *
 * Three text fields with a "Focus second field" button. Validation for #8 (focus runtime behaviour,
 * especially around Robot input on macOS) checks that:
 * - the per-field `isFocused` flag in `AutomatorNode` reflects the actual focus state
 * - clicking the button transfers focus correctly via `FocusRequester`
 * - typing after the focus jump lands in the right field
 *
 * Tags: `focus.field.first|second|third`, `focus.jumpButton`.
 */
val FocusScenario: Scenario =
    Scenario(
        title = "Focus traversal",
        testTag = "scenario.focus",
        unblocks = "#8 focus state surfacing, Robot input fidelity after explicit focus jumps.",
        content = { FocusContent() },
    )

@Composable
private fun FocusContent() {
    val firstFocus = remember { FocusRequester() }
    val secondFocus = remember { FocusRequester() }
    val thirdFocus = remember { FocusRequester() }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var third by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Three focusable fields. The button explicitly requests focus on the second.")

            OutlinedTextField(
                value = first,
                onValueChange = { first = it },
                label = { Text("First") },
                modifier =
                    Modifier.fillMaxWidth().focusRequester(firstFocus).testTag("focus.field.first"),
            )
            OutlinedTextField(
                value = second,
                onValueChange = { second = it },
                label = { Text("Second") },
                modifier =
                    Modifier.fillMaxWidth()
                        .focusRequester(secondFocus)
                        .testTag("focus.field.second"),
            )
            OutlinedTextField(
                value = third,
                onValueChange = { third = it },
                label = { Text("Third") },
                modifier =
                    Modifier.fillMaxWidth().focusRequester(thirdFocus).testTag("focus.field.third"),
            )
            Button(
                onClick = { secondFocus.requestFocus() },
                modifier = Modifier.testTag("focus.jumpButton"),
            ) {
                Text("Focus second field")
            }
        }
    }
}

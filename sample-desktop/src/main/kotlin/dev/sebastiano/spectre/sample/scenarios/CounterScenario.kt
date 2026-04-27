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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Scenario: counter button + text input.
 *
 * The original Spectre demo surface — kept as the v0 baseline so the demo flow in `Main.kt` still
 * has something to exercise. Tags: `incrementButton`, `textInput`, `echoText`.
 */
val CounterScenario: Scenario =
    Scenario(
        title = "Counter + text input",
        testTag = "scenario.counter",
        unblocks = "Baseline (#5 click + typeText paths).",
        content = { CounterContent() },
    )

@Composable
private fun CounterContent() {
    var counter by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { counter++ }, modifier = Modifier.testTag("incrementButton")) {
                Text("Count: $counter")
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Type something") },
                modifier = Modifier.fillMaxWidth().testTag("textInput"),
            )

            if (inputText.isNotEmpty()) {
                Text(text = "You typed: $inputText", modifier = Modifier.testTag("echoText"))
            }
        }
    }
}

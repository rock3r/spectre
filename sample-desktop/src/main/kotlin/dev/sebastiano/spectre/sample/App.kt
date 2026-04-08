package dev.sebastiano.spectre.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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

@Composable
fun App(modifier: Modifier = Modifier) {
    MaterialTheme {
        var counter by remember { mutableIntStateOf(0) }
        var inputText by remember { mutableStateOf("") }

        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Spectre sample",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("header"),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { counter++ },
                        modifier = Modifier.testTag("incrementButton"),
                    ) {
                        Text("Count: $counter")
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Type something") },
                        modifier = Modifier.fillMaxWidth().testTag("textInput"),
                    )

                    if (inputText.isNotEmpty()) {
                        Text(
                            text = "You typed: $inputText",
                            modifier = Modifier.testTag("echoText"),
                        )
                    }
                }
            }
        }
    }
}

package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Scenario: in-canvas popup.
 *
 * Renders a `Popup` composable above the main content. Validation for #7 (multi-window/popup
 * support) drives this to confirm the automator surfaces the popup's semantics owner regardless of
 * the active layer mode (`OnSameCanvas` vs `OnComponent` vs `OnWindow`).
 *
 * Tags: `popup.toggleButton`, `popup.body`, `popup.dismissButton`.
 */
val PopupScenario: Scenario =
    Scenario(
        title = "Popup (in-canvas)",
        testTag = "scenario.popup",
        unblocks = "#7 popup discovery, #8 popup-bound geometry.",
        content = { PopupContent() },
    )

@Composable
private fun PopupContent() {
    var visible by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Click the button to show a popup. The popup's tree is owned by a separate semantics root."
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { visible = !visible },
                    modifier = Modifier.testTag("popup.toggleButton"),
                ) {
                    Text(if (visible) "Hide popup" else "Show popup")
                }

                if (visible) {
                    Popup(
                        properties = PopupProperties(focusable = true),
                        onDismissRequest = { visible = false },
                    ) {
                        Surface(
                            modifier = Modifier.padding(16.dp).testTag("popup.body"),
                            tonalElevation = 4.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Hello from a popup",
                                    modifier = Modifier.testTag("popup.text"),
                                )
                                Button(
                                    onClick = { visible = false },
                                    modifier = Modifier.testTag("popup.dismissButton"),
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

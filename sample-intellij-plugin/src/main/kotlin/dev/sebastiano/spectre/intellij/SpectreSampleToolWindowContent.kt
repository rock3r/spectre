package dev.sebastiano.spectre.intellij

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Tool-window scenarios that mirror the desktop sample's `CounterScenario` and `PopupScenario`, but
 * wired through Jewel components so they render with the host IDE's L&F. Spectre's automator should
 * resolve every tagged node here exactly as it does for the standalone desktop sample.
 *
 * Tags:
 * - `ide.counter.button` — increments the counter; proves click + state-update fidelity.
 * - `ide.counter.text` — always present, shows the current count.
 * - `ide.popup.toggleButton` — opens / closes the in-canvas popup.
 * - `ide.popup.body` / `ide.popup.text` / `ide.popup.dismissButton` — same shape as the desktop
 *   `PopupScenario`, so the IDE-hosted variant exercises the same popup discovery code path.
 */
@Composable
internal fun SpectreSampleToolWindowContent() {
    var counter by remember { mutableIntStateOf(0) }
    var popupVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Spectre is automating this Jewel-hosted ComposePanel inside an IntelliJ tool window.")

        DefaultButton(onClick = { counter++ }, modifier = Modifier.testTag("ide.counter.button")) {
            Text("Click count: $counter")
        }
        Text(text = "Counter: $counter", modifier = Modifier.testTag("ide.counter.text"))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DefaultButton(
                onClick = { popupVisible = !popupVisible },
                modifier = Modifier.testTag("ide.popup.toggleButton"),
            ) {
                Text(if (popupVisible) "Hide popup" else "Show popup")
            }

            if (popupVisible) {
                Popup(
                    properties = PopupProperties(focusable = true),
                    onDismissRequest = { popupVisible = false },
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).testTag("ide.popup.body"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Hello from a Jewel-hosted popup",
                            modifier = Modifier.testTag("ide.popup.text"),
                        )
                        DefaultButton(
                            onClick = { popupVisible = false },
                            modifier = Modifier.testTag("ide.popup.dismissButton"),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}

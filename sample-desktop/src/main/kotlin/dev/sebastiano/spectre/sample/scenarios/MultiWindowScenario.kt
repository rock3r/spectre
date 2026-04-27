package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

/**
 * Scenario: multi-window — opens a secondary `Window` for popup-as-separate-window validation.
 *
 * Spectre's `WindowTracker` discovers extra `Window` instances via `Window.getOwnedWindows()` /
 * platform-specific introspection. Validation for #7 drives this scenario to confirm:
 * - the secondary window's `TrackedWindow.surfaceId` is distinct from the main window
 * - nodes inside the secondary window are addressable by `findByTestTag` after a `refreshWindows()`
 * - the `isPopup` flag matches the actual window kind (true for the secondary `Window` here, since
 *   CMP exposes it as a popup-class window when launched from inside a parent composition)
 *
 * Tags: `multiwindow.toggleButton`, `multiwindow.secondary.text`,
 * `multiwindow.secondary.dismissButton`.
 */
val MultiWindowScenario: Scenario =
    Scenario(
        title = "Multi-window (secondary Window)",
        testTag = "scenario.multiwindow",
        unblocks = "#7 multi-window discovery, #8 surfaceId stability across windows.",
        content = { MultiWindowContent() },
    )

@Composable
private fun MultiWindowContent() {
    var open by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Opens a separate Compose Window. WindowTracker should discover both surfaces.")
            Button(
                onClick = { open = !open },
                modifier = Modifier.testTag("multiwindow.toggleButton"),
            ) {
                Text(if (open) "Close secondary window" else "Open secondary window")
            }
        }
    }

    if (open) {
        val state = rememberWindowState(width = 320.dp, height = 200.dp)
        Window(
            onCloseRequest = { open = false },
            state = state,
            title = "Spectre — secondary window",
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "I am a secondary window",
                    modifier = Modifier.testTag("multiwindow.secondary.text"),
                )
                Button(
                    onClick = { open = false },
                    modifier = Modifier.testTag("multiwindow.secondary.dismissButton"),
                ) {
                    Text("Close me")
                }
            }
        }
    }
}

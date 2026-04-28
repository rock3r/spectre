package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JDialog
import javax.swing.SwingUtilities

/**
 * Scenario: a Swing `JDialog` hosting a Compose `ComposePanel` with tagged content.
 *
 * Validation for #7 (`ComposePanel.semanticsOwners` for JDialog-hosted popup content) drives this
 * to confirm that:
 * - `WindowTracker` surfaces the JDialog as a tracked window
 * - `findOneByTestTag` resolves nodes inside the dialog's `ComposePanel` semantics owner
 *
 * Tags: `jdialog.toggleButton` (in main), `jdialog.body` and `jdialog.dismissButton` (in dialog).
 */
val JDialogScenario: Scenario =
    Scenario(
        title = "JDialog hosting ComposePanel",
        testTag = "scenario.jdialog",
        unblocks = "#7 ComposePanel.semanticsOwners coverage for JDialog-hosted popup content.",
        content = { JDialogContent() },
    )

@Composable
private fun JDialogContent() {
    var dialogOpen by remember { mutableStateOf(false) }
    var dialogRef by remember { mutableStateOf<JDialog?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Open a Swing JDialog hosting a Compose ComposePanel. The automator should " +
                    "surface the dialog as its own tracked window with discoverable Compose nodes."
            )
            Button(
                onClick = { dialogOpen = !dialogOpen },
                modifier = Modifier.testTag("jdialog.toggleButton"),
            ) {
                Text(if (dialogOpen) "Close JDialog" else "Open JDialog")
            }
        }
    }

    DisposableEffect(dialogOpen) {
        if (dialogOpen) {
            // Parent the dialog to the currently-visible top-level ComposeWindow rather than
            // letting Swing fall back to its hidden `SharedOwnerFrame`. The synthetic input
            // driver discovers popup targets by walking `rootWindow.ownedWindows`, so a
            // `null`-owned JDialog would be invisible to it (and to any owner-relative API like
            // `setLocationRelativeTo(parent)`).
            val parent =
                java.awt.Frame.getFrames().firstOrNull { it.isShowing && it !is JDialog }
                    as? java.awt.Frame
            val dialog =
                JDialog(parent, "Spectre — JDialog popup", false).apply {
                    val composePanel = ComposePanel()
                    composePanel.setContent {
                        JDialogPanelContent(onDismiss = { dialogOpen = false })
                    }
                    contentPane.layout = BorderLayout()
                    contentPane.add(composePanel, BorderLayout.CENTER)
                    preferredSize = Dimension(DIALOG_WIDTH_PX, DIALOG_HEIGHT_PX)
                    pack()
                    setLocationRelativeTo(parent)
                    isVisible = true
                }
            dialogRef = dialog
        }
        onDispose {
            dialogRef?.let { d -> SwingUtilities.invokeLater { d.dispose() } }
            dialogRef = null
        }
    }
}

private const val DIALOG_WIDTH_PX: Int = 360
private const val DIALOG_HEIGHT_PX: Int = 180

@Composable
private fun JDialogPanelContent(onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Hello from a JDialog-hosted ComposePanel",
            modifier = Modifier.testTag("jdialog.body"),
        )
        Button(onClick = onDismiss, modifier = Modifier.testTag("jdialog.dismissButton")) {
            Text("Dismiss")
        }
    }
}

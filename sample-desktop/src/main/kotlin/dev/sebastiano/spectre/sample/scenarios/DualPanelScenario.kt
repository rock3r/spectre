package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * Scenario: same-window extra semantics roots.
 *
 * Renders a Compose tree alongside a `SwingPanel` host (which is itself a Swing component embedded
 * in the Compose hierarchy). Validation for #7 confirms that `WindowTracker` surfaces both the
 * primary Compose panel and the embedded panel as first-class entries in `windows`, so that
 * `findOneByTestTag` resolves nodes from either subtree without the test caring which root owns the
 * node.
 *
 * Tags: `dual.left.text`, `dual.right.swing.label` (the Swing label has no Compose semantics — we
 * tag the surrounding Compose Text instead so the assertion has something to anchor on).
 */
val DualPanelScenario: Scenario =
    Scenario(
        title = "Same-window dual roots (Compose + SwingPanel)",
        testTag = "scenario.dualpanel",
        unblocks = "#7 same-window extra semantics roots discovered as first-class entries.",
        content = { DualPanelContent() },
    )

@Composable
private fun DualPanelContent() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Compose root on the left, an embedded SwingPanel on the right. Both should be " +
                    "reachable through the automator's `windows` snapshot."
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = "Left pane (Compose)", modifier = Modifier.testTag("dual.left.text"))
                SwingPanel(
                    modifier = Modifier.fillMaxWidth().testTag("dual.right.swingHost"),
                    factory = {
                        JLabel("Right pane (Swing JLabel)", SwingConstants.CENTER).apply {
                            name = "dual.right.swing.label"
                        }
                    },
                )
            }
        }
    }
}

package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Scenario: known-position widgets for HiDPI coordinate validation.
 *
 * Two squares positioned at fixed dp offsets from the parent's top-left corner. Validation for #8
 * (HiDPI mapping on Retina) drives this to confirm:
 * - `AutomatorNode.boundsOnScreen` for `hidpi.target.<x>x<y>` matches the on-screen pixel position
 *   once converted via [HiDpiMapper]
 * - the computed `centerOnScreen` lands inside the right pixel after `boundsInWindow ÷ density`
 *
 * Tags: `hidpi.target.<dpX>x<dpY>`.
 */
val HiDpiScenario: Scenario =
    Scenario(
        title = "HiDPI coordinate targets",
        testTag = "scenario.hidpi",
        unblocks = "#8 boundsInWindow → screen pixel mapping on Retina.",
        content = { HiDpiContent() },
    )

@Composable
private fun HiDpiContent() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Two known-position targets. Use these to validate Retina coordinate maths.")
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                HiDpiTarget(offsetXDp = 40, offsetYDp = 0)
                HiDpiTarget(offsetXDp = 200, offsetYDp = 80)
            }
        }
    }
}

@Composable
private fun HiDpiTarget(offsetXDp: Int, offsetYDp: Int) {
    Box(
        modifier =
            Modifier.offset(x = offsetXDp.dp, y = offsetYDp.dp)
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary)
                .testTag("hidpi.target.${offsetXDp}x${offsetYDp}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${offsetXDp},${offsetYDp}",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

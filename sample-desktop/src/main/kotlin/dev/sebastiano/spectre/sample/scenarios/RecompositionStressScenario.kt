package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Scenario: heavy recomposition stress.
 *
 * When `running` is true the scenario renders a fluctuating number of items (cycles 0..32 every
 * 16ms via a coroutine inside `LaunchedEffect`) so the semantics tree mutates rapidly. Validation
 * for #8 (thread safety under heavy recomposition / streaming updates) drives this and asserts that
 * repeated `refreshWindows()` + `findOneByTestTag` calls don't crash, don't return stale or
 * mid-mutation snapshots, and consistently see at least the always-present ticker text node.
 *
 * Tags: `recomp.toggleButton`, `recomp.ticker`, `recomp.item.<i>` for each currently-rendered item.
 */
val RecompositionStressScenario: Scenario =
    Scenario(
        title = "Recomposition stress",
        testTag = "scenario.recomposition",
        unblocks = "#8 thread-safety / snapshot coherence under heavy recomposition.",
        content = { RecompositionStressContent() },
    )

private const val MAX_ITEMS: Int = 32
private const val STEP_DELAY_MS: Long = 16L

@Composable
private fun RecompositionStressContent() {
    var running by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            tick = (tick + 1) % (MAX_ITEMS + 1)
            delay(STEP_DELAY_MS)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Toggle the ticker to churn the semantics tree at ~60Hz. " +
                    "Reading via the automator should never crash or return mid-mutation state."
            )
            Button(
                onClick = { running = !running },
                modifier = Modifier.testTag("recomp.toggleButton"),
            ) {
                Text(if (running) "Stop" else "Start")
            }
            Text(text = "Tick: $tick", modifier = Modifier.testTag("recomp.ticker"))
            for (i in 0 until tick) {
                Text(text = "Item #$i", modifier = Modifier.testTag("recomp.item.$i"))
            }
        }
    }
}

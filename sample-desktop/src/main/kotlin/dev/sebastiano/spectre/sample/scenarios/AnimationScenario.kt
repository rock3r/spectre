package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Scenario: continuously running infinite animation alongside an event-driven settle target.
 *
 * Validation for #8 (wait contract under real animations / background work) drives this to confirm
 * that `eventually { ... }` against a settle-time predicate still completes even while a separate
 * part of the UI is animating forever — the wait helper should not block forever because the
 * animation never "settles".
 *
 * Tags: `anim.spinner` (infinite rotation), `anim.toggleButton`, `anim.settled` (only present after
 * the user toggles the settle button).
 */
val AnimationScenario: Scenario =
    Scenario(
        title = "Infinite animation + event-driven settle",
        testTag = "scenario.animation",
        unblocks = "#8 wait-contract correctness while a background animation never settles.",
        content = { AnimationContent() },
    )

@Composable
private fun AnimationContent() {
    var settled by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition()
    val angle by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(animation = tween(SPIN_PERIOD_MS, easing = LinearEasing)),
        )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A perpetually rotating square. The wait helper should still observe " +
                    "event-driven settles (button click → `anim.settled` appears) without " +
                    "blocking on the never-settling animation."
            )
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .rotate(angle)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("anim.spinner")
            )
            Button(onClick = { settled = true }, modifier = Modifier.testTag("anim.toggleButton")) {
                Text(if (settled) "Settled" else "Settle")
            }
            if (settled) {
                Text(
                    "Settled — predicate target observed.",
                    modifier = Modifier.testTag("anim.settled"),
                )
            }
        }
    }
}

private const val SPIN_PERIOD_MS: Int = 800

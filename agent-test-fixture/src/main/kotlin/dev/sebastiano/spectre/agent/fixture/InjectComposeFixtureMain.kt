@file:Suppress("MatchingDeclarationName")

package dev.sebastiano.spectre.agent.fixture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Compose-only fixture for #209 injection e2e: same tagged UI as [main] but **never** references
 * `dev.sebastiano.spectre.core` so the target JVM can run without spectre-core on its classpath.
 * The agent injects relocated core at attach time.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun injectMain() {
    System.err.println("[inject-fixture] entered injectMain() pid=${ProcessHandle.current().pid()}")
    System.err.flush()

    val panelRef = java.util.concurrent.atomic.AtomicReference<ComposePanel>()
    SwingUtilities.invokeAndWait {
        val frame =
            JFrame(SPECTRE_FIXTURE_WINDOW_TITLE).apply {
                defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
                size = Dimension(FIXTURE_WIDTH_PX, FIXTURE_HEIGHT_PX)
                setLocationRelativeTo(null)
                isAlwaysOnTop = true
            }

        val composePanel =
            ComposePanel().apply {
                setContent {
                    var typed by remember { mutableStateOf("") }
                    MaterialTheme {
                        Column(modifier = Modifier.padding(FIXTURE_PADDING_DP.dp)) {
                            Text(
                                text = "Spectre inject fixture",
                                modifier = Modifier.testTag(TAG_LABEL),
                            )
                            TextField(
                                value = typed,
                                onValueChange = { typed = it },
                                modifier = Modifier.testTag(TAG_TEXT_FIELD),
                            )
                            Button(
                                onClick = { typed = "$BUTTON_CLICKED_PREFIX$typed" },
                                modifier = Modifier.testTag(TAG_BUTTON),
                            ) {
                                Text(text = "Click me")
                            }
                        }
                    }
                }
            }

        frame.contentPane.add(composePanel)
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
        panelRef.set(composePanel)
    }

    val panel = panelRef.get()
    val deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS
    while (injectSemanticsOwnerCountOnEdt(panel) == 0) {
        check(System.currentTimeMillis() < deadline) {
            "Compose semantics tree did not populate within ${READY_POLL_TIMEOUT_MS}ms"
        }
        Thread.sleep(READY_POLL_INTERVAL_MS)
    }
    System.err.println("[inject-fixture] semantics ready; emitting READY")
    System.err.flush()

    val pid = ProcessHandle.current().pid()
    // Explicitly does NOT touch ComposeAutomator — that is the whole point of this fixture.
    println("$READY_SENTINEL pid=$pid inject=true")
    System.out.flush()

    Thread.currentThread().join()
}

/** JVM entry point for ProcessBuilder (`…InjectComposeFixtureMainKt`). */
fun main() {
    injectMain()
}

private const val FIXTURE_PADDING_DP: Int = 16
private const val FIXTURE_WIDTH_PX: Int = 320
private const val FIXTURE_HEIGHT_PX: Int = 240
private const val READY_POLL_INTERVAL_MS: Long = 25
private const val READY_POLL_TIMEOUT_MS: Long = 15_000

@OptIn(ExperimentalComposeUiApi::class)
private fun injectSemanticsOwnerCountOnEdt(panel: ComposePanel): Int {
    val box = IntArray(1)
    SwingUtilities.invokeAndWait { box[0] = panel.semanticsOwners.size }
    return box[0]
}

@file:Suppress("MatchingDeclarationName") // file-level utilities; `main` is the entry point.

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
 * Compose Desktop fixture used by `:agent`'s `AgentAttachIntegrationTest`. Puts up one JFrame with
 * a [ComposePanel] holding three tagged nodes — one [Text], one [TextField], one [Button] — so the
 * integration test can assert against real, non-empty semantics-tree data (non-empty windows,
 * findByTestTag matches, DTO bounds, click).
 *
 * **Why not Compose Desktop's `application { Window { ... } }`?** That runtime starts an
 * application-scoped coroutine and never returns from main; combined with `ProcessBuilder`-spawned
 * stdout reading, the first-frame `LaunchedEffect` printing the READY sentinel turned out to be
 * unreliable across machines (Compose Desktop's main loop sometimes blocked before the sentinel
 * reached the parent reader). A plain `JFrame + ComposePanel` is the same Compose Desktop substrate
 * Spectre's IntelliJ-hosted path uses, behaves deterministically under headless detection, and lets
 * `main()` print the sentinel itself after the EDT has finished its first paint pass.
 *
 * Lives in a separate Gradle module from `:agent` because applying the Compose Compiler plugin
 * module-wide to `:agent` would pull `@Composable` processing into the production agent runtime,
 * which has no business knowing about Compose.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Emit a diagnostic line *before* anything else so the parent's watchdog at least
    // knows the fixture booted, even if AWT init or Compose blow up afterwards.
    System.err.println("[fixture] entered main() pid=${ProcessHandle.current().pid()}")
    System.err.flush()

    // Touch ComposeAutomator at startup so the class is on the loaded-classes list when the
    // agent attaches — matches the realistic case where the target app uses Spectre directly.
    val automatorClassName = dev.sebastiano.spectre.core.ComposeAutomator::class.java.name

    val panelRef = java.util.concurrent.atomic.AtomicReference<ComposePanel>()
    SwingUtilities.invokeAndWait {
        val frame =
            JFrame(SPECTRE_FIXTURE_WINDOW_TITLE).apply {
                defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
                size = Dimension(FIXTURE_WIDTH_PX, FIXTURE_HEIGHT_PX)
                setLocationRelativeTo(null)
            }

        val composePanel =
            ComposePanel().apply {
                setContent {
                    var typed by remember { mutableStateOf("") }
                    MaterialTheme {
                        Column(modifier = Modifier.padding(SPECTRE_FIXTURE_PADDING_DP.dp)) {
                            Text(
                                text = "Spectre agent fixture",
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

    // Race-free readiness signal: poll `ComposePanel.semanticsOwners` until it's non-empty.
    // That's exactly the property Spectre's `WindowTracker.trackActivePanels` checks — see
    // `core/.../WindowTracker.kt:108`. If we print READY before this, the parent's
    // `windows()` call might come back empty (semantics tree not yet populated by Compose's
    // first composition), and the integration test's strict assertions would race against
    // Compose's frame schedule. Polling kills the race deterministically.
    //
    // **EDT discipline**: `semanticsOwners` is backed by Compose state that's mutated on the
    // AWT EDT. Reading it from the main thread can deadlock against Compose's scheduling
    // (Compose may be waiting on the EDT to advance the same composition we're inspecting).
    // We marshal each read to the EDT via `SwingUtilities.invokeAndWait` to match Spectre's
    // own `readOnEdt` convention.
    val panel = panelRef.get()
    val deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS
    while (semanticsOwnerCountOnEdt(panel) == 0) {
        check(System.currentTimeMillis() < deadline) {
            "Compose semantics tree did not populate within ${READY_POLL_TIMEOUT_MS}ms"
        }
        Thread.sleep(READY_POLL_INTERVAL_MS)
    }
    System.err.println(
        "[fixture] semantics ready (${semanticsOwnerCountOnEdt(panel)} owners); emitting READY"
    )
    System.err.flush()

    val pid = ProcessHandle.current().pid()
    println("$READY_SENTINEL pid=$pid touchedClass=$automatorClassName")
    System.out.flush()

    // Idle until the parent kills us. Swing's EDT keeps the JVM alive in the background.
    Thread.currentThread().join()
}

/** Sentinel the parent integration test scans `stdout` for to confirm the window is up. */
public const val READY_SENTINEL: String = "SPECTRE-FIXTURE-READY"

/** The window title — assertable from the integration test via `WindowSummaryDto.title`. */
public const val SPECTRE_FIXTURE_WINDOW_TITLE: String = "Spectre Agent Test Fixture"

/** Prefix the Button click handler prepends to the TextField's value. */
public const val BUTTON_CLICKED_PREFIX: String = "clicked:"

/** Test tag on the heading [androidx.compose.material3.Text]. */
public const val TAG_LABEL: String = "agent-fixture-label"

/** Test tag on the [androidx.compose.material3.TextField]. */
public const val TAG_TEXT_FIELD: String = "agent-fixture-text-field"

/** Test tag on the [androidx.compose.material3.Button]. */
public const val TAG_BUTTON: String = "agent-fixture-button"

private const val SPECTRE_FIXTURE_PADDING_DP: Int = 16
private const val FIXTURE_WIDTH_PX: Int = 320
private const val FIXTURE_HEIGHT_PX: Int = 240
private const val READY_POLL_INTERVAL_MS: Long = 25
private const val READY_POLL_TIMEOUT_MS: Long = 15_000

/**
 * Reads [ComposePanel.semanticsOwners].size on the AWT EDT and returns it. Wraps the access in
 * [SwingUtilities.invokeAndWait] so we never touch Compose state from the wrong thread; matches
 * Spectre's own `readOnEdt` convention in `core/EdtUtils.kt`.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun semanticsOwnerCountOnEdt(panel: ComposePanel): Int {
    val box = IntArray(1)
    SwingUtilities.invokeAndWait { box[0] = panel.semanticsOwners.size }
    return box[0]
}

@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class)
@file:Suppress("MatchingDeclarationName") // file-level utilities; `main` is the entry point.

package dev.sebastiano.spectre.agent.fixture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Compose Desktop fixture used by `:agent`'s `AgentAttachIntegrationTest`. Puts up one JFrame with
 * a [ComposePanel] holding five tagged nodes — one [Text], one [TextField], one [Button], one
 * checked [Checkbox], and one [LinearProgressIndicator] — so the integration test can assert
 * against real, non-empty semantics-tree data (non-empty windows, findByTestTag matches, DTO
 * bounds, node state, click).
 *
 * **Why not Compose Desktop's `application { Window { ... } }`?** That runtime starts an
 * application-scoped coroutine and never returns from main; combined with `ProcessBuilder`-spawned
 * stdout reading, the first-frame `LaunchedEffect` printing the READY sentinel turned out to be
 * unreliable across machines (Compose Desktop's main loop sometimes blocked before the sentinel
 * reached the parent reader). A plain `JFrame + ComposePanel` is the same Compose Desktop substrate
 * Spectre's IntelliJ-hosted path uses, behaves deterministically under headless detection, and lets
 * `main()` print the sentinel itself after the EDT has finished its first paint pass.
 *
 * **Why `isAlwaysOnTop`?** The integration test drives the attached agent's *real* `java.awt.Robot`
 * click and keyboard paths against this window. On Windows, foreground-stealing prevention stops a
 * JVM spawned by another foreground process (the Gradle/IDE terminal running the test) from raising
 * its window via `toFront()` alone, so Robot clicks would land on the terminal and the text field
 * would never take focus — a spurious hard failure that isn't a real regression. `isAlwaysOnTop`
 * puts the fixture above the spawning terminal so Robot clicks hit Compose pixels and a genuine
 * click grants OS keyboard focus. This is the same pattern `sample-desktop`'s `WindowsRobotSmoke`
 * relies on (its finding #1). It's inert elsewhere: `LinuxRobotSmoke` keeps it on too (a no-op
 * under Xvfb's minimal focus stacking), and `MacOsRobotSmoke` documents it as unnecessary on macOS
 * (AppKit has no foreground-stealing prevention), so leaving it enabled there does no harm.
 *
 * Lives in a separate Gradle module from `:agent` because applying the Compose Compiler plugin
 * module-wide to `:agent` would pull `@Composable` processing into the production agent runtime,
 * which has no business knowing about Compose.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    announceBootAndWatchParent()

    // Touch ComposeAutomator at startup so the class is on the loaded-classes list when the
    // agent attaches — matches the realistic case where the target app uses Spectre directly.
    val automatorClassName = dev.sebastiano.spectre.core.ComposeAutomator::class.java.name

    // Optional chrome modes for attach parity regressions (#362):
    // - SPECTRE_FIXTURE_UNDECORATED=true → undecorated JFrame (custom-chrome-like host)
    // - SPECTRE_FIXTURE_DELAYED_SHOW=true → pack/displayable first, compose, then show (onboarding-
    //   style delayed visibility). WindowTracker must still list the surface once semantics exist.
    // - SPECTRE_FIXTURE_JWINDOW_POPUP=true → also open an untitled, non-focusable JWindow owned by
    //   the frame and hosting a ComposePanel, like IntelliJ heavyweight popups (#605).
    val undecorated = System.getenv("SPECTRE_FIXTURE_UNDECORATED").toBoolean()
    val delayedShow = System.getenv("SPECTRE_FIXTURE_DELAYED_SHOW").toBoolean()
    val jwindowPopup = System.getenv("SPECTRE_FIXTURE_JWINDOW_POPUP").toBoolean()
    System.err.println(
        "[fixture] chrome undecorated=$undecorated delayedShow=$delayedShow " +
            "jwindowPopup=$jwindowPopup"
    )
    System.err.flush()

    val panelRef = java.util.concurrent.atomic.AtomicReference<ComposePanel>()
    SwingUtilities.invokeAndWait {
        val frame =
            JFrame(SPECTRE_FIXTURE_WINDOW_TITLE).apply {
                defaultCloseOperation = FIXTURE_DEFAULT_CLOSE_OPERATION
                size = Dimension(FIXTURE_WIDTH_PX, FIXTURE_HEIGHT_PX)
                setLocationRelativeTo(null)
                // Keep the fixture above the spawning terminal so the agent's real java.awt.Robot
                // clicks land on Compose pixels and can take OS keyboard focus on Windows. See the
                // `isAlwaysOnTop` rationale in this file's KDoc.
                isAlwaysOnTop = true
                if (undecorated) {
                    isUndecorated = true
                }
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
                                modifier =
                                    Modifier.testTag(TAG_BUTTON).semantics {
                                        role = Role.Button
                                        contentDescription = "fixture submit"
                                    },
                            ) {
                                Text(text = "Click me")
                            }
                            NodeStateRow()
                        }
                    }
                }
            }

        frame.contentPane.add(composePanel)
        if (delayedShow) {
            // Make the peer displayable without showing — mirrors Jewel onboarding's
            // visible=false first composition, then flip to visible.
            frame.pack()
            frame.isVisible = false
        } else {
            frame.isVisible = true
            frame.toFront()
            frame.requestFocus()
        }
        panelRef.set(composePanel)
    }

    if (delayedShow) {
        // Wait for semantics while still not showing, then show — attach may probe either phase.
        val panel = panelRef.get()
        val deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS
        while (semanticsOwnerCountOnEdt(panel) == 0) {
            check(System.currentTimeMillis() < deadline) {
                "Compose semantics tree did not populate within ${READY_POLL_TIMEOUT_MS}ms " +
                    "(delayed-show phase)"
            }
            Thread.sleep(READY_POLL_INTERVAL_MS)
        }
        SwingUtilities.invokeAndWait {
            val frames = java.awt.Frame.getFrames()
            for (frame in frames) {
                if (frame.title == SPECTRE_FIXTURE_WINDOW_TITLE) {
                    frame.isVisible = true
                    frame.toFront()
                    frame.requestFocus()
                    break
                }
            }
        }
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
    awaitSemantics(listOfNotNull(panel, if (jwindowPopup) openJWindowPopup() else null))
    System.err.println(
        "[fixture] semantics ready (${semanticsOwnerCountOnEdt(panel)} owners); emitting READY"
    )
    System.err.flush()

    val pid = ProcessHandle.current().pid()
    println("$READY_SENTINEL pid=$pid touchedClass=$automatorClassName")
    System.out.flush()

    // Idle until the parent kills us, or until it exits (see exitWhenParentExits). Swing's EDT
    // keeps the JVM alive in the background.
    Thread.currentThread().join()
}

/**
 * Emits a diagnostic line *before* anything else, so the parent's watchdog at least knows the
 * fixture booted even if AWT init or Compose blow up afterwards. Then ties this JVM's lifetime to
 * the process that launched it.
 */
private fun announceBootAndWatchParent() {
    System.err.println("[fixture] entered main() pid=${ProcessHandle.current().pid()}")
    System.err.flush()
    exitWhenParentExits()
}

/**
 * Parent-owned lifetime: the fixture blocks on the main thread until the spawning test destroys the
 * process. [WindowConstants.EXIT_ON_CLOSE] would [System.exit] on a close-box or WM close and look
 * like "exited after READY" during a shared-display `./gradlew check`.
 */
public const val FIXTURE_DEFAULT_CLOSE_OPERATION: Int = WindowConstants.DO_NOTHING_ON_CLOSE

/** Sentinel the parent integration test scans `stdout` for to confirm the window is up. */
public const val READY_SENTINEL: String = "SPECTRE-FIXTURE-READY"

/**
 * A checked [Checkbox] and a determinate [LinearProgressIndicator], so the integration test can
 * read real toggleable and progress semantics over the wire (#576).
 */
@Composable
private fun NodeStateRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A null onCheckedChange drops the toggleable semantics, so pass a no-op: the box stays
        // checked, and the test reads its ToggleableState.
        Checkbox(checked = true, onCheckedChange = {}, modifier = Modifier.testTag(TAG_CHECKBOX))
        LinearProgressIndicator(
            progress = { FIXTURE_PROGRESS },
            modifier = Modifier.testTag(TAG_PROGRESS),
        )
    }
}

private fun awaitSemantics(panels: List<ComposePanel>) {
    val deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS
    while (panels.any { semanticsOwnerCountOnEdt(it) == 0 }) {
        check(System.currentTimeMillis() < deadline) {
            "Compose semantics tree did not populate within ${READY_POLL_TIMEOUT_MS}ms"
        }
        Thread.sleep(READY_POLL_INTERVAL_MS)
    }
}

/**
 * Opens an untitled, non-focusable `JWindow` owned by the fixture frame, hosting a [ComposePanel]
 * with one solid [JWINDOW_POPUP_COLOR] box tagged [TAG_JWINDOW_POPUP]. Returns the panel.
 */
private fun openJWindowPopup(): ComposePanel {
    val ref = java.util.concurrent.atomic.AtomicReference<ComposePanel>()
    SwingUtilities.invokeAndWait {
        val owner = java.awt.Frame.getFrames().first { it.title == SPECTRE_FIXTURE_WINDOW_TITLE }
        val popupPanel =
            ComposePanel().apply {
                setContent {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color(JWINDOW_POPUP_COLOR))
                            .testTag(TAG_JWINDOW_POPUP)
                    )
                }
            }
        JWindow(owner).apply {
            focusableWindowState = false
            // Keep the popup above the always-on-top owner frame.
            isAlwaysOnTop = true
            contentPane.add(popupPanel)
            setBounds(
                owner.x + JWINDOW_POPUP_OFFSET_PX,
                owner.y + JWINDOW_POPUP_OFFSET_PX,
                JWINDOW_POPUP_WIDTH_PX,
                JWINDOW_POPUP_HEIGHT_PX,
            )
            isVisible = true
        }
        ref.set(popupPanel)
    }
    return ref.get()
}

/** Test tag on the solid box inside the optional JWindow popup. */
public const val TAG_JWINDOW_POPUP: String = "agent-fixture-jwindow-popup"

/** ARGB colour of the JWindow popup box. */
public const val JWINDOW_POPUP_COLOR: Long = 0xFFE07020

/** JWindow popup size in AWT units. */
public const val JWINDOW_POPUP_WIDTH_PX: Int = 240
public const val JWINDOW_POPUP_HEIGHT_PX: Int = 160

private const val JWINDOW_POPUP_OFFSET_PX: Int = 80

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

/** Test tag on the always-checked [androidx.compose.material3.Checkbox] (#576). */
public const val TAG_CHECKBOX: String = "agent-fixture-checkbox"

/** Test tag on the determinate [androidx.compose.material3.LinearProgressIndicator] (#576). */
public const val TAG_PROGRESS: String = "agent-fixture-progress"

/** Value the fixture's progress indicator reports, out of the default `0f..1f` range. */
public const val FIXTURE_PROGRESS: Float = 0.5f

private const val SPECTRE_FIXTURE_PADDING_DP: Int = 16
private const val FIXTURE_WIDTH_PX: Int = 320
private const val FIXTURE_HEIGHT_PX: Int = 300
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

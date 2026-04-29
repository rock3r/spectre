package dev.sebastiano.spectre.intellij

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.SemanticsReader
import dev.sebastiano.spectre.core.WindowTracker

/**
 * Manual validation entry point for #13: opens the "Spectre Sample" tool window and runs the
 * in-process automator against it, dumping the discovered semantics tree to the IDE log both before
 * and after triggering the popup so the Jewel-hosted popup discovery path is exercised end-to-end
 * (not just the always-visible counter widgets).
 *
 * Run via `./gradlew :sample-intellij-plugin:runIde`, then `Tools → Run Spectre Against the Sample
 * Tool Window`. The "ide.counter.text" / "ide.popup.toggleButton" / "ide.popup.body" / etc. nodes
 * from `SpectreSampleToolWindowContent` should appear in `idea.log`. This proves Spectre's
 * `WindowTracker` reaches the Jewel-hosted `ComposePanel` inside the IDE process and that
 * `SemanticsReader` reads its semantics owners — both checklist items on #13.
 *
 * Threading model:
 * - Polling and `Thread.sleep` happen on a pooled background thread, NEVER the EDT. Sleeping on the
 *   EDT would block AWT's event queue for the full poll budget and prevent Compose recomposition,
 *   so the panel-attach / popup-appear events we're polling for could never fire — the polls would
 *   always time out even though the tool window is healthy. (Caught by both Codex and Bugbot review
 *   on #43; the older single-`invokeLater` shape suffered from this race.)
 * - Compose-tree access (`refreshWindows()`, `findOneByTestTag`, semantics-tree reads) is
 *   marshalled back to the EDT inside the poll iteration via `invokeAndWait`. The ComposePanel's
 *   semantics owners must be read from the EDT — they're not thread-safe.
 * - `triggerOnClick` runs the semantics OnClick action on the EDT (same reason).
 */
class RunSpectreAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        showToolWindowThenAutomate(project)
    }

    private fun showToolWindowThenAutomate(project: Project) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            thisLogger().warn("Spectre Sample tool window not registered")
            return
        }
        // Activation must run on EDT (ToolWindow API contract). The activation callback fires
        // on EDT too — we immediately bounce off to a pooled thread so the polling loop
        // doesn't block AWT's event queue.
        toolWindow.activate(
            { ApplicationManager.getApplication().executeOnPooledThread { driveAutomator() } },
            /* autoFocusContents = */ true,
            /* forced = */ true,
        )
    }

    private fun driveAutomator() {
        val automator =
            ComposeAutomator.inProcess(
                windowTracker = WindowTracker(),
                semanticsReader = SemanticsReader(),
                robotDriver = RobotDriver.headless(),
            )
        val log = thisLogger()

        // 1. Wait for the Jewel `ComposePanel` to attach its semantics owner. Compose
        //    composition is asynchronous; the panel's first owners can land several frames
        //    after activation. Polling here happens on the pooled background thread (the
        //    `Thread.sleep` between ticks does NOT block EDT) and the per-tick Compose
        //    access is marshalled back to EDT.
        val panelReady = pollOnEdt {
            automator.refreshWindows()
            automator.windows.isNotEmpty() && automator.findOneByTestTag("ide.counter.text") != null
        }
        if (!panelReady) {
            log.warn(
                "Spectre saw no tracked windows / counter node within $POLL_BUDGET_MS ms. The " +
                    "tool window is open but its ComposePanel hasn't finished attaching its " +
                    "semantics owner — try invoking the action again."
            )
            return
        }

        // 2. Dump the initial tree (popup closed).
        log.info("[Spectre] tracked windows (initial): ${automator.windows.map { it.surfaceId }}")
        runOnEdt { dumpTaggedNodes(automator, "[Spectre] (initial)") }

        // 3. Open the popup so the Jewel-hosted popup discovery path actually runs. Without
        //    this the action would only ever observe the initial counter scenario nodes — the
        //    popup `body`/`text`/`dismissButton` nodes don't exist until after a click. We
        //    drive the click through Compose's `OnClick` semantics action (the headless
        //    `RobotDriver` can't reach the OS).
        val toggle = runOnEdt { automator.findOneByTestTag("ide.popup.toggleButton") }
        if (toggle == null) {
            log.warn("[Spectre] popup toggle node not discoverable — popup discovery skipped")
            return
        }
        runOnEdt { triggerOnClick(toggle) }

        val popupReady = pollOnEdt {
            automator.refreshWindows()
            automator.findOneByTestTag("ide.popup.body") != null
        }
        if (!popupReady) {
            log.warn(
                "[Spectre] popup body did not appear within $POLL_BUDGET_MS ms after toggling — " +
                    "Jewel popup discovery may have regressed (#39 territory)."
            )
            return
        }

        log.info(
            "[Spectre] tracked windows (with popup): ${automator.windows.map { it.surfaceId }}"
        )
        runOnEdt { dumpTaggedNodes(automator, "[Spectre] (with popup)") }
    }

    private fun dumpTaggedNodes(automator: ComposeAutomator, prefix: String) {
        val log = thisLogger()
        for (node in automator.allNodes()) {
            node.testTag ?: continue
            log.info("$prefix ${formatNode(node)}")
        }
    }

    /**
     * Invokes the Compose `OnClick` semantics action on [node] from the EDT — equivalent to a
     * synthetic click for the purpose of toggling state, without going through the OS input stack.
     * The `RobotDriver.headless()` we use here can't deliver real input, so we drive the semantics
     * tree directly. This is the same pattern Compose's own `composeTestRule.onNode` uses
     * internally. Caller is responsible for ensuring this runs on the EDT.
     */
    private fun triggerOnClick(node: AutomatorNode) {
        val onClick = node.semanticsNode.config.getOrNull(SemanticsActions.OnClick)
        onClick?.action?.invoke()
    }

    /**
     * Polls [predicate] until it returns true or [POLL_BUDGET_MS] elapses. Each tick runs
     * [predicate] on the EDT (so it can touch Compose semantics safely); the inter-tick sleep runs
     * on the calling thread (must NOT be the EDT — sleeping there freezes AWT event dispatching and
     * prevents Compose from recomposing the very state we're polling for).
     */
    private inline fun pollOnEdt(crossinline predicate: () -> Boolean): Boolean {
        check(!ApplicationManager.getApplication().isDispatchThread) {
            "pollOnEdt must NOT be called from the EDT — it would block recomposition. " +
                "Call from a pooled background thread (executeOnPooledThread)."
        }
        val deadline = System.nanoTime() + POLL_BUDGET_MS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            val matched = runOnEdt { runCatching { predicate() }.getOrDefault(false) }
            if (matched) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Runs [block] on the EDT and returns its result. Wrapper around `invokeAndWait` so the polling
     * loop can synchronously fold each Compose-touching tick back into its decision.
     */
    private inline fun <T> runOnEdt(crossinline block: () -> T): T {
        if (ApplicationManager.getApplication().isDispatchThread) return block()
        var result: T? = null
        @Suppress("UNCHECKED_CAST")
        ApplicationManager.getApplication().invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun formatNode(node: AutomatorNode): String = buildString {
        append("  ")
        append(node.testTag)
        node.text?.let { append(" text=\"$it\"") }
        node.editableText?.let { append(" editableText=\"$it\"") }
        append(" surface=").append(node.trackedWindow.surfaceId)
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Spectre Sample"
        const val POLL_BUDGET_MS: Long = 3_000L
        const val POLL_INTERVAL_MS: Long = 50L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}

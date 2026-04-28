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
        toolWindow.activate(
            { ApplicationManager.getApplication().invokeLater { driveAutomator() } },
            true,
            true,
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

        // 1. Wait for the Jewel `ComposePanel` to attach its semantics owner. The activation
        //    callback runs as soon as the tool window has been shown but Compose composition is
        //    asynchronous — on a cold IDE warmup the first `refreshWindows()` can land before
        //    the panel registers any owners. Poll for a bounded budget instead of failing on
        //    the very first tick.
        val panelReady = poll {
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
        dumpTaggedNodes(automator, "[Spectre] (initial)")

        // 3. Open the popup so the Jewel-hosted popup discovery path actually runs. Without
        //    this the action would only ever observe the initial counter scenario nodes — the
        //    popup `body`/`text`/`dismissButton` nodes don't exist until after a click. We
        //    drive the click through the in-process automator's `RobotDriver.headless()`
        //    (which can't reach the OS) so we fire the toggle by activating its semantics
        //    on-click action directly.
        val toggle = automator.findOneByTestTag("ide.popup.toggleButton")
        if (toggle == null) {
            log.warn("[Spectre] popup toggle node not discoverable — popup discovery skipped")
            return
        }
        triggerOnClick(toggle)

        val popupReady = poll {
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
        dumpTaggedNodes(automator, "[Spectre] (with popup)")
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
     * internally.
     */
    private fun triggerOnClick(node: AutomatorNode) {
        ApplicationManager.getApplication().invokeAndWait {
            val onClick = node.semanticsNode.config.getOrNull(SemanticsActions.OnClick)
            onClick?.action?.invoke()
        }
    }

    private inline fun poll(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + POLL_BUDGET_MS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
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

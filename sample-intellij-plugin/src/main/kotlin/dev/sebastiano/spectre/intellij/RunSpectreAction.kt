package dev.sebastiano.spectre.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.SemanticsReader
import dev.sebastiano.spectre.core.WindowTracker

/**
 * Manual validation entry point for #13: opens the "Spectre Sample" tool window and runs the
 * in-process automator against it, dumping the discovered semantics tree to the IDE log.
 *
 * Run via `./gradlew :sample-intellij-plugin:runIde`, then `Tools → Run Spectre Against the Sample
 * Tool Window`. The "ide.counter.text" / "ide.popup.toggleButton" / etc. nodes from
 * `SpectreSampleToolWindowContent` should appear in `idea.log`. This proves Spectre's
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
        // The Jewel `ComposePanel` only attaches its semantics owner once the tool window has
        // actually been shown. Activate first; only after the activation callback do we run the
        // automator, otherwise `WindowTracker.refresh()` finds zero panels and the dump is
        // empty even though the tool window exists.
        toolWindow.activate(
            { ApplicationManager.getApplication().invokeLater { dumpAutomatorTree(project) } },
            true,
            true,
        )
    }

    private fun dumpAutomatorTree(@Suppress("UNUSED_PARAMETER") project: Project) {
        val automator =
            ComposeAutomator.inProcess(
                windowTracker = WindowTracker(),
                semanticsReader = SemanticsReader(),
                robotDriver = RobotDriver.headless(),
            )
        automator.refreshWindows()
        val log = thisLogger()
        if (automator.windows.isEmpty()) {
            log.warn(
                "Spectre saw no tracked windows. Either the IDE has no visible Compose surface, " +
                    "or the tool window hasn't finished attaching its ComposePanel yet."
            )
            return
        }
        log.info("Spectre tracked windows: ${automator.windows.map { it.surfaceId }}")
        for (node in automator.allNodes()) {
            val tag = node.testTag ?: continue
            log.info(formatNode(node))
        }
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

        // Suppressed: kept here for future use when we wire a programmatic "open the tool window
        // when the project starts" hook for the remoteRobot follow-up.
        @Suppress("UNUSED_PARAMETER", "unused")
        fun listener(): ToolWindowManagerListener = object : ToolWindowManagerListener {}
    }
}

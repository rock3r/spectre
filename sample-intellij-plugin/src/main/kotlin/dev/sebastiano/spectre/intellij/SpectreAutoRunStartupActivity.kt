package dev.sebastiano.spectre.intellij

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.delay

/**
 * Diagnostic startup activity that fires [RunSpectreAction] automatically once the project is
 * ready, gated on the `-Dspectre.autorun=true` system property. Off by default — manual `Tools →
 * Run Spectre Against the Sample Tool Window` invocation is the normal interactive path.
 *
 * Used by repo maintainers to validate the action end-to-end without needing to drive the IDE menu
 * interactively (`./gradlew :sample-intellij-plugin:runIde -PspectreAutorun=true`, then `tail`
 * `idea.log` for the `[Spectre]` lines). Eliminates the need for an interactive smoke pass when
 * iterating on the action's plumbing.
 *
 * The short delay before invocation gives the tool window manager + Compose's first composition
 * pass time to land — without it the action's first `panelReady` poll would still succeed thanks to
 * its 3-second budget, but the wait would dominate the auto-run trace timing and obscure any
 * genuine attachment latency.
 */
class SpectreAutoRunStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (System.getProperty("spectre.autorun") != "true") return

        thisLogger()
            .info(
                "[Spectre] auto-run requested via -Dspectre.autorun=true; waiting briefly before firing the action"
            )
        delay(STARTUP_DELAY_MILLIS)

        val action = ActionManager.getInstance().getAction(ACTION_ID)
        if (action == null) {
            thisLogger().warn("[Spectre] auto-run aborted: action '$ACTION_ID' not registered")
            return
        }

        // ActionManager invocation must happen on EDT. The action itself bounces straight to a
        // pooled background thread for the polling loop, so this only blocks EDT for the
        // initial tool-window activation.
        ApplicationManager.getApplication().invokeLater {
            val event =
                AnActionEvent.createFromDataContext(
                    ActionPlaces.UNKNOWN,
                    null,
                    SimpleDataContext.builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                        .build(),
                )
            action.actionPerformed(event)
        }
    }

    private companion object {
        const val ACTION_ID = "dev.sebastiano.spectre.sample.RunSpectre"
        const val STARTUP_DELAY_MILLIS: Long = 1_500
    }
}

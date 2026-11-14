# IntelliJ and Jewel-hosted Compose

Spectre can drive Jewel tool windows and plugin `ComposePanel` surfaces hosted inside an IntelliJ Platform IDE, where
the IDE owns shared library versions, window focus, and the AWT event dispatch thread.

## Match the IDE dependency baseline

Use Spectre `0.7.1` or newer against the stable IDEA `2026.2.3` shared-dependency baseline (`0.7.0` is incompatible
with the IDE's coroutines `1.10.2` fork at the bytecode level, even if transitive coroutines are excluded). Keep
IDE-provided libraries, exclude duplicate plugin dependencies, and never replace the IDE's coroutine fork. Check the
pinned matrix at <https://spectre.sebastiano.dev/guide/intellij/#dependency-compatibility>.

## Pin `RobotDriver.synthetic` and stay off the EDT

Real `RobotDriver()` steals IDE focus and misfires if the developer switches windows. Pass
`RobotDriver.synthetic(rootWindow = ideFrame)` and run the automator off the EDT (all five wait helpers — `waitForNode`,
`waitUntilGone`, `waitUntil`, `waitForIdle`, and `waitForVisualIdle` — throw `IllegalStateException` on the EDT because
they marshal reads back via `invokeAndWait`). Under macOS `apple.awt.UIElement=true`, synthetic key routing falls back
to the Compose host under `ideFrame` when `Window.focusOwner` is `null`, so `typeText` still reaches focused
`TextField`s:

```kotlin
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver

class RunSpectreAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ideFrame = WindowManager.getInstance().getFrame(project) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val automator = ComposeAutomator.inProcess(
                robotDriver = RobotDriver.synthetic(rootWindow = ideFrame),
            )
            thisLogger().info(automator.printTree())
            // Run waits and interactions here, off the EDT
        }
    }
}
```

## Popups, `ComposePanel`, and external attach

If the workspace also provides a `jewel-swing-interop` skill, load it when working on `SwingBridgeTheme`,
`JewelFlags.useCustomPopupRenderer`, or `ComposePanel` hosting details. Key Spectre rules:

- **Popup layers:** Compose Desktop defaults `compose.layers.type` to `OnSameCanvas`, keeping popups inside the host
  window. When separate popup windows are enabled, they appear as distinct surfaces in `tree()`; wait for dismissal with
  `waitUntilGone(tag = ...)`, which refreshes windows before each poll.
- **Embedded `ComposePanel` recording:** a `ComposePanel` has no top-level window title, so `AutoRecorder.startWindow`
  cannot target it directly; use `startRegion(...)` or record the host `ideFrame`.
- **External inspection and cross-JVM:** to inspect a stock IDE from another process without adding `spectre-core`, add
  `-XX:+EnableDynamicAgentLoading` to IDE VM options and use `AgentAttach.attach(pid)` (`inject-runtime.jar`; see
  `references/agent.md`). Avoid `:server` HTTP transport unless needed and flag its experimental trust limits
  (`docs/SECURITY.md`).

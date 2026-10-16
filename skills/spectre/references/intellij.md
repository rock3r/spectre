# IntelliJ / Jewel-hosted Compose

Spectre can drive Compose UIs inside IntelliJ, including Jewel tool windows
and plugin `ComposePanel` instances. The IDE controls dependencies, window focus,
and the event dispatch thread.

## Match the IDE's dependencies

Use Spectre 0.7.1 with the stable IDEA 2026.2.3 baseline. Version 0.7.0 is incompatible.
Version 0.7.0 calls coroutine JVM methods missing from the IDE's 1.10.2 fork;
excluding its transitive coroutines cannot fix that compiled bytecode.
Version 0.7.1 compiles against the stable IDE's shared-dependency baseline.
Keep the IDE-provided libraries and exclude duplicate plugin dependencies;
do not replace the IDE's coroutine fork or bypass `runSpectreTest` checks.
See the [dependency compatibility table](https://spectre.sebastiano.dev/guide/intellij/#dependency-compatibility)
for the pinned versions.

## Use the synthetic driver

`ComposeAutomator.inProcess()` uses synthetic AWT events by default.
Real `RobotDriver()` can move IDE focus and reach another window if focus changes.
For IntelliJ, use `RobotDriver.synthetic(rootWindow = ideFrame)` so events target
the IDE frame. On macOS, this also works with `apple.awt.UIElement=true`.
If AWT reports no `Window.focusOwner`, Spectre sends key events to the Compose
host under `ideFrame`, allowing focused `TextField`s to receive `typeText`.

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
            // ...
        }
    }
}
```

## Don't run the automator on the EDT

Run the automator's polling and wait loop through `executeOnPooledThread` or another
non-EDT dispatcher. `waitForNode`, `waitUntilGone`, `waitUntil`, `waitForIdle`,
and `waitForVisualIdle` throw `IllegalStateException` on the EDT.
Each semantics read returns to the EDT internally through `invokeAndWait`.

## Popups and `ComposePanel`

For Jewel popups, `ComposePanel` embedding, `SwingBridgeTheme`, or popup
discovery, read the repository's **`jewel-swing-interop` skill**. It explains
`OnSameCanvas`, separate windows, `JewelFlags.useCustomPopupRenderer`, and
theme rules that affect the automator's tree.

Check these popup and panel behaviours:

- Compose Desktop defaults `compose.layers.type` to `OnSameCanvas`, so most
  popups render inside their host AWT window. With separate windows enabled,
  popups show up as additional discovered
  surfaces in `tree()`.
- Dismissing a popup that renders in its own window removes that window —
  and every node in it from the tracked set. Wait for that with
  `waitUntilGone(tag = …)`, which refreshes windows before each poll; a
  one-shot `findBy…` read against the last snapshot cannot observe it.
- An embedded `ComposePanel` has no top-level title. Window-targeted
  recording can't follow it; use region capture or pass the host IDE frame.

## External attach without `spectre-core`

To inspect an IDE from another process, use agent attach.
If the IDE lacks `spectre-core`, `AgentAttach.attach(pid)` injects
`META-INF/spectre/inject-runtime.jar`. Add `-XX:+EnableDynamicAgentLoading` to
the IDE VM options and restart it. Code already running inside the IDE can
use `ComposeAutomator` directly. See `references/agent.md` and
[IntelliJ-hosted Compose](https://spectre.sebastiano.dev/guide/intellij/).

## HTTP and cross-JVM access

The `:server` HTTP transport is **experimental** and has the trust limits
documented in `docs/SECURITY.md`. For IDE tests, use in-process automation
through `executeOnPooledThread` or agent attach when a separate process is needed.

# IntelliJ / Jewel-hosted Compose

Spectre can drive Compose UIs hosted **inside** an IntelliJ Platform IDE —
typically Jewel tool windows or `ComposePanel` instances embedded in plugin
UIs. The setup differs from a standalone Compose Desktop app in important
ways.

## Use the synthetic driver

The real `RobotDriver()` would steal focus from the IDE and could
mis-target windows when the developer alt-tabs away. Use
`RobotDriver.synthetic(rootWindow = ideFrame)` so AWT events are dispatched
directly into the IDE's window hierarchy:

```kotlin
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.synthetic

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

`executeOnPooledThread` (or any background dispatcher) is required. The
automator's outer loop — polling, retries, waits — runs on the calling
thread. If that thread is the EDT, `waitForIdle`/`waitForVisualIdle` will
deadlock when they try to `invokeAndWait` onto a thread they're already
running on. Per-tick semantics reads internally marshal back to the EDT.

## Popups and `ComposePanel`

If the task also involves Jewel popup rendering, `ComposePanel` embedding,
`SwingBridgeTheme`, or popup discovery quirks, read the repo-local
**`jewel-swing-interop` skill** — it covers the popup rendering modes
(`OnSameCanvas` vs separate window), `JewelFlags.useCustomPopupRenderer`,
and theme rules that affect what the automator sees.

Two facts worth knowing up front:

- Compose Desktop defaults `compose.layers.type` to `OnSameCanvas`, so most
  popups render inside their host AWT window rather than creating a new
  one. The automator already handles this — but if you've explicitly opted
  into separate windows, popups will show up as additional discovered
  surfaces in `tree()`.
- An embedded `ComposePanel` has no top-level title. Window-targeted
  recording can't follow it; use region capture or pass the host IDE frame.

## What about HTTP / cross-JVM?

The `:server` module that lets another JVM drive the IDE's Compose UI is
**experimental** and has security caveats (see `docs/SECURITY.md` in the
repo). Don't recommend it for general use without flagging that — prefer
running tests in-process via `executeOnPooledThread`.

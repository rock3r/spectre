# IntelliJ-hosted Compose

Spectre treats Jewel-on-IntelliJ tool windows the same as any other Compose surface:
the in-process automator can read their semantics tree, drive their input via Compose
semantics actions, and capture screenshots. The work to make this practical is mostly
about getting the automator running inside the IDE process and respecting the IDE's
EDT contract.

## In-process via a plugin action

The recommended pattern is to build a `ComposeAutomator` from inside an IntelliJ
plugin action. Because the action runs in the IDE's JVM, you reach the Jewel-hosted
`ComposePanel`'s semantics owners directly — no HTTP transport, no separate process.

```kotlin
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver

class RunSpectreAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // Build the automator on a pooled background thread — never on the EDT.
        // The polling loop below sleeps between ticks; sleeping on the EDT would
        // freeze AWT and prevent Compose from recomposing the very state we're
        // polling for.
        ApplicationManager.getApplication().executeOnPooledThread {
            val automator = ComposeAutomator.inProcess(
                robotDriver = RobotDriver.headless(),
            )
            thisLogger().info(automator.printTree())
        }
    }
}
```

Wire the action into `plugin.xml`, run the plugin with `./gradlew :your-plugin:runIde`,
and trigger it from the **Tools** menu (or whatever group you registered it under).

### `RobotDriver.headless()` is the right choice here

For an IDE-hosted automator, prefer `RobotDriver.headless()` over the default real-OS
driver. Two reasons:

- The IDE already owns the screen and keyboard focus you'd otherwise be fighting for
  via `java.awt.Robot`.
- "Clicks" inside the IDE-hosted panel are typically better delivered through Compose's
  own `OnClick` semantics action than through OS-level mouse events — see the next
  section.

Headless stubs out input / screenshot / clipboard side effects without affecting the
live `WindowTracker` and `SemanticsReader`, so the automator still reads real semantics
from the live tool window.

### Driving interactions via semantics actions

OS-level input is the wrong tool for in-IDE automation. Instead, walk the semantics
tree and invoke the `SemanticsActions.OnClick` action directly on the EDT:

```kotlin
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.intellij.openapi.application.ApplicationManager

private fun triggerOnClick(node: AutomatorNode) {
    val onClick = node.semanticsNode.config.getOrNull(SemanticsActions.OnClick)
    onClick?.action?.invoke()
}

// caller (must run on EDT):
ApplicationManager.getApplication().invokeAndWait {
    val toggle = automator.findOneByTestTag("popup.toggleButton") ?: return@invokeAndWait
    triggerOnClick(toggle)
}
```

This is the same pattern Compose's own test rule uses internally. It bypasses input
dispatch entirely and toggles state through the semantics tree — robust against window
focus, occlusion, and parallel test runs.

### EDT marshalling

Compose semantics owners hosted by `ComposePanel` are not thread-safe and must be read
on the EDT. The pattern is:

- Run the **outer loop** (polling, retries, `Thread.sleep` between ticks) on a pooled
  background thread.
- Marshal each **per-tick semantics read** back to the EDT via
  `ApplicationManager.getApplication().invokeAndWait { ... }`.

```kotlin
import com.intellij.openapi.application.ApplicationManager

inline fun <T> runOnEdt(crossinline block: () -> T): T {
    if (ApplicationManager.getApplication().isDispatchThread) return block()
    var result: T? = null
    @Suppress("UNCHECKED_CAST")
    ApplicationManager.getApplication().invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

// e.g.:
val node = runOnEdt { automator.findOneByTestTag("ide.counter.text") }
```

If you sleep on the EDT instead, AWT's event queue stops draining, Compose can't
recompose, and your polled state never updates — the polls always time out even
though the tool window is healthy.

## Auto-trigger on startup

For headless smokes, gate the action behind a system property and wire it as a startup
activity:

```xml
<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="com.example.SpectreAutorun" />
</extensions>
```

```kotlin
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SpectreAutorun : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getProperty("spectre.autorun") != "true") return
        // ...build automator, run scenarios, write artefacts
    }
}
```

Pass `-Dspectre.autorun=true` (or wire it through your Gradle task) to fire the action
on project open without needing a human at the keyboard. Spectre's own
`sample-intellij-plugin` follows this shape and runs that way under
`-PspectreAutorun=true`.

## Validating from a separate test JVM

When you want a CI-friendly automated test that boots the IDE, installs your plugin,
and asserts on what the automator discovers, the standard tool is JetBrains'
[`intellij-ide-starter`](https://github.com/JetBrains/intellij-community/tree/master/tools/intellij.tools.ide.starter).
The test JVM uses the **Driver API** to invoke actions in the IDE child process; for
assertions, the simplest bridge is the IDE's `idea.log` — the action writes log lines
that the test JVM reads off disk from the sandbox system directory.

Sketch:

```kotlin
import com.intellij.driver.sdk.invokeGlobalBackendAction
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
// ...

testContext.runIdeWithDriver().useDriverAndCloseIde {
    waitForProjectOpen()
    invokeGlobalBackendAction("RunSpectreAction")
    // ...wait for the action to flush, then read idea.log to assert.
}
```

Spectre's `:sample-intellij-plugin:uiTest` is a full worked example — it boots a real
IDE, installs the locally-built plugin zip, invokes `RunSpectreAction` through the
Driver API, and asserts every tagged Compose node from `SpectreSampleToolWindowContent`
appears in the log. CI runs it via
[`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
when plugin / core / recording sources change.

## Surface caveats

A few things to know about IDE-hosted Compose surfaces:

- **Tool windows have no top-level OS title**, so the recording router falls back to
  region capture (or SCK on macOS, where the IDE's main frame is the window). See
  [Recording](recording.md) for the routing logic.
- **Popups inside the IDE** are still tracked. Compose creates separate roots for them,
  and the `WindowTracker` enumerates each one — your selectors find nodes regardless of
  whether they live in the main tool window or a dropdown.
- **The IDE owns its own EDT**. Spectre's `waitForIdle` and `waitForVisualIdle` still
  refuse to run on it, so any code path triggered from a UI handler needs to bounce
  off to a pooled background thread before calling them.

## Where to look

- [`sample-intellij-plugin/`](https://github.com/rock3r/spectre/tree/main/sample-intellij-plugin)
  — the in-tree IntelliJ plugin Spectre drives in CI, with the
  pooled-thread / EDT-marshalling pattern fully worked out.
- [`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
  — the workflow that boots IntelliJ and runs the UI test on macOS and Windows.
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — the
  original design notes covering IDE-hosting more broadly.

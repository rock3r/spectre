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
import com.intellij.openapi.wm.WindowManager
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver

class RunSpectreAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ideFrame = WindowManager.getInstance().getFrame(project) ?: return

        // Build the automator on a pooled background thread — never on the EDT.
        // The polling loop below sleeps between ticks; sleeping on the EDT would
        // freeze AWT and prevent Compose from recomposing the very state we're
        // polling for.
        ApplicationManager.getApplication().executeOnPooledThread {
            val automator = ComposeAutomator.inProcess(
                robotDriver = RobotDriver.synthetic(rootWindow = ideFrame),
            )
            thisLogger().info(automator.printTree())
        }
    }
}
```

`printTree()` is synchronous. Interaction and wait methods (`waitForNode`, `click`, `typeText`,
etc.) are `suspend` — wrap them in `runBlocking { … }` inside the pooled thread:

```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    val automator = ComposeAutomator.inProcess(
        robotDriver = RobotDriver.synthetic(rootWindow = ideFrame),
    )
    runBlocking {
        automator.waitForNode(tag = "ide.counter.text")
        automator.click(automator.findOneByTestTag("ide.counter.button")!!)
    }
}
```

Alternatively, drive the automator from a `suspend fun` and reach it from a coroutine scope
(e.g. the `ProjectActivity.execute(project)` override below).

Wire the action into `plugin.xml`, run the plugin with `./gradlew :your-plugin:runIde`,
and trigger it from the **Tools** menu (or whatever group you registered it under).

### Hide the action from end users

A Spectre-driving action exists to validate the UI; it has no business showing up in a
production plugin distribution's Tools menu. Hide it on two axes:

- **Mark it internal in `plugin.xml`.** The `internal="true"` attribute makes the
  action visible only when the IDE is launched in internal mode
  (`-Didea.is.internal=true`):

    ```xml
    <action
        id="dev.example.RunSpectreAction"
        class="dev.example.RunSpectreAction"
        text="Run Spectre"
        internal="true">
        <add-to-group group-id="ToolsMenu" anchor="last" />
    </action>
    ```

- **Gate `update(...)` on a Registry flag and/or a JVM system property.** Belt and
  braces: even if someone flips internal mode on, the action stays disabled unless
  the gate is open. Both controls are easy for CI / dev launches and inert in
  production:

    ```kotlin
    import com.intellij.openapi.actionSystem.AnActionEvent
    import com.intellij.openapi.util.registry.Registry

    override fun update(e: AnActionEvent) {
        val enabled = Registry.`is`("my.plugin.spectre.enabled", false) ||
            System.getProperty("my.plugin.spectre.enabled") == "true"
        e.presentation.isEnabledAndVisible = enabled
    }
    ```

    Set the registry key via **Help → Find Action → Registry…** in any IDE, or pass
    `-Dmy.plugin.spectre.enabled=true` on the JVM command line for headless / CI
    runs (which is also the natural shape if you're combining this with
    [`-Dspectre.autorun=true`](#auto-trigger-on-startup) for non-interactive smokes).

The combination keeps the action discoverable to the right audience (developers,
QA, CI) and invisible to the rest of the world.

### Pick the right `RobotDriver` for in-IDE work

See [Driving input → Real vs. synthetic input](interactions.md#real-vs-synthetic-input)
for the full trade-off rundown across `RobotDriver()`, `RobotDriver.synthetic(...)`,
and `RobotDriver.headless()`. Two IDE-specific notes on top of that page:

- **`synthetic` is the usual default.** When the JVM running the automator is also
  the JVM hosting the UI, real OS input would dispatch events back to the IDE
  you're inside, fight with whatever else has focus on the host machine, and move
  the user's cursor visibly. Synthetic AWT events skip all of that. On macOS this
  also works for helper/test JVMs launched with `apple.awt.UIElement=true`: AWT may
  never report a `Window.focusOwner`, but Spectre routes key events to the
  key-listening Compose Desktop host so focused text fields still receive
  `typeText`. The IntelliJ-specific recipe for the `rootWindow` argument is
  `WindowManager.getInstance().getFrame(project)` — that's the `Window` shown in
  the action sample above.
- **`RobotDriver()` is still valid** when you specifically need to exercise
  OS-level shortcut chains, focus transitions, or cross-window interactions —
  those are the only things synthetic input doesn't cover.

### Optional: drive interactions via semantics actions

There's a third interaction style that doesn't go through any `RobotDriver` at all:
walk the semantics tree and invoke the `SemanticsActions.OnClick` action directly on
the EDT. This is what Compose's own test rule does internally. It's useful when:

- You're using `RobotDriver.headless()` (which throws on real input) and still need
  clicks to actually do something.
- You want clicks that are robust against window focus, occlusion, parallel test
  runs, and animation timing — semantics actions toggle state synchronously, no
  layout dependency.

```kotlin
val toggle = automator.findOneByTestTag("popup.toggleButton") ?: return
automator.performSemanticsClick(toggle)
```

`performSemanticsClick` marshals onto the EDT itself and invokes the Compose
`OnClick` semantics action directly. It throws `IllegalStateException` if the node
has no `OnClick` attached.

The trade-off: only nodes that expose an `OnClick` action (most `Modifier.clickable`
content does) can be driven this way, and you bypass any composable that reacts to
real pointer events but doesn't expose a semantics action. With `synthetic`, the
real-pointer-style path is also available.

### EDT marshalling

Compose semantics owners hosted by `ComposePanel` are not thread-safe and must be read
on the EDT. The pattern is:

- Run the **outer loop** (polling, retries, `delay` between ticks) on a pooled
  background thread inside a `runBlocking { … }` — interaction and wait methods are
  `suspend`.
- Marshal each **per-tick semantics read** back to the EDT via
  `ApplicationManager.getApplication().invokeAndWait { ... }`.

```kotlin
import com.intellij.openapi.application.ApplicationManager

inline fun <T> runOnEdt(crossinline block: () -> T): T {
    if (ApplicationManager.getApplication().isDispatchThread) return block()
    var result: T? = null
    ApplicationManager.getApplication().invokeAndWait { result = block() }
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

- **Tool windows have no top-level OS title**, so window-targeted recording can't
  pick them out by title the way it does standalone Compose windows. You still have
  three options:

    1. **Region-capture the tool window's screen bounds.** Compute the tool window
       component's `boundsOnScreen` and pass it to `AutoRecorder.startRegion(...)`.
       `AutoRecorder` routes that through `ffmpeg` region capture (or the Wayland
       portal on Linux Wayland). Works everywhere; the trade-off is
       that anything overlapping the captured rectangle — the IDE's chrome,
       notifications, popups that escape the tool window's bounds — appears in the
       recording.
    2. **Window-target the whole IDE frame.** Pass
       `WindowManager.getInstance().getFrame(project)?.asTitledWindow()` as
       `window` and the IDE frame's screen bounds as `region`. The IDE main frame
       *does* have a top-level OS title (e.g. "IntelliJ IDEA – ProjectName"), so
       this hits the macOS SCK helper, Windows `gdigrab title=` capture, or the
       Linux Wayland portal — window-targeted across all three. Useful when the
       interesting state is in the tool window plus its surrounding IDE chrome
       (run output, status bar, etc.).
    3. **Float the tool window first.** IntelliJ tool windows can be detached into
       their own top-level OS windows ("Window" view mode). Once detached, the
       floating window has its own title bar and behaves like a standalone Compose
       window for capture purposes. Switching modes from a test action is
       possible via the platform's tool-window APIs; from
       [`intellij-ide-starter`](#validating-from-a-separate-test-jvm) tests, the
       cleanest route is to invoke the IDE action that triggers the same change.
       Verify the floated frame's title is what your tests expect before relying
       on title-based capture.

    See [Recording](recording.md) for the full routing logic.
- **Popups inside the IDE** are still tracked. Compose creates separate roots for them,
  and Spectre's window tracking enumerates each one — your selectors find nodes regardless
  of whether they live in the main tool window or a dropdown.
- **The IDE owns its own EDT**. Spectre's `waitForIdle` and `waitForVisualIdle` still
  refuse to run on it, so any code path triggered from a UI handler needs to bounce
  off to a pooled background thread before calling them.

## Where to look

- [`sample-intellij-plugin/`](https://github.com/rock3r/spectre/tree/main/sample-intellij-plugin)
  — the in-tree IntelliJ plugin Spectre drives in CI, with the
  pooled-thread / EDT-marshalling pattern fully worked out.
- [`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
  — the workflow that boots IntelliJ and runs the UI test on macOS, Windows, and Linux.
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — the
  original design notes covering IDE-hosting more broadly.

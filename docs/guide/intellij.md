# IntelliJ-hosted Compose

Spectre reads semantics, sends input, and captures screenshots for Jewel tool windows
inside IntelliJ. Run the automator in the IDE process and follow its event dispatch
thread (EDT) rules.

## Dependency compatibility

Each Spectre release pins the shared libraries to a stable IntelliJ Platform dependency
baseline. Spectre 0.7.1 uses **IDEA 2026.2.3, build 262.10968.63**:

| Dependency | Compile baseline |
| --- | --- |
| Kotlin | 2.4.0 |
| Coroutines | 1.10.2; tested with the IDE's `1.10.2-intellij-2` fork |
| Kotlin serialization | 1.9.0 |
| Ktor | 3.4.1 |
| Compose Multiplatform | 1.12.0 |
| Skiko | 0.150.1, supplied through Compose |

The
[stable IDE source](https://github.com/JetBrains/intellij-community/tree/idea/2026.2.3)
and [dependency inventory](https://resources.jetbrains.com/storage/third-party-libraries/idea/idea-2026.2.3-third-party-libraries.json)
define these versions. Older IDE releases have no compatibility guarantee.

Use the IDE's Kotlin, coroutines, serialisation, Compose, and Skiko classes.
Declare required platform modules, including `composeUI()`. Exclude duplicate
libraries from the plugin distribution and keep the IDE's coroutine fork.
The separate Spectre CLI and build tools may use other dependency versions.

**Spectre 0.7.0 is incompatible with the IDE's coroutine runtime.** It compiles
ordinary Kotlin `runBlocking` calls to `BuildersKt.runBlockingK` from coroutines 1.11.
Dependency exclusions cannot change compiled calls. Spectre 0.7.1 restores the older
compile baseline and keeps the existing test runner and checks.

## In-process via a plugin action

Create a `ComposeAutomator` in an IntelliJ plugin action. It runs inside the IDE
JVM and reads Jewel `ComposePanel` semantics owners directly.

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

`printTree()` runs synchronously. Interaction and wait methods such as `waitForNode`,
`click`, and `typeText` are `suspend`. Call them inside `runBlocking { … }` on the
pooled thread. JUnit tests should use `runSpectreTest`:

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

The automator can also run in a suspending function such as
`ProjectActivity.execute(project)` below.

Register the action in `plugin.xml`. Run `./gradlew :your-plugin:runIde`, then
trigger the action from its registered menu group, such as **Tools**.

### Hide the action from end users

Keep test actions out of production plugin menus. Use both controls below:

- **Mark it internal in `plugin.xml`.** With `internal="true"`, the action appears
  only when the IDE runs with `-Didea.is.internal=true`:

    ```xml
    <action
        id="dev.example.RunSpectreAction"
        class="dev.example.RunSpectreAction"
        text="Run Spectre"
        internal="true">
        <add-to-group group-id="ToolsMenu" anchor="last" />
    </action>
    ```

- **Gate `update(...)` with a Registry flag or JVM property.** The action stays
  disabled until this condition passes, including in internal mode. CI and local
  launches can enable the condition explicitly:

    ```kotlin
    import com.intellij.openapi.actionSystem.AnActionEvent
    import com.intellij.openapi.util.registry.Registry

    override fun update(e: AnActionEvent) {
        val enabled = Registry.`is`("my.plugin.spectre.enabled", false) ||
            System.getProperty("my.plugin.spectre.enabled") == "true"
        e.presentation.isEnabledAndVisible = enabled
    }
    ```

    Set the registry key through **Help → Find Action → Registry…** in the IDE.
    For headless or CI runs, pass `-Dmy.plugin.spectre.enabled=true` to the JVM.
    Combine it with [`-Dspectre.autorun=true`](#auto-trigger-on-startup)
    for a non-interactive smoke test.

Developers and CI can enable the action. Production users do not see it by default.

### Pick the right `RobotDriver` for in-IDE work

See [Driving input → Real vs. synthetic input](interactions.md#real-vs-synthetic-input)
for the three `RobotDriver` modes. IDE-hosted tests also need these settings:

- **`synthetic` is the default.** `ComposeAutomator.inProcess()` uses
  `RobotDriver.synthetic()`. It sends events within the IDE JVM and avoids
  global focus and cursor changes. In macOS helper JVMs with
  `apple.awt.UIElement=true`, AWT may provide no `Window.focusOwner`.
  Spectre routes keys to the key-listening Compose Desktop host so focused
  text fields still receive `typeText`. For IntelliJ, set `rootWindow` to
  `WindowManager.getInstance().getFrame(project)`, as the action sample shows.
- **`RobotDriver()` sends real OS input** for system shortcuts, focus transitions,
  and interactions across windows. Select it explicitly when a test needs those events.

### Optional: drive interactions via semantics actions

For a node with a click action, call `SemanticsActions.OnClick` on the EDT.
Compose's test rule uses the same action. It helps in these cases:

- `RobotDriver.headless()` throws on input; semantics click actions remain available.
- Click actions must work regardless of window focus, occlusion, parallel tests and layout.
  Semantics actions change state synchronously.

```kotlin
val toggle = automator.findOneByTestTag("popup.toggleButton") ?: return
automator.performSemanticsClick(toggle)
```

`performSemanticsClick` marshals onto the EDT itself and invokes the Compose
`OnClick` semantics action directly. It throws `IllegalStateException` if the node
has no `OnClick` attached.

Only nodes with an `OnClick` action support this call. Most `Modifier.clickable`
content has one. Composables that handle pointer events without a semantics
action need pointer input. Use `synthetic` for that path.

### EDT marshalling

Read `ComposePanel` semantics owners on the EDT. Use this thread pattern:

- Run the **outer loop** for polls, retries, and `delay` on a pooled thread inside
  `runBlocking { … }`. Interaction and wait methods are `suspend`.
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

Sleeping on the EDT blocks AWT events and Compose recomposition.
The polled state then stays unchanged and waits can time out.

## Auto-trigger on startup

For an automated smoke run, register a startup activity and gate it with a
system property:

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

Pass `-Dspectre.autorun=true` directly or through Gradle to run on project open.
Spectre's `sample-intellij-plugin` uses `-PspectreAutorun=true` for this setup.

## External attach without spectre-core

Use [agent attach](agent.md) to inspect Jewel from another process.
If the IDE or plugin lacks `spectre-core`, the experimental **inject** bootstrap
loads the nested inject runtime from `spectre-agent-runtime`.

1. Add **`-XX:+EnableDynamicAgentLoading`** through **Help → Edit Custom VM Options…**
   and restart the IDE. Without it, JDK 21+ emits a JEP 451 warning.
   A future JDK may reject attach. A macOS options file is:
   `~/Library/Application Support/JetBrains/IntelliJIdea2026.2/idea.vmoptions`
2. Show a Compose surface with known test tags or product semantics. Any Jewel or
   Compose host inside the IDE works. Spectre's sample plugin is optional.
   To check `ide.counter.*` and `ide.popup.*` without shipping `spectre-core`,
   build the tags-only zip from a Spectre checkout:
   `./gradlew :sample-intellij-plugin:buildNoCorePlugin` →
   `sample-intellij-plugin/build/distributions/sample-intellij-plugin-no-core-0.0.0-DEV.zip`.
   The default instrumented sample plugin includes core and uses that attach path.
3. Find the IDE PID (`jps -l`), then from an attacher JVM with `spectre-agent` +
   `spectre-agent-runtime`:

```kotlin
@file:OptIn(ExperimentalSpectreAgentApi::class)

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

AgentAttach.attach(idePid).use { automator ->
    println(automator.findByTestTag("ide.counter.text"))
    println(automator.allNodes().mapNotNull { it.testTag })
}
```

If a plugin includes `spectre-core`, attach uses the instrumented path.
Both attach paths expose the same API. Code inside the IDE can call
`ComposeAutomator` in-process.

Spectre maintainers check inject with the no-core sample through
`./gradlew :sample-intellij-plugin:stockInjectUiTest`. This opt-in task needs a
graphical host and does not run on every pull request. The spike recipe is at:
[`docs/spikes/209-injection/stock-intellij-recipe.md`](https://github.com/rock3r/spectre/blob/main/docs/spikes/209-injection/stock-intellij-recipe.md).

## Validating from a separate test JVM

For a test that starts the IDE, installs a plugin, and checks discovered nodes, use JetBrains'
[`intellij-ide-starter`](https://github.com/JetBrains/intellij-community/tree/master/tools/intellij.tools.ide.starter).
The test JVM uses the **Driver API** to invoke actions in the IDE child process.
The action can write assertions data to `idea.log`. The test JVM then reads
that file from the sandbox system directory.

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

Spectre's `:sample-intellij-plugin:uiTest` shows the full setup. It starts a real
IDE, installs the locally built plugin zip, and invokes `RunSpectreAction` through
the Driver API. It checks that the log contains every tagged Compose node from
`SpectreSampleToolWindowContent`. CI runs it through
[`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
when plugin / core / recording sources change.

## Surface caveats

Capture and event handling depend on the IDE host:

- **Tool windows have no top-level OS title.** A recorder cannot target a tool
  window by its own title. Choose one of these capture paths:

    1. **Capture the tool window's screen region.** Pass the component's
       `boundsOnScreen` to `AutoRecorder.startRegion(...)`. This uses Windows Graphics
       Capture, macOS ScreenCaptureKit, the Linux Xorg/Xvfb helper, or the Wayland portal.
       The recording includes visible pixels from other windows, IDE chrome,
       notifications, and popups inside the rectangle.
    2. **Capture the whole IDE frame.** Pass
       `WindowManager.getInstance().getFrame(project)?.asTitledWindow()` as
       `window` and the IDE frame's screen bounds as `region`. The IDE main frame
       has a top-level title such as "IntelliJ IDEA – ProjectName". This selects
       macOS SCK, Windows Graphics Capture, or the Linux Wayland portal for window
       capture. The video includes the tool window and IDE chrome, such as run
       output and the status bar.
    3. **Float the tool window.** IntelliJ's "Window" view mode gives a tool
       window its own top-level frame and title. A plugin action can switch modes
       through tool-window APIs. An
       [`intellij-ide-starter`](#validating-from-a-separate-test-jvm) test can invoke
       the corresponding IDE action. Check the floating frame's title before
       selecting it for capture.

    See [Recording](recording.md) for the full routing logic.
- **Spectre tracks IDE popups.** Compose gives them separate roots, which Spectre
  includes in its window tracking. Selectors can find nodes in a tool window or dropdown.
- **Waits must run outside the IDE's EDT.** `waitForNode`, `waitForIdle`, and
  `waitForVisualIdle` reject EDT callers. Move work started by a UI handler to a
  pooled thread before waiting.

## Where to look

- [`sample-intellij-plugin/`](https://github.com/rock3r/spectre/tree/main/sample-intellij-plugin)
  shows the IntelliJ plugin used in CI, including pooled-thread work and EDT reads.
- [`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
  starts IntelliJ UI tests on macOS, Windows, and Linux.
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8)
  contains the original IDE-hosting design notes.

# IntelliJ-hosted Compose

Spectre treats Jewel-on-IntelliJ tool windows the same as any other Compose surface: the
in-process automator can read their semantics tree, drive their input, and capture
screenshots. The trick is getting the automator running inside the IDE's process.

## In-process via a plugin action

The simplest setup is the one demonstrated by [`sample-intellij-plugin`](https://github.com/rock3r/spectre/tree/main/sample-intellij-plugin):
a plugin action that builds a `ComposeAutomator.inProcess()` instance on demand and
queries the IDE's tool windows.

A minimal action looks like:

```kotlin
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import dev.sebastiano.spectre.core.ComposeAutomator

class RunSpectreAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val automator = ComposeAutomator.inProcess()
        thisLogger().info(automator.printTree())
    }
}
```

Wire it into `plugin.xml` under `<actions>`, run the plugin with `runIde`, and trigger
it from the **Tools** menu.

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

Spectre's own `sample-intellij-plugin` follows the same shape — running with
`-PspectreAutorun=true` fires the action on project open without needing a human at
the keyboard.

## Driving from a separate process

If the test process can't run inside the IDE — for example because the IDE is being
launched by `intellij-ide-starter` and you want clean separation — install the
[`server` module's routes](cross-jvm.md) inside the plugin, then drive from the test
JVM via `ComposeAutomator.http(host, port)`.

That's the shape `:sample-intellij-plugin:uiTest` uses: it boots a real IntelliJ IDEA
via `intellij-ide-starter`, installs the locally-built plugin, fires the action through
the Driver API, and asserts every tagged Compose node lands in `idea.log`. CI runs it
in [`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
when plugin/core/recording sources change.

## Surface caveats

A few things to know about IDE-hosted Compose surfaces:

- **Tool windows have no top-level OS title**, so the recording router will fall back
  to region capture (or SCK on macOS, where the IDE's main frame is the window). See
  [Recording](recording.md) for the routing logic.
- **Popups inside the IDE** are still tracked. Compose creates separate roots for them,
  and the `WindowTracker` enumerates each one — your selectors find nodes regardless of
  whether they live in the main tool window or a dropdown.
- **The IDE owns its own EDT**. Spectre's wait helpers still refuse to run on it, so
  any code path triggered from a UI handler needs the same `withContext(Dispatchers.Default)`
  bracket as any other test.

## Where to look

- [`sample-intellij-plugin/`](https://github.com/rock3r/spectre/tree/main/sample-intellij-plugin)
  — the in-tree IntelliJ plugin Spectre drives in CI.
- [`ide-uitest.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/ide-uitest.yml)
  — the workflow that boots IntelliJ and runs the UI test on macOS and Windows.
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — the
  original design notes covering IDE-hosting more broadly.

# #320: Stock IntelliJ inject attach recipe (post-#209)

**Status:** documented + evidence chain recorded (closes #320)  
**Date:** 2026-07-26  
**Depends on:** inject packaging (#319), practicalities.md §1 (vmoptions)

## Goal

Attach from a sister JVM to **stock IntelliJ IDEA 2026.2+** that does **not** ship
`spectre-core`, using nested inject-runtime, and dump Jewel-hosted tool-window tags
(`ide.counter.*` / `ide.popup.*`) when a Compose surface with those tags is present.

## Prerequisites

1. **IntelliJ IDEA 2026.2+** (platform 262 / JBR era matching Spectre’s sample plugin pin).
2. **Custom VM options** include dynamic agent loading (see practicalities.md §1):

   ```text
   -XX:+EnableDynamicAgentLoading
   ```

   Path example (macOS):  
   `~/Library/Application Support/JetBrains/IntelliJIdea2026.2/idea.vmoptions`  
   Then **fully restart** the IDE.

3. Attacher machine: **JDK 21+** with `jdk.attach`, same OS user as the IDE process.
4. Built artifacts from this repo (or published snapshots):

   ```bash
   ./gradlew :agent-runtime:jar :agent:jar
   ```

5. A **Jewel/Compose surface with known test tags** in the IDE process:
   - **Recommended proving UI:** install Spectre’s `:sample-intellij-plugin` (dev zip /
     `runIde` sandbox). Tags are defined in `SpectreSampleToolWindowContent`:

     | Tag | Role |
     | --- | --- |
     | `ide.counter.button` | click + state |
     | `ide.counter.text` | always present |
     | `ide.popup.toggleButton` | opens popup |
     | `ide.popup.body` / `ide.popup.text` / `ide.popup.dismissButton` | popup tree |

   - **True stock IDE (no sample plugin):** only Compose surfaces that already expose
     semantics (and any tags the product sets) are visible. There is no guarantee of
     `ide.counter.*` tags without a plugin or product code that sets them.

### Note on sample plugin vs pure inject

The sample plugin **ships `spectre-core`** so its in-process `RunSpectreAction` and
`:sample-intellij-plugin:uiTest` exercise the **instrumented** path (bootstrap prefers
preinstalled core). That is intentional for CI IDE validation.

To exercise **inject** against the same Jewel tags:

1. Build a throwaway plugin zip that includes only the tool-window UI (Jewel + tags)
   **without** `spectre-core` on the plugin classpath, **or**
2. Attach to any third-party / stock Compose surface that has no Spectre dependency.

If `spectre-core` is present, attach still works — inject is simply not used.

## Recipe (manual / release QA)

### 1. Start the IDE

With `-XX:+EnableDynamicAgentLoading` already in custom VM options. Open the sample
tool window (**View → Tool Windows → Spectre Sample**, or the plugin’s registered id)
so Compose has painted and tags exist.

### 2. Find the IDE PID

```bash
jps -l | rg -i 'idea|intellij'
# or
jps -lm | rg -i 'com.intellij'
```

Use the main IDE process PID (not helper processes).

### 3. Attach and dump tags

From a Kotlin script / small main with `spectre-agent` + `spectre-agent-runtime` on the
classpath (repo shape):

```kotlin
@file:OptIn(ExperimentalSpectreAgentApi::class)

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Path

AgentAttach.attach(
    pid = idePid,
    options =
        AttachOptions(
            agentJarPath =
                Path.of("agent-runtime/build/libs/spectre-agent-runtime-0.1.0-SNAPSHOT.jar"),
        ),
).use { automator ->
    println(automator.windows())
    val tags = automator.allNodes().mapNotNull { it.testTag }.sorted()
    println(tags)
    for (tag in
        listOf(
            "ide.counter.text",
            "ide.counter.button",
            "ide.popup.toggleButton",
        )) {
        println("$tag -> ${automator.findByTestTag(tag).size}")
    }
}
```

Or rely on classpath discovery of `spectre-agent-runtime` and call
`AgentAttach.attach(idePid)` only.

### 4. Success / failure signals

| Signal | Meaning |
| --- | --- |
| Non-empty `windows()` / `allNodes()` | Agent + IPC + Compose host discovery OK |
| `findByTestTag("ide.counter.text")` non-empty | Jewel tool window semantics visible |
| `ide.popup.*` after opening popup | Popup roots tracked (open via UI or later Robot) |
| JEP 451 stderr / attach reject | Missing `EnableDynamicAgentLoading` |
| `ComposeNotOnClasspathException` | No Compose host in that process |
| `SpectreNotOnClasspathException` | Inject resource missing from agent-runtime jar |
| Empty tags with sample plugin closed | Tool window not showing / not composed yet |

## Evidence chain (what is automated vs manual)

| Layer | How proven | What it proves |
| --- | --- | --- |
| Nested inject packaging | `:agent-inject-runtime` / `:agent-runtime` verify tasks + #329 strip tests | Jar shape + Windows CP strip |
| Inject attach without core | `AgentInjectAttachIntegrationTest` (Linux/macOS; physical Windows validated) | Real attach/UDS/tree via inject |
| Jewel tags in IDE process | `:sample-intellij-plugin:uiTest` + `ide-uitest.yml` | `ide.counter.*` / `ide.popup.*` discoverable in IDEA 2026.2 sandbox (instrumented) |
| Stock IDE + inject + Jewel tags | **This recipe** (manual / release QA) | Operator checklist when IDE has no core |

Combined: packaging and inject transport are CI-proven; Jewel tag names and IDE hosting
are CI-proven on the sample plugin; full stock no-core + inject is the documented QA
path above (environment-dependent, same gap noted in practicalities.md before #320).

## CI stance

Do **not** add always-on hosted CI that boots stock IntelliJ and attaches inject for every
PR — `ide-uitest.yml` already owns IDE boot cost for the instrumented sample. Revisit only
if product requires no-core inject as a release gate (see packaging-1.0-decision.md
revisit triggers).

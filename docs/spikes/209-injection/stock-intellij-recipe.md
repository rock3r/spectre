# #320 / #353: Stock IntelliJ inject attach recipe (post-#209)

**Status:** **PASS** (0.4.1 / #353) — automated opt-in e2e + packaging contract.  
**Date:** 2026-07-26 (recipe); 2026-07-27 (0.4.0 honesty note); 2026-08-03 (#353 close-out)  
**Depends on:** inject packaging (#319), practicalities.md §1 (vmoptions), no-core sample
plugin zip (#375), stock inject e2e (#376)

## Goal

Attach from a sister JVM to **IntelliJ IDEA 2026.2+** that does **not** ship
`spectre-core`, using nested inject-runtime, and dump Jewel-hosted tool-window tags
(`ide.counter.*` / `ide.popup.*`) when a Compose surface with those tags is present.

## Automated path (opt-in — preferred re-verify)

**Does not** run on every PR (always-on stock IDE inject CI remains a non-goal). Local /
release QA on a **graphical** host (non-headless JVM; Linux needs `DISPLAY` or
`xvfb-run -a`):

```bash
# Builds no-core plugin zip + agent-runtime, boots IDEA 2026.2 via ide-starter,
# AgentAttach.attach → inject + Jewel tags.
./gradlew :sample-intellij-plugin:stockInjectUiTest
```

**Pass criteria:** the task must **execute** `StockIntellijInjectAttachUiTest` (not
assumption-skip). A headless run skips with a successful Gradle exit — that is **not**
stock-inject proof. Check the HTML/XML test report for `tests=1` / `skipped=0`, or look
for the inject log line below in the sandbox `idea.log`.

What a non-skipped green run proves (real shipped APIs):

1. `-XX:+EnableDynamicAgentLoading` on the IDE VM options patch.
2. No-core plugin install (`buildNoCorePlugin` — Jewel tags only, no `spectre-core` jars).
3. Tool window **Spectre Sample** opened via Driver.
4. `AgentAttach.attach(idePid)` with nested inject-runtime.
5. `waitForNode("ide.counter.text")` + other proving tags non-empty.
6. `idea.log` contains `[spectre-agent] spectre-core not on target classpath; injecting`.

Packaging-only (no IDE boot):

```bash
./gradlew :sample-intellij-plugin:verifyNoCorePluginZip
# Contract unit tests (always on :check via buildSrcUnitTests):
./gradlew -p buildSrc test --tests '*NoCorePluginPackagingContractTest*'
```

## Prerequisites (manual recipe)

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
   ./gradlew :agent-runtime:jar :agent:jar :sample-intellij-plugin:buildNoCorePlugin
   ```

5. A **Jewel/Compose surface with known test tags** in the IDE process:
   - **Recommended proving UI:** install the **no-core** sample plugin zip from
     `sample-intellij-plugin/build/distributions/sample-intellij-plugin-no-core-0.0.0-DEV.zip`
     (or run the automated path above). Tags are defined in `SpectreSampleToolWindowContent`:

     | Tag | Role |
     | --- | --- |
     | `ide.counter.button` | click + state |
     | `ide.counter.text` | always present |
     | `ide.popup.toggleButton` | opens popup |
     | `ide.popup.body` / `ide.popup.text` / `ide.popup.dismissButton` | popup tree |

   - **Instrumented sample plugin** (`buildPlugin` / `uiTest`) still ships `spectre-core` for
     in-process validation — that path does **not** exercise inject.
   - **True stock IDE (no sample plugin):** only Compose surfaces that already expose
     semantics (and any tags the product sets) are visible. There is no guarantee of
     `ide.counter.*` tags without a plugin or product code that sets them.

### Note on sample plugin vs pure inject

The default sample plugin **ships `spectre-core`** so its in-process `RunSpectreAction` and
`:sample-intellij-plugin:uiTest` exercise the **instrumented** path (bootstrap prefers
preinstalled core). That is intentional for CI IDE validation.

To exercise **inject** against the same Jewel tags:

1. Use `:sample-intellij-plugin:buildNoCorePlugin` / `stockInjectUiTest` (preferred), **or**
2. Attach to any third-party / stock Compose surface that has no Spectre dependency.

If `spectre-core` is present, attach still works — inject is simply not used.

## Recipe (manual / release QA)

### 1. Start the IDE

With `-XX:+EnableDynamicAgentLoading` already in custom VM options. Install the **no-core**
plugin zip (not the instrumented `buildPlugin` zip). Open the sample tool window
(**View → Tool Windows → Spectre Sample**) so Compose has painted and tags exist.

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
| Agent log: `spectre-core not on target classpath; injecting` | Inject path used (not instrumented) |
| JEP 451 stderr / attach reject | Missing `EnableDynamicAgentLoading` |
| `ComposeNotOnClasspathException` | No Compose host in that process |
| `SpectreNotOnClasspathException` | Inject resource missing from agent-runtime jar |
| Empty tags with sample plugin closed | Tool window not showing / not composed yet |

## Evidence chain (what is automated vs manual)

| Layer | How proven | What it proves |
| --- | --- | --- |
| Nested inject packaging | `:agent-inject-runtime` / `:agent-runtime` verify tasks + #329 strip tests | Jar shape + Windows CP strip |
| Inject attach without core (fixture) | `AgentInjectAttachIntegrationTest` (Linux/macOS; physical Windows validated) | Real attach/UDS/tree via inject |
| Jewel tags in IDE (instrumented) | `:sample-intellij-plugin:uiTest` + `ide-uitest.yml` | `ide.counter.*` / `ide.popup.*` in IDEA 2026.2 sandbox with core |
| No-core plugin packaging | `NoCorePluginPackagingContract` + `verifyNoCorePluginZip` (#375) | Tags-only zip; zero core jars |
| Stock IDE + inject + Jewel tags | **`stockInjectUiTest`** (#376) — opt-in; local macOS PASS 2026-08-03 | Full no-core IDE attach via inject |

Combined: packaging, fixture inject, instrumented IDE tags, and **stock no-core IDE inject**
are all automated. Inject remains **experimental inspect** (see STABILITY.md / packaging-1.0-decision.md).

## CI stance

Do **not** add always-on hosted CI that boots stock IntelliJ and attaches inject for every
PR — `ide-uitest.yml` already owns IDE boot cost for the instrumented sample.
`stockInjectUiTest` is the durable re-verify entry point for release QA / when inject or
IDE-hosting code changes. Revisit always-on only if product requires no-core inject as a
release gate (see packaging-1.0-decision.md revisit triggers).

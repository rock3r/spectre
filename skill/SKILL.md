---
name: spectre-ui-automation
description: >
  Use when the user is writing, running, or debugging end-to-end UI automation tests for a
  Compose for Desktop (aka Compose Multiplatform on desktop) app — including setting up Spectre,
  clicking or typing in a running window, finding UI nodes by tag or text, waiting for the UI to
  settle, capturing screenshots or video, or troubleshooting a Compose Desktop or IntelliJ-hosted
  UI under test. Also use when the user asks how to automate a live running Compose Desktop app,
  even if they don't mention Spectre by name.
license: Apache-2.0
compatibility: Requires a Compose Desktop or Compose Multiplatform (desktop target) application and JDK 21+.
---

# Spectre UI Automation

Spectre automates live Compose Desktop UIs by reading the semantics tree and dispatching real OS input. Full docs and API reference: **[spectre.sebastiano.dev](https://spectre.sebastiano.dev)**

For the longer agent skill (selectors, recording, agent attach, IntelliJ), use **`spectre`** /
`skills/spectre/SKILL.md`. For atomic `capture.json` workflows, use **`spectre-capture`**.

## Spectre vs `ComposeTestRule`

- **`ComposeTestRule`** (`compose-ui-test`) — tests a composable in isolation, without launching a real app. Right for unit and component tests.
- **Spectre** — automates a fully running app end-to-end: the local process, a separate JVM via HTTP or Java-agent attach, or an IntelliJ plugin hosting Compose UI. Right when you need the whole app stack in motion, for UI automation and user-journey level validation.

If you're testing an individual composable in isolation → use `ComposeTestRule`. If you're automating a full app → use Spectre.

## Gotchas

- **Use `runSpectreTest`, not `runTest`.** `runTest` collapses `delay()` to zero, which silently breaks `longClick` hold durations, `swipe` step pacing, and clipboard-settle polling. Prefer `runSpectreTest` from the testing module (real wall time + leak detection); plain `runBlocking` is a fallback.
- **Selectors are non-waiting.** Every `findBy...` / `findOneBy...` call reads the semantics tree once. Call `waitForNode` before querying any node that might not exist yet, and `waitUntilGone` after dismissing a popup, menu, or dialog before touching what was behind it. For barriers no selector expresses — a node count, a combination — use `waitUntil(description) { ... }`.
- **EDT rule.** All five wait helpers (`waitForNode`, `waitUntilGone`, `waitUntil`, `waitForIdle`, and `waitForVisualIdle`) throw `IllegalStateException` when called from the AWT event dispatch thread. Standard JUnit test methods run off the EDT so this isn't normally an issue; if you call them from the EDT, wrap with `withContext(Dispatchers.Default)`.
- **Expression-body tests.** Write `@Test fun mySpec(): Unit = runSpectreTest { ... }`. JUnit 5.14+ rejects non-void test methods, and Kotlin infers the return type from the last expression in the `runSpectreTest` body.

## Setup

In `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:spectre-core:$spectreVersion")
    testImplementation("dev.sebastiano.spectre:spectre-testing:$spectreVersion")
    // Required for video or native window-scoped screenshots:
    testImplementation("dev.sebastiano.spectre:spectre-recording:$spectreVersion")
    testRuntimeOnly("dev.sebastiano.spectre:spectre-recording-macos:$spectreVersion") // macOS helper
    testRuntimeOnly("dev.sebastiano.spectre:spectre-recording-linux:$spectreVersion") // Linux helper
    testRuntimeOnly("dev.sebastiano.spectre:spectre-recording-windows:$spectreVersion") // Windows helper

    // optional:
    testImplementation("dev.sebastiano.spectre:spectre-server:$spectreVersion") // cross-JVM HTTP transport
    testImplementation("dev.sebastiano.spectre:spectre-agent:$spectreVersion") // agent attach API
    testRuntimeOnly("dev.sebastiano.spectre:spectre-agent-runtime:$spectreVersion") // loadable Java agent
}
```

Check [spectre.sebastiano.dev](https://spectre.sebastiano.dev) for the latest version and full user guide.

## Test structure (JUnit 5)

Sequential tests — own the extension on the class:

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MyTest {
    @JvmField @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun myTest(): Unit = runSpectreTest {
        launchMyApp()  // your responsibility — Spectre manages the automator, not the window
        val automator = automatorExt.automator
        automator.waitForNode(tag = "root-content")
        // interact and assert
    }
}
```

`launchMyApp()` is whatever starts your app's window — a daemon thread calling `main()`, a purpose-built test harness, anything that opens a Compose Desktop window. Spectre's extension/rule only manages the `ComposeAutomator` lifecycle.

**Parallel tests** — use `@ExtendWith` with parameter injection instead. Each test gets its own automator from the per-invocation store, avoiding races:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(ComposeAutomatorExtension::class)
class MyTest {
    @Test
    fun myTest(automator: ComposeAutomator): Unit = runSpectreTest {
        launchMyApp()
        automator.waitForNode(tag = "root-content")
        // interact and assert
    }
}
```

For JUnit 4, use `@get:Rule val automatorRule = ComposeAutomatorRule()` and access via `automatorRule.automator`.

## Finding nodes

```kotlin
automator.findOneByTestTag("Submit")         // most reliable; needs Modifier.testTag on the composable
automator.findOneByText("Submit")            // exact, case-sensitive by default
automator.findByText("sub", exact = false)   // substring, case-insensitive
automator.findByContentDescription("Send")
automator.findByRole(Role.Button)
automator.printTree()                        // dump the full semantics tree — use this first when debugging
```

For anything you'll click, scroll, or type into, **prefer `testTag` over text selectors** — text selectors break when copy changes or when the app is localized. Use text selectors for asserting visible content, not for navigation.

Key `AutomatorNode` properties: `testTag`, `text`, `texts`, `contentDescription`, `role`, `isFocused`, `isDisabled`, `isSelected`, `editableText`, `boundsInWindow`, `boundsOnScreen`, `centerOnScreen`, `children`, `parent`.

## Interactions

All interaction methods are `suspend` — they must be called from inside `runSpectreTest { ... }` (or `runBlocking` as a fallback).

```kotlin
automator.click(node)
automator.doubleClick(node)
automator.longClick(node, holdFor = 600.milliseconds)
automator.swipe(from = firstNode, to = lastNode)
automator.swipe(startX = 100, startY = 400, endX = 100, endY = 100, steps = 16, duration = 200.milliseconds)
automator.scrollWheel(listNode, wheelClicks = 5)   // negative = scroll up
automator.typeText("hello")                        // key events; supported ASCII only
automator.pasteText("こんにちは")                   // clipboard paste for large or Unicode text
automator.clearAndTypeText(node, "replacement")    // click + clear + typeText
automator.pressKey(KeyEvent.VK_TAB)
automator.pressKey(KeyEvent.VK_S, modifiers = InputEvent.CTRL_DOWN_MASK)
automator.pressEnter()

val img: BufferedImage = automator.screenshot()         // full virtual screen
val img = automator.screenshot(windowIndex = 0)         // native single-window capture; needs recording + platform helper
val img = automator.screenshot(node)                    // native window capture; needs recording + platform helper
```

`typeText` dispatches key press/release pairs and does not touch the clipboard. Use `pasteText` for large strings or arbitrary Unicode.

## Synchronization

```kotlin
// After launching — poll until a node appears
automator.waitForNode(tag = "root-content")

// After dismissing a popup / menu / dialog — poll until nothing matches in any tracked window
automator.waitUntilGone(tag = "popup.body")

// For a barrier no tag/text selector expresses — the predicate receives the AutomatorTree
automator.waitUntil(description = "at least five rows are showing") {
    allNodes().count { it.testTag?.startsWith("row.") == true } >= 5
}

// After an interaction — wait for semantics tree + idling resources to settle
automator.waitForIdle()

// After an animation or visual change that doesn't affect semantics
automator.waitForVisualIdle()
```

Typical post-interaction pattern:

```kotlin
automator.click(submit)
automator.waitForIdle()        // semantics settled
automator.waitForVisualIdle()  // pixels settled
val result = automator.findOneByTestTag("Result")
```

All five wait helpers are `suspend` and **must not** be called from the AWT EDT. Default timeout is 5 s; all parameters are tunable. `waitUntil` is scoped to the semantics tree it hands the predicate — wait for non-Spectre state (a service flag, a file, an HTTP response) with the tool that owns it, not inside the lambda.

## Input drivers

Use `ComposeAutomator.inProcess()` (the default) for most tests — it uses a real `java.awt.Robot` and moves the actual cursor. Switch drivers only when needed:

- `RobotDriver.synthetic(rootWindow = window)` — synthetic AWT events posted directly into the window's event queue; no real cursor motion, safe for parallel test runs.
- `RobotDriver.headless()` — read-only; every input or screenshot call throws `UnsupportedOperationException`. Semantics-tree reads still work.
- `ComposeAutomator.http("localhost", 7654)` — cross-JVM via HTTP; requires the `:server` module running in the target process.
- `AgentAttach.attach(pid)` — attach to a **running** Compose JVM. The target does **not** need `spectre-core` preinstalled: when core is absent, the agent runtime injects nested `META-INF/spectre/inject-runtime.jar`. Prefer a `spectre-core` dependency when you control the target build. The attacher needs `spectre-agent` plus `spectre-agent-runtime`.

---

For recording, IntelliJ-hosted Compose, advanced selectors, troubleshooting, and the full reference, see **[spectre.sebastiano.dev](https://spectre.sebastiano.dev)**.

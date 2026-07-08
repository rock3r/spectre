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

## Spectre vs `ComposeTestRule`

- **`ComposeTestRule`** (`compose-ui-test`) — tests a composable in isolation, without launching a real app. Right for unit and component tests.
- **Spectre** — automates a fully running app end-to-end: the local process, a separate JVM via HTTP, or an IntelliJ plugin hosting Compose UI. Right when you need the whole app stack in motion, for UI automation and user-journey level validation.

If you're testing an individual composable in isolation → use `ComposeTestRule`. If you're automating a full app → use Spectre.

## Gotchas

- **Use `runBlocking`, not `runTest`.** `runTest` collapses `delay()` to zero, which silently breaks `longClick` hold durations, `swipe` step pacing, and clipboard-settle polling inside `typeText`. This breaks Spectre's internal delays.
- **Selectors are non-waiting.** Every `findBy...` / `findOneBy...` call reads the semantics tree once. Call `waitForNode` before querying any node that might not exist yet.
- **EDT rule.** `waitForIdle` and `waitForVisualIdle` throw `IllegalStateException` when called from the AWT event dispatch thread. Standard JUnit test methods run off the EDT so this isn't normally an issue; if you call them from the EDT, wrap with `withContext(Dispatchers.Default)`.

## Setup

In `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:core:$spectreVersion")
    testImplementation("dev.sebastiano.spectre:testing:$spectreVersion")
    // optional:
    testImplementation("dev.sebastiano.spectre:recording:$spectreVersion")  // video capture
    testImplementation("dev.sebastiano.spectre:server:$spectreVersion")     // cross-JVM HTTP transport
}
```

Check [spectre.sebastiano.dev](https://spectre.sebastiano.dev) for the latest version and full user guide.

## Test structure (JUnit 5)

Sequential tests — own the extension on the class:

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MyTest {
    @JvmField @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun myTest() = runBlocking {
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(ComposeAutomatorExtension::class)
class MyTest {
    @Test
    fun myTest(automator: ComposeAutomator) = runBlocking {
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

All interaction methods are `suspend` — they must be called from inside `runBlocking { ... }`.

```kotlin
automator.click(node)
automator.doubleClick(node)
automator.longClick(node, holdFor = 600.milliseconds)
automator.swipe(from = firstNode, to = lastNode)
automator.swipe(startX = 100, startY = 400, endX = 100, endY = 100, steps = 16, duration = 200.milliseconds)
automator.scrollWheel(listNode, wheelClicks = 5)   // negative = scroll up
automator.typeText("hello")                        // clipboard-based; works for non-ASCII
automator.clearAndTypeText(node, "replacement")    // click + clear + type in one call
automator.pressKey(KeyEvent.VK_TAB)
automator.pressKey(KeyEvent.VK_S, modifiers = InputEvent.CTRL_DOWN_MASK)
automator.pressEnter()

val img: BufferedImage = automator.screenshot()         // full virtual screen
val img = automator.screenshot(windowIndex = 0)        // single Compose surface
val img = automator.screenshot(node)                   // node's bounding region
```

## Synchronization

```kotlin
// After launching — poll until a node appears (EDT-safe)
automator.waitForNode(tag = "root-content")

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

All three wait helpers are `suspend`. Default timeout is 5 s; all parameters are tunable.

## Input drivers

Use `ComposeAutomator.inProcess()` (the default) for most tests — it uses a real `java.awt.Robot` and moves the actual cursor. Switch drivers only when needed:

- `RobotDriver.synthetic(window)` — synthetic AWT events posted directly into the window's event queue; no real cursor motion, safe for parallel test runs.
- `RobotDriver.headless()` — read-only; every input or screenshot call throws `UnsupportedOperationException`. Semantics-tree reads still work.
- `ComposeAutomator.http("localhost", 7654)` — cross-JVM via HTTP; requires the `:server` module running in the target process.

---

For recording, IntelliJ-hosted Compose, advanced selectors, troubleshooting, and the full reference, see **[spectre.sebastiano.dev](https://spectre.sebastiano.dev)**.

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

Spectre reads the semantics tree of a running Compose Desktop UI.
It sends synthetic input by default. Tests can opt into real OS input.
See the [user guide](https://spectre.sebastiano.dev) for setup and API details.

Use **`spectre`** in `skills/spectre/SKILL.md` for selectors, recording, attach, and IntelliJ.
Use **`spectre-capture`** for atomic `capture.json` workflows.

## Choosing a test framework

- **`ComposeTestRule`** (`compose-ui-test`) — tests a composable in isolation, without launching a real app. Use it for unit and component tests.
- **Spectre** — automates a running app in the local process, a separate JVM via HTTP or Java-agent attach, or an IntelliJ plugin hosting Compose UI. Use it to test interactions across the application.

Use `ComposeTestRule` for an isolated composable. Use Spectre for a running application.

## Common constraints

- **Use `runSpectreTest`.** `runTest` collapses `delay()` to zero.
  That breaks `longClick` holds, `swipe` pacing, and clipboard polling.
  `runSpectreTest` uses real time and detects leaks. Plain `runBlocking` remains a fallback.
- **Selectors read once.** `findBy...` and `findOneBy...` do not wait.
  Call `waitForNode` when a node may appear later. Call `waitUntilGone` after dismissing
  a popup, menu, or dialog. Use `waitUntil(description) { ... }` for tree conditions
  such as a node count.
- **Keep waits off the EDT.** All five wait helpers throw `IllegalStateException` on
  the AWT event dispatch thread. JUnit test methods normally run outside the EDT.
  Move EDT calls with `withContext(Dispatchers.Default)`.
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

Check the [user guide](https://spectre.sebastiano.dev) for the current release and setup.

## Test structure (JUnit 5)

For sequential tests, put the extension on the test class:

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

`launchMyApp()` must open a Compose Desktop window, through the app or a test harness.
The Spectre extension and rule manage the `ComposeAutomator`. The test harness manages the window.

For **parallel tests**, use `@ExtendWith` and parameter injection.
Each invocation receives its own automator:

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

Prefer `testTag` for nodes that tests click, scroll, or type into.
Copy and translation changes can break text selectors. Use text queries to check visible content.

Key `AutomatorNode` properties: `testTag`, `text`, `texts`, `contentDescription`, `role`, `isFocused`, `isDisabled`, `isSelected`, `editableText`, `boundsInWindow`, `boundsOnScreen`, `centerOnScreen`, `children`, `parent`.

## Interactions

Interaction methods are `suspend`. Call them inside `runSpectreTest { ... }`.
Plain `runBlocking` is a fallback.

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
// Multi-window composite stills: automator.screenshotWindows(listOf(0, 1)) → scene.composite
// Opt-in JUnit golds: assertMatchesGold + ScreenshotTolerance — see long skill references/junit.md
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

All five waits are `suspend` and reject EDT callers. Their default timeout is 5 s.
`waitUntil` polls the semantics tree supplied to its predicate.
Use another tool to wait for a service flag, file, or HTTP response.

## Input drivers

Use `ComposeAutomator.inProcess()` for most tests. By default, it sends
synthetic AWT events (`RobotDriver.synthetic()`) posted into the window hierarchy.
Switch drivers only when needed:

- `RobotDriver()` — real `java.awt.Robot` input; moves the actual cursor and takes OS focus.
- `RobotDriver.synthetic(rootWindow = window)` — same synthetic path as the default, pinned to a window.
- `RobotDriver.headless()` — read-only; every input or screenshot call throws `UnsupportedOperationException`. Semantics-tree reads still work.
- `ComposeAutomator.http(security, host = "localhost", port = 7654)` — experimental authenticated
  cross-JVM HTTPS (bearer required); deliberate **subset** of in-process (windows/nodes/input/
  screenshots; no live idling resources / `withTracing`). It needs matching `SpectreHttpSecurity`
  and `:server` in the target. Plaintext requires the explicit loopback-only test escape hatch.
  See <https://spectre.sebastiano.dev/guide/cross-jvm/>.
- `AgentAttach.attach(pid)` — attach to a **running** Compose JVM. The target does **not** need `spectre-core` preinstalled: when core is absent, the agent runtime injects nested `META-INF/spectre/inject-runtime.jar`. Prefer a `spectre-core` dependency for controlled target builds. The attacher needs `spectre-agent` plus `spectre-agent-runtime`.

---

For recording, IntelliJ-hosted Compose, advanced selectors, troubleshooting, and the full reference, see **[spectre.sebastiano.dev](https://spectre.sebastiano.dev)**.

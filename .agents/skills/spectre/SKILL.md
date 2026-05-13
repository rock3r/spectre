---
name: spectre
description: Use when writing, debugging, or reviewing tests that drive a live Compose Desktop UI with Spectre — the JVM/Kotlin library for automating real Compose Desktop windows (and IntelliJ/Jewel-hosted Compose) via the semantics tree plus java.awt.Robot or synthetic AWT input. Trigger on mentions of `ComposeAutomator`, `RobotDriver`, `AutomatorNode`, `findByTestTag`, `waitForNode`, `waitForIdle`, "Compose Desktop UI test", "automate a Compose window", screenshotting or recording a Compose Desktop window, or any task involving `dev.sebastiano.spectre.*` imports. Also use when the user is writing JUnit 4/5 tests against Compose Desktop, even if they don't say "Spectre" explicitly — if the test drives a real Compose window (not `runComposeUiTest`), this is the right tool.
---

# Spectre

Spectre drives **live** Compose Desktop UIs from JUnit tests. It is *not* the
Compose Multiplatform test framework (`runComposeUiTest` / `onNodeWithTag`) —
that one runs an off-screen Compose tree on a virtual clock. Spectre opens a real
window, reads its semantics tree, and feeds it real OS input via
`java.awt.Robot` (or synthetic AWT events).

Pick Spectre when the test needs to exercise the actual window, popups,
IntelliJ/Jewel-hosted Compose, or to record a real video of the UI.

## The 30-second mental model

A test owns three things, in this order:

1. **A running Compose window.** You launch your app or test harness yourself
   (e.g. `application { Window(...) { … } }`); Spectre does *not* host it for
   you.
2. **A `ComposeAutomator`.** Built once per test via
   `ComposeAutomator.inProcess()` (usually through the JUnit extension/rule),
   it discovers Compose surfaces and reads their semantics.
3. **Suspending input + synchronization calls** against that automator. All
   input methods (`click`, `typeText`, etc.) and waits are `suspend` functions
   — wrap the test body in `runBlocking { ... }`.

There is no `compose-test` style auto-wait. Every `findBy…` call is a single
read against current state. **You wait explicitly**, then you query.

## Minimal end-to-end example

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CounterTest {
    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun `clicking increment bumps the counter`() = runBlocking {
        launchCounterApp() // your harness — opens the Compose window

        val automator = automatorExt.automator
        automator.waitForNode(tag = "CounterValue")
        automator.waitForVisualIdle()

        val initial = automator.findOneByTestTag("CounterValue")
        check(initial?.text == "Count: 0")

        val increment = automator.findOneByTestTag("Increment")
            ?: error("Could not find Increment button")
        automator.click(increment)
        automator.waitForVisualIdle()

        val updated = automator.findOneByTestTag("CounterValue")
        check(updated?.text == "Count: 1")
    }
}
```

JUnit 4 users substitute `ComposeAutomatorRule` for `ComposeAutomatorExtension`
and `@get:Rule` for `@RegisterExtension`.

## Choosing a `RobotDriver`

`ComposeAutomator.inProcess()` accepts a `RobotDriver`. Default is real input
on the host OS. Three variants:

- **`RobotDriver()`** — real `java.awt.Robot` input on the host. Highest
  fidelity, but contends for global focus. Use for the default
  single-process test run.
- **`RobotDriver.synthetic(rootWindow = someTopLevelWindow)`** — dispatches
  AWT events directly into the given `java.awt.Window` hierarchy. No global
  focus contention, so safe for **parallel test JVMs** and for IDE-hosted
  Compose where stealing the IDE focus would be hostile. Does **not** see OS
  shortcuts (Cmd+Tab, system menus).
- **`RobotDriver.headless()`** — refuses to send any input. For tests that
  only read the semantics tree (e.g. asserting a screen layout) without
  driving it.

```kotlin
val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = ideFrame),
)
```

## Finding nodes

Selectors all live on `ComposeAutomator` and return `AutomatorNode` (or a list
thereof). They do **not** wait — see the synchronization section.

Use, in order of preference:

1. **`findByTestTag(tag)` / `findOneByTestTag(tag)`** — relies on
   `Modifier.testTag("…")` on the composable. The default. Most reliable.
2. **`findByText(text, exact = true)` / `findOneByText(...)`** — match
   semantics `Text`. Brittle to i18n; OK for affordances written in test
   harness code.
3. **`findByContentDescription(...)`** — accessibility descriptions.
4. **`findByRole(Role.Button)`** — semantics roles.
5. **`allNodes()`** / **`tree()`** / **`printTree()`** — for debugging.
   `printTree()` returns a human-readable dump; log it when a selector returns
   `null`.

`AutomatorNode` exposes `testTag`, `text`/`texts`, `contentDescription(s)`,
`role`, `isFocused`, `isDisabled`, `isSelected`, `editableText`, plus
coordinates: `boundsInWindow` (dp), `boundsOnScreen` (screen pixels,
post-HiDPI), `centerOnScreen`. Tree navigation via `children`/`parent`.

## Driving input

All `suspend` on `ComposeAutomator`:

- `click(node)`, `doubleClick(node)`, `longClick(node, holdFor = 500.ms)`
- `swipe(from, to, steps, duration)` or `swipe(startX, startY, endX, endY, …)`
- `scrollWheel(node, wheelClicks)`
- `typeText("hello")` — pastes via the system clipboard (faster, handles
  unicode). On macOS the clipboard write is async; Spectre polls until the
  clipboard reads back the requested text. Disable clipboard managers in CI.
- `clearAndTypeText(node, "new")` — Ctrl/Cmd+A, Backspace, then `typeText`.
- `pressKey(KeyEvent.VK_ENTER, modifiers = 0)`, `pressEnter()`.
- `performSemanticsClick(node)` — bypasses the OS entirely and invokes
  the Compose `OnClick` semantics action. Use this if focus contention is
  causing flakes and you don't need to exercise the OS input path.
- `focusWindow(node)` — raises and focuses the window hosting `node`.

## Synchronization — the part everyone gets wrong

This is the #1 source of flakes. Internalize three rules:

### Rule 1: queries don't wait

`findByTestTag("Submit")` returns whatever is in the semantics tree *right
now*. If the screen hasn't rendered yet, it returns null. Always wait before
querying state that depends on a prior action.

### Rule 2: pick the right wait

- **`waitForNode(tag = "...", timeout = 5.seconds)`** — wait until a node with
  the given tag (or text) exists. Throws on timeout. Use this when a *new*
  node must appear.
- **`waitForIdle()`** — wait until the semantics fingerprint stabilizes and
  all registered `AutomatorIdlingResource`s are idle. Use this when you've
  triggered work that updates semantics but no specific node appears.
- **`waitForVisualIdle(stableFrames = 3)`** — wait until the on-screen pixels
  are stable for N consecutive frames. Heavier than `waitForIdle`. Use it
  before screenshotting, or when work is animation-bound rather than
  semantics-bound.

A typical pattern after an interaction is `waitForVisualIdle()`. After
triggering a screen *change* (e.g. opening a dialog), `waitForNode(tag = …)`
is more precise.

### Rule 3: never call `waitForIdle`/`waitForVisualIdle` on the EDT

They drain the AWT event dispatch thread and snapshot it via
`invokeAndWait`. Calling from the EDT deadlocks. `waitForNode` is exempt.
If your dispatcher is Swing-backed, wrap the wait in
`withContext(Dispatchers.Default) { … }`. `waitForNode` does not have this
restriction.

### Rule 4: use `runBlocking`, not `runTest`

`kotlinx-coroutines-test`'s `runTest` skips `delay()`. That collapses
`longClick` hold durations, `swipe` pacing, and the macOS clipboard-settle
poll inside `typeText` to zero, breaking them all. Use `runBlocking { ... }`
in the test body.

### Custom idling resources

Background work that doesn't tick the semantics tree (custom animations,
network calls) is invisible to `waitForIdle`. Register an
`AutomatorIdlingResource` so the wait knows about it:

```kotlin
automator.registerIdlingResource(myResource)
try {
    // ...
    automator.waitForIdle()
} finally {
    automator.unregisterIdlingResource(myResource)
}
```

## Screenshots

`automator.screenshot()` returns a `BufferedImage`. Three forms:

```kotlin
automator.screenshot()                  // full desktop
automator.screenshot(region = Rectangle(x, y, w, h))
automator.screenshot(node)              // node bounds
automator.screenshot(windowIndex = 0)   // a tracked window
```

Always `waitForVisualIdle()` immediately before screenshotting — otherwise
you may capture a mid-animation frame.

## Recording, JUnit, IntelliJ-hosted Compose

These each have their own reference. Read the file *only when the task
touches that area*; they are not needed for the common case.

- **Video recording** → `references/recording.md` — `AutoRecorder`, platform
  capture backends (ScreenCaptureKit / gdigrab / ffmpeg / Wayland portal),
  region vs window targeting, frame-drop and HiDPI traps.
- **JUnit 4 vs JUnit 5 integration** → `references/junit.md` —
  `ComposeAutomatorExtension`, `ComposeAutomatorRule`, parameter resolution,
  lifecycle.
- **IntelliJ/Jewel-hosted Compose** → `references/intellij.md` — running
  the automator from an `AnAction` against the IDE frame, the
  pooled-thread requirement, and the `synthetic` driver. If the work also
  involves Jewel popups, `ComposePanel` embedding, or `SwingBridgeTheme`,
  the repo-local `jewel-swing-interop` skill applies as well.

## Common pitfalls (memorise these)

| Symptom | Cause | Fix |
|---|---|---|
| `findOneBy…` returns `null` right after an interaction | Selectors don't wait | Add `waitForNode(...)` or `waitForVisualIdle()` first |
| Test deadlocks inside `waitForIdle()` | Called from the EDT | Wrap in `withContext(Dispatchers.Default)` |
| `longClick`/`swipe`/`typeText` complete instantly and miss | Test body uses `runTest` | Switch to `runBlocking` |
| Two parallel test JVMs steal focus from each other | Both use real `RobotDriver()` | Use `RobotDriver.synthetic(rootWindow)` |
| Cmd+Tab or OS shortcuts don't work under synthetic driver | Synthetic events bypass HID | Use real `RobotDriver()` for those tests |
| Screenshot is blurry / mid-animation | Captured before frame stabilised | `waitForVisualIdle()` first |
| `typeText` times out on macOS in CI | Clipboard manager rewriting `NSPasteboard` | Disable clipboard utilities in CI |
| Region-based recording crops out popups | Popups escape the region | Use window-targeted recording |
| Window-targeted Wayland recording throws `IllegalStateException` | `xprop` missing or non-GNOME compositor | Fall back to region capture (`window = null`) |
| Coordinates derived from `boundsInWindow` land off-target on HiDPI | `boundsInWindow` is dp; screen is pixels | Use `boundsOnScreen`/`centerOnScreen`, or apply density |

## What Spectre is NOT (don't pretend it is)

- It is **not** published to Maven Central yet. Consumers wire it as a
  composite build or local clone. Do not suggest `implementation("dev.sebastiano.spectre:…")`
  unless the user confirms artifacts exist.
- It is **not** `compose-test` / `runComposeUiTest` / `onNodeWithTag`. Don't
  mix those APIs into a Spectre test.
- It does **not** capture audio. Recording is video-only.
- The cross-JVM **HTTP server is experimental** and security-caveated — do
  not recommend it for general use without flagging that.
- It does **not** auto-wait on queries. Don't write tests that assume it
  does.

## When unsure, dump the tree

The single most useful debugging primitive:

```kotlin
println(automator.printTree())
```

Run it right before a failing selector. The output names every node Spectre
can see, with tags/texts/bounds. 90% of "selector returned null" mysteries
resolve here.
